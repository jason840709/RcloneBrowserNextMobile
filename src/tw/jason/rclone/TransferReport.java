package tw.jason.rclone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one transfer did, file by file, read from rclone's output while it runs (0.7.0).
 *
 * Two kinds of lines arrive on the same stream:
 * - rclone's per-file comparison ("--combined -"): one line per file, "= path" same on both sides
 *   (or, with --ignore-existing, already in the destination), "+ path" only in the source,
 *   "- path" only in the destination, "* path" different, "! path" could not be read.
 * - rclone's JSON log at INFO level: what was actually done ("Copied (new)", "Copied (replaced
 *   existing)", "Moved", "Deleted", "Removing directory"), errors, and the statistics every second.
 *
 * When an attempt fails, rclone runs the whole job again and compares every file again. The
 * comparison counts then describe the latest attempt, and files copied by an earlier attempt are
 * taken out of "same". Actions add up over all attempts, since they really happened.
 *
 * Counts cover every file. Only the first {@link #KEEP} paths of each group are kept for the list
 * on screen. Engine error text can contain host names or tokens: lists keep the path, a plain
 * explanation, and the message with credentials and URL queries removed.
 */
public final class TransferReport {
    public static final int KEEP = 500;
    /** List groups, in the order the detail page offers them. */
    public static final String ADDED = "added", REPLACED = "replaced", MOVED = "moved", DELETED = "deleted",
            SAME = "same", ONLY_DST = "onlyDst", ERRORS = "errors",
            // 0.7.4: folders removed from the destination, and names left out because the destination cannot keep them.
            DIRS = "dirs", SKIPPED = "skipped";
    private static final String[] GROUPS = {ADDED, REPLACED, MOVED, DELETED, SAME, ONLY_DST, ERRORS, DIRS, SKIPPED};
    private static final Pattern ATTEMPT = Pattern.compile("Attempt (\\d+)/(\\d+) (failed|succeeded)");
    private static final Pattern PARTIAL = Pattern.compile("\\.[0-9a-f]{8}\\.partial$");

    // Comparison of the latest attempt
    private long same, onlySrc, onlyDst, differ, unreadable;
    // Actions done, all attempts
    private long added, replaced, moved, deleted, removedDirs, timeOnly, fileErrors;
    /** 0.7.4: names left out (see Settings.cardNameFilters); folders only in the destination found by the app's clean-up, and those kept. */
    private long skipped, extraDirs, keptDirs;
    private boolean cardNames;
    private long actionsBeforeAttempt;
    private int attempt = 1, attempts;
    private boolean deletesSkipped, recovered;
    /**
     * 0.7.2: an error that may mean a folder listing was incomplete (a folder could not be read, or an error
     * this class does not recognise). Only these make it unsafe to delete destination-only files. Errors on
     * single files (a name the destination refuses, a file that could not be written) do not.
     */
    private boolean unsafe;
    /** 0.7.2: rclone skipped deletions because of single-file errors, and the app deleted the files afterwards. */
    private boolean deletedAnyway;
    /** 0.7.3: the first error that made deletion unsafe: {msg (cleaned), object, objectType}. */
    private JSONObject unsafeReason;
    /**
     * 0.7.3: every kind of error, counted. The key is the object type and the message with paths and numbers
     * taken out, so repeated errors with different paths become one kind; one cleaned sample each.
     */
    private final LinkedHashMap<String, long[]> errorKinds = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> errorSamples = new LinkedHashMap<>();
    /**
     * 0.7.4: failed files per folder, every folder (up to 300), not only the first 500 files: {count, reason}. The
     */
    private final LinkedHashMap<String, Object[]> errorFolders = new LinkedHashMap<>();
    private JSONObject stats;
    private final LinkedHashMap<String, ArrayList<JSONArray>> lists = new LinkedHashMap<>();
    private final ArrayList<String> general = new ArrayList<>(), notes = new ArrayList<>();

    public TransferReport() { for (String g : GROUPS) lists.put(g, new ArrayList<>()); }

    /** Latest rclone statistics, or null before the first report. */
    public synchronized JSONObject stats() { return stats; }

    /** One output line. Lines that are neither form are ignored. */
    public synchronized void read(String line) {
        if (line == null || line.isEmpty()) return;
        char first = line.charAt(0);
        if (line.length() > 2 && line.charAt(1) == ' ' && "=+-*!".indexOf(first) >= 0) { compared(first, line.substring(2)); return; }
        if (first != '{') return;
        JSONObject o;
        try { o = new JSONObject(line); } catch (JSONException e) { return; }
        JSONObject st = o.optJSONObject("stats");
        if (st != null) { stats = st; return; }
        String level = o.optString("level"), msg = o.optString("msg"), object = o.optString("object");
        if ("error".equals(level)) { error(msg, object, o.optString("objectType")); return; }
        // "--checksum" between two sides without a common hash: rclone says so and compares sizes only.
        if (msg.contains("falling back to --size-only")) { note("兩邊算不出共同的檔案指紋（雜湊值），這次改成只比對大小。"); return; }
        if (!"info".equals(level) || object.isEmpty()) return;
        long size = o.optLong("size", -1);
        if (msg.startsWith("Copied")) {
            if (msg.contains("replaced existing")) { replaced++; keep(REPLACED, object, size); }
            else { added++; keep(ADDED, object, size); }
        } else if (msg.startsWith("Moved")) { moved++; keep(MOVED, object, size); }
        else if (msg.equals("Deleted")) { deleted++; keep(DELETED, object, size); }
        else if (msg.startsWith("Removing directory")) { removedDirs++; keep(DIRS, object, -1); }
        else if (msg.startsWith("Updated modification time")) timeOnly++;
    }

    private void compared(char symbol, String path) {
        switch (symbol) {
            case '=': same++; keep(SAME, path, -1); break;
            case '+': onlySrc++; break;
            case '-': onlyDst++; keep(ONLY_DST, path, -1); break;
            case '*': differ++; break;
            default: unreadable++; break;
        }
    }

    /** Paths, quoted names and numbers out of a message, so messages of one kind look the same. */
    public static String kindOf(String msg, String objectType) {
        String m = msg.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("(?<![\\w])/[^:]*", "…").replaceAll("\\d+", "N");
        return (objectType.isEmpty() ? "（無對象）" : objectType) + " | " + (m.length() > 160 ? m.substring(0, 160) + "…" : m);
    }

    private void countKind(String msg, String object, String objectType) {
        String k = kindOf(msg, objectType);
        long[] n = errorKinds.get(k);
        if (n == null) {
            if (errorKinds.size() >= 60) k = "（其他種類）";
            n = errorKinds.get(k);
            if (n == null) { n = new long[1]; errorKinds.put(k, n); errorSamples.put(k, (object.isEmpty() ? "" : object + "：") + clean(msg)); }
        }
        n[0]++;
    }

    private void markUnsafe(String msg, String object, String objectType) {
        unsafe = true;
        if (unsafeReason == null) {
            try { unsafeReason = new JSONObject().put("msg", clean(msg)).put("object", object).put("objectType", objectType).put("plain", FileOps.friendlyError(msg)); }
            catch (JSONException ignored) {}
        }
    }

    private void error(String msg, String object, String objectType) {
        countKind(msg, object, objectType);
        Matcher m = ATTEMPT.matcher(msg);
        if (m.find()) {
            attempts = Integer.parseInt(m.group(2));
            if ("succeeded".equals(m.group(3))) { recovered = true; return; }
            int failed = Integer.parseInt(m.group(1));
            // The last attempt failed: nothing runs after it, so its counts stand (0.7.0 cleared them here).
            if (failed >= attempts) return;
            // The next attempt compares everything again, and tries the failed files again.
            attempt = failed + 1;
            actionsBeforeAttempt = added + replaced + moved;
            same = onlySrc = onlyDst = differ = unreadable = fileErrors = 0;
            lists.get(SAME).clear();
            lists.get(ONLY_DST).clear();
            lists.get(ERRORS).clear();
            errorFolders.clear();
            return;
        }
        if (msg.startsWith("not deleting")) { deletesSkipped = true; return; }
        // rclone's closing summary repeats the last error; it is not a new one.
        if (msg.startsWith("Failed to sync") || msg.startsWith("Failed to copy with") || msg.startsWith("Failed to move with")) return;
        // Allow-list: only errors known to concern writing one file leave deletion safe. Anything else, including
        // messages this class has never seen, counts as possibly incomplete listing. (A first version of 0.7.2
        // listed the dangerous messages instead; rclone's real text for an unreadable folder, "failed to open
        // directory", was not on it, and the engine test caught files being deleted under that folder.)
        if (!singleFileError(msg, objectType) || listingError(msg)) markUnsafe(msg, object, objectType);
        // Errors about a whole remote ("Local file system at …") or with no object are general.
        if (object.isEmpty() || objectType.endsWith("Fs")) {
            // Not tied to one file: it might be about a whole folder, so deleting is not safe.
            markUnsafe(msg, object, objectType);
            String plain = FileOps.friendlyError(msg);
            if (general.size() < 20 && !general.contains(plain)) general.add(plain);
            return;
        }
        fileErrors++;
        ArrayList<JSONArray> into = lists.get(ERRORS);
        String path = PARTIAL.matcher(object).replaceFirst("");
        String plain = FileOps.friendlyError(msg);
        if (msg.contains("invalid argument")) {
            String odd = unusual(path);
            if (!odd.isEmpty()) plain += "名稱裡不常見的字元：" + odd + "。";
        }
        if (into.size() < KEEP) into.add(new JSONArray().put(path).put(plain).put(clean(msg)));
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        Object[] f = errorFolders.get(folder);
        if (f == null && errorFolders.size() < 300) errorFolders.put(folder, f = new Object[]{0L, plain});
        if (f != null) f[0] = (Long) f[0] + 1;
    }

    /**
     * Characters in a path that a disk might refuse, with their code points, e.g. "💛（U+1F49B）、?（U+003F）",
     * and names ending in a space or period. Shown with "invalid argument" errors so the cause can be seen.
     */
    static String unusual(String path) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (int i = 0; i < path.length(); ) {
            int cp = path.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '/') continue;
            if (cp < 0x20 || cp == 0x7F || "?:*\"<>|\\".indexOf(cp) >= 0 || cp > 0x7E && !Character.isLetterOrDigit(cp) && Character.getType(cp) != Character.SPACE_SEPARATOR
                    || cp >= 0xD800 && cp <= 0xDFFF || cp >= 0x10000) {
                String shown = cp < 0x20 || cp == 0x7F || Character.getType(cp) == Character.FORMAT ? "（看不見的字元）" : new String(Character.toChars(cp));
                found.add(shown + "（U+" + String.format(java.util.Locale.ROOT, "%04X", cp) + "）");
            }
        }
        for (String part : path.split("/")) if (part.endsWith(" ") || part.endsWith(".")) found.add("名稱結尾是" + (part.endsWith(" ") ? "空格" : "句點"));
        return android.text.TextUtils.join("、", found);
    }

    /**
     * An error about writing one file: rclone's "Failed to copy/move …" or "Failed to set modification time" on a
     * file object (objectType ends with "Object", e.g. *local.Object), such as a name the memory card refuses.
     * Folder-level errors have objectType "string" and are never single-file errors.
     */
    static boolean singleFileError(String msg, String objectType) {
        // A folder's time that could not be set (it was listed; only its metadata was not written).
        if (objectType.endsWith("Directory") && msg.startsWith("Failed to update directory timestamp")) return true;
        if (!objectType.endsWith("Object")) return false;
        return msg.startsWith("Failed to copy") || msg.startsWith("Failed to move") || msg.startsWith("Failed to set modification time")
                || msg.startsWith("partial file rename failed") || msg.startsWith("Removing failed copy");
    }

    /** rclone's messages for a folder it could not list completely. */
    static boolean listingError(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("error reading source directory") || m.contains("error reading destination directory")
                || m.contains("error listing") || m.contains("failed to list") || m.contains("readdir")
                || m.contains("directory not found") || m.contains("list failed") || m.contains("failed to open directory");
    }

    /**
     * The destination can still be cleaned up although rclone skipped it: every error was about a single file and
     * there was one attempt, so the comparison is the complete one. 0.7.2 also required destination-only files;
     * 0.7.4 does not, because folders only in the destination (empty ones included) are removed too.
     */
    public synchronized boolean canCleanAnyway() {
        return deletesSkipped && !unsafe && attempt == 1;
    }

    /**
     * The app cleaned up after rclone skipped it: destination-only files deleted (counted from rclone's "Deleted"
     * lines), {@code removed} folders removed, {@code found} folders only in the destination, {@code kept} of them
     * left because the source has them or they still held something.
     */
    public synchronized void cleanedAnyway(java.util.List<String> removed, long found, long kept) {
        deletedAnyway = true;
        deletesSkipped = false;
        removedDirs += removed.size();
        for (String d : removed) keep(DIRS, d, -1);
        extraDirs = found;
        keptDirs = kept;
    }

    /** 0.7.4: names that a memory card cannot keep were left out of this transfer (see Settings.cardNameFilters). */
    public synchronized void cardNamesLeftOut() { cardNames = true; }

    /** 0.7.4: one name left out, with the reason. */
    public synchronized void skipped(String path, String reason) {
        skipped++;
        ArrayList<JSONArray> into = lists.get(SKIPPED);
        if (into.size() < KEEP) into.add(new JSONArray().put(path).put(reason));
    }

    private void note(String text) { if (!notes.contains(text)) notes.add(text); }

    private void keep(String group, String path, long size) {
        ArrayList<JSONArray> into = lists.get(group);
        if (into.size() < KEEP) into.add(new JSONArray().put(path).put(size));
    }

    /** Drops user names, passwords and URL queries (where tokens travel), and long tails. */
    static String clean(String msg) {
        String s = msg.replaceAll("(?i)(\\w+://)[^/\\s@\"]+@", "$1")
                .replaceAll("\\?[^\\s\"']*", "?…")
                .replaceAll("(?i)((?:token|key|secret|password|pass|authorization)\\W{0,3})[^\\s\"',&]+", "$1…");
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** Totals for the record. Stored with the history entry, so it stays small. */
    public synchronized JSONObject summary() {
        JSONObject o = new JSONObject();
        try {
            o.put("added", added).put("replaced", replaced).put("moved", moved).put("deleted", deleted)
             .put("removedDirs", removedDirs).put("timeOnly", timeOnly).put("fileErrors", fileErrors)
             // Files copied by an earlier attempt show as "same" in the next one; they were not skipped.
             .put("same", Math.max(0, same - (attempt > 1 ? actionsBeforeAttempt : 0)))
             .put("onlySrc", onlySrc).put("onlyDst", onlyDst).put("differ", differ).put("unreadable", unreadable)
             .put("attempt", attempt).put("attempts", attempts).put("recovered", recovered).put("deletesSkipped", deletesSkipped)
             .put("unsafeErrors", unsafe).put("deletedAnyway", deletedAnyway)
             .put("skipped", skipped).put("extraDirs", extraDirs).put("keptDirs", keptDirs).put("cardNames", cardNames);
            if (unsafeReason != null) o.put("unsafeReason", unsafeReason);
            if (stats != null) {
                for (String k : new String[]{"bytes", "totalBytes", "transfers", "totalTransfers", "checks", "totalChecks", "listed", "deletes", "errors"})
                    o.put(k, stats.optLong(k));
                o.put("elapsed", stats.optDouble("elapsedTime", 0));
                o.put("speed", stats.optDouble("speed", 0));
            }
            if (!general.isEmpty()) o.put("general", new JSONArray(general));
            if (!notes.isEmpty()) o.put("notes", new JSONArray(notes));
        } catch (JSONException ignored) {}
        return o;
    }

    /** Summary plus the kept paths of every group: {"summary":{…},"lists":{"added":[[path,size],…],"errors":[[path,plain,raw],…]}}. */
    public synchronized JSONObject details() {
        JSONObject out = new JSONObject(), l = new JSONObject();
        try {
            for (Map.Entry<String, ArrayList<JSONArray>> e : lists.entrySet()) l.put(e.getKey(), new JSONArray(e.getValue()));
            out.put("summary", summary()).put("lists", l);
            JSONArray kinds = new JSONArray();
            for (Map.Entry<String, long[]> e : errorKinds.entrySet())
                kinds.put(new JSONObject().put("kind", e.getKey()).put("count", e.getValue()[0]).put("sample", errorSamples.get(e.getKey())));
            out.put("errorKinds", kinds);
            JSONArray folders = new JSONArray();
            for (Map.Entry<String, Object[]> e : errorFolders.entrySet())
                folders.put(new JSONObject().put("folder", e.getKey()).put("count", e.getValue()[0]).put("reason", e.getValue()[1]));
            out.put("errorFolders", folders);
        } catch (JSONException ignored) {}
        return out;
    }

    /** First copied path (new or replaced), for the check that the destination keeps modification times; null if none. */
    public synchronized String firstCopied() {
        for (String g : new String[]{ADDED, REPLACED}) if (!lists.get(g).isEmpty()) return lists.get(g).get(0).optString(0, null);
        return null;
    }

    /** Files rclone is copying right now: [{name, bytes, size, percentage, speed}], from the latest statistics. */
    public synchronized JSONArray transferring() {
        JSONArray a = stats == null ? null : stats.optJSONArray("transferring");
        return a == null ? new JSONArray() : a;
    }
}

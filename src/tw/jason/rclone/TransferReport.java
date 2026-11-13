package tw.jason.rclone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

 
public final class TransferReport {
    public static final int KEEP = 500;
     
    public static final int KEEP_ERRORS = 5000;
    public static int listLimit(String group) {
        return ERRORS.equals(group) || ONLY_DST.equals(group) || SKIPPED.equals(group) ? KEEP_ERRORS : KEEP;
    }
     
    public static final String ADDED = "added", REPLACED = "replaced", MOVED = "moved", DELETED = "deleted",
            SAME = "same", ONLY_DST = "onlyDst", ERRORS = "errors",
             
            DIRS = "dirs", SKIPPED = "skipped";
    private static final String[] GROUPS = {ADDED, REPLACED, MOVED, DELETED, SAME, ONLY_DST, ERRORS, DIRS, SKIPPED};
    private static final Pattern ATTEMPT = Pattern.compile("Attempt (\\d+)/(\\d+) (failed|succeeded)");
    private static final Pattern PARTIAL = Pattern.compile("\\.[0-9a-f]{8}\\.partial$");

     
    private long same, onlySrc, onlyDst, differ, unreadable;
     
    private long added, replaced, moved, deleted, removedDirs, timeOnly, fileErrors;
     
    private long skipped, extraDirs, keptDirs;
    private boolean cardNames;
    private long actionsBeforeAttempt;
    private int attempt = 1, attempts;
    private boolean deletesSkipped, recovered;
     
    private boolean unsafe;
     
    private boolean deletedAnyway;
    private boolean directoryCleanup;
    private String directoryCleanupProblem="";
     
    private JSONObject unsafeReason;
    private JSONObject directoryAudit;
     
    private final LinkedHashMap<String, long[]> errorKinds = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> errorSamples = new LinkedHashMap<>();
     
    private final LinkedHashMap<String, Object[]> errorFolders = new LinkedHashMap<>();
    private JSONObject stats;
    private final LinkedHashMap<String, ArrayList<JSONArray>> lists = new LinkedHashMap<>();
    private final ArrayList<String> general = new ArrayList<>(), notes = new ArrayList<>();

    public TransferReport() { for (String g : GROUPS) lists.put(g, new ArrayList<>()); }

     
    public synchronized JSONObject stats() { return stats; }

     
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
             
            if (failed >= attempts) return;
             
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
         
        if (msg.startsWith("Failed to sync") || msg.startsWith("Failed to copy with") || msg.startsWith("Failed to move with")) return;
         
         
         
         
        if (!singleFileError(msg, objectType) || listingError(msg)) markUnsafe(msg, object, objectType);
         
        if (object.isEmpty() || objectType.endsWith("Fs")) {
             
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
        if (into.size() < KEEP_ERRORS) into.add(new JSONArray().put(path).put(plain).put(clean(msg)));
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        Object[] f = errorFolders.get(folder);
        if (f == null && errorFolders.size() < 300) errorFolders.put(folder, f = new Object[]{0L, plain});
        if (f != null) f[0] = (Long) f[0] + 1;
    }

     
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

     
    static boolean singleFileError(String msg, String objectType) {
         
        if (objectType.endsWith("Directory") && msg.startsWith("Failed to update directory timestamp")) return true;
        if (!objectType.endsWith("Object")) return false;
        return msg.startsWith("Failed to copy") || msg.startsWith("Failed to move") || msg.startsWith("Failed to set modification time")
                || msg.startsWith("partial file rename failed") || msg.startsWith("Removing failed copy");
    }

     
    static boolean listingError(String msg) {
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("error reading source directory") || m.contains("error reading destination directory")
                || m.contains("error listing") || m.contains("failed to list") || m.contains("readdir")
                || m.contains("directory not found") || m.contains("list failed") || m.contains("failed to open directory");
    }

     
    public synchronized boolean canCleanAnyway() {
        return deletesSkipped && !unsafe && attempt == 1;
    }

     
    public synchronized void cleanedAnyway(java.util.List<String> removed, long found, long kept) {
        deletedAnyway = true;
        deletesSkipped = false;
        directoriesCleaned(removed, found, kept);
    }

     
    public synchronized void directoriesCleaned(java.util.List<String> removed, long found, long kept) {
        directoryCleanup = true;
        removedDirs += removed.size();
        for (String d : removed) keep(DIRS, d, -1);
        extraDirs = found;
        keptDirs = kept;
    }

    public synchronized void directoryCleanupProblem(String message) {
        directoryCleanupProblem=message;
    }

    synchronized void directoryAudit(SyncDirectoryCleanup.Result result) {
        try {
            directoryAudit = new JSONObject().put("sourceTopDirs", result.sourceTopDirs)
                    .put("targetTopDirsBefore", result.targetTopDirsBefore).put("targetTopDirsAfter", result.targetTopDirsAfter)
                    .put("keptDetails", result.keptDetails).put("keptNotListed", Math.max(0, result.kept - result.keptDetails.length()));
        } catch (JSONException ignored) {}
    }

     
    public synchronized void cardNamesLeftOut() { cardNames = true; }

     
    public synchronized void skipped(String path, String reason) {
        skipped++;
        ArrayList<JSONArray> into = lists.get(SKIPPED);
        if (into.size() < listLimit(SKIPPED)) into.add(new JSONArray().put(path).put(reason));
    }

    private void note(String text) { if (!notes.contains(text)) notes.add(text); }

    private void keep(String group, String path, long size) {
        ArrayList<JSONArray> into = lists.get(group);
        if (into.size() < listLimit(group)) into.add(new JSONArray().put(path).put(size));
    }

     
    static String clean(String msg) {
        String s = msg.replaceAll("(?i)(\\w+://)[^/\\s@\"]+@", "$1")
                .replaceAll("\\?[^\\s\"']*", "?…")
                .replaceAll("(?i)((?:token|key|secret|password|pass|authorization)\\W{0,3})[^\\s\"',&]+", "$1…");
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

     
    public synchronized JSONObject summary() {
        JSONObject o = new JSONObject();
        try {
            o.put("added", added).put("replaced", replaced).put("moved", moved).put("deleted", deleted)
             .put("removedDirs", removedDirs).put("timeOnly", timeOnly).put("fileErrors", fileErrors)
              
             .put("same", Math.max(0, same - (attempt > 1 ? actionsBeforeAttempt : 0)))
             .put("onlySrc", onlySrc).put("onlyDst", onlyDst).put("differ", differ).put("unreadable", unreadable)
             .put("attempt", attempt).put("attempts", attempts).put("recovered", recovered).put("deletesSkipped", deletesSkipped)
             .put("unsafeErrors", unsafe).put("deletedAnyway", deletedAnyway)
             .put("directoryCleanup", directoryCleanup).put("directoryCleanupProblem", directoryCleanupProblem)
             .put("skipped", skipped).put("extraDirs", extraDirs).put("keptDirs", keptDirs).put("cardNames", cardNames);
            if (unsafeReason != null) o.put("unsafeReason", unsafeReason);
            if (directoryAudit != null) o.put("directoryAudit", directoryAudit);
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

     
    public synchronized String firstCopied() {
        for (String g : new String[]{ADDED, REPLACED}) if (!lists.get(g).isEmpty()) return lists.get(g).get(0).optString(0, null);
        return null;
    }

     
    public synchronized JSONArray transferring() {
        JSONArray a = stats == null ? null : stats.optJSONArray("transferring");
        return a == null ? new JSONArray() : a;
    }
}

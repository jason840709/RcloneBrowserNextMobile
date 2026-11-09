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
            SAME = "same", ONLY_DST = "onlyDst", ERRORS = "errors";
    private static final String[] GROUPS = {ADDED, REPLACED, MOVED, DELETED, SAME, ONLY_DST, ERRORS};
    private static final Pattern ATTEMPT = Pattern.compile("Attempt (\\d+)/(\\d+) (failed|succeeded)");
    private static final Pattern PARTIAL = Pattern.compile("\\.[0-9a-f]{8}\\.partial$");

    // Comparison of the latest attempt
    private long same, onlySrc, onlyDst, differ, unreadable;
    // Actions done, all attempts
    private long added, replaced, moved, deleted, removedDirs, timeOnly, fileErrors;
    private long actionsBeforeAttempt;
    private int attempt = 1, attempts;
    private boolean deletesSkipped, recovered;
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
        else if (msg.startsWith("Removing directory")) removedDirs++;
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

    private void error(String msg, String object, String objectType) {
        Matcher m = ATTEMPT.matcher(msg);
        if (m.find()) {
            attempts = Integer.parseInt(m.group(2));
            if ("succeeded".equals(m.group(3))) { recovered = true; return; }
            // The next attempt compares everything again.
            attempt = Integer.parseInt(m.group(1)) + 1;
            actionsBeforeAttempt = added + replaced + moved;
            same = onlySrc = onlyDst = differ = unreadable = 0;
            lists.get(SAME).clear();
            lists.get(ONLY_DST).clear();
            return;
        }
        if (msg.startsWith("not deleting")) { deletesSkipped = true; return; }
        // Errors about a whole remote ("Local file system at …") or with no object are general.
        if (object.isEmpty() || objectType.endsWith("Fs")) {
            String plain = FileOps.friendlyError(msg);
            if (general.size() < 20 && !general.contains(plain)) general.add(plain);
            return;
        }
        fileErrors++;
        ArrayList<JSONArray> into = lists.get(ERRORS);
        if (into.size() < KEEP) into.add(new JSONArray().put(PARTIAL.matcher(object).replaceFirst("")).put(FileOps.friendlyError(msg)).put(clean(msg)));
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
             .put("attempt", attempt).put("attempts", attempts).put("recovered", recovered).put("deletesSkipped", deletesSkipped);
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

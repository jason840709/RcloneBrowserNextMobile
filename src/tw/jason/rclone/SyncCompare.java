package tw.jason.rclone;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sync preview ("先比對"): a dry run of the same command the transfer would use, read line by line.
 *
 * Up to 0.5.0 the preview went through {@link Rclone#run}, which stops any command after 90 seconds and
 * keeps the whole output in memory. Two folders with 300,000 files each need longer than that just to
 * be listed, so the preview was killed and reported as a connection timeout. This version has no
 * overall time limit: rclone's own connect and idle timeouts still end it when the network stops
 * answering, and the user can stop it at any time. Counts cover every change; only the first
 * {@link #KEEP} changes of each group are kept for the list on screen.
 */
public final class SyncCompare {
    /** Changes kept per group (deletions, others) for the on-screen list. */
    public static final int KEEP = 300;

    /** Live numbers. Counts come from the change lines, which rclone prints for every change. */
    public static final class Snapshot {
        /** Items rclone has listed so far on both sides (rclone's "listed" statistic). */
        public long listed;
        /** Items compared so far (rclone's "checks": a file present on both sides, or a folder). */
        public long checks;
        /** Bytes the would-be copies add up to. */
        public long bytes;
        public int updates, deletes, folders, timeOnly, other;
        public long elapsedMs;
        public int changes() { return updates + deletes + folders + other; }
    }

    public interface Listener { void progress(Snapshot s); }

    /** Outcome. {@code problem} is null on success; it never contains raw engine text. */
    public static final class Result {
        public final Snapshot totals;
        public final List<FileOps.SyncChange> shown;
        public final String problem;
        public final boolean stopped;
        /** How many of {@link #shown} are deletions (they come first). */
        public final int shownDeletes;
        Result(Snapshot t, List<FileOps.SyncChange> s, int d, String p, boolean stop) { totals = t; shown = s; shownDeletes = d; problem = p; stopped = stop; }
        /** Changes not in {@link #shown} because of the {@link #KEEP} limit. */
        public int hidden() { return totals.changes() - shown.size(); }
    }

    private volatile Process process;
    private volatile boolean stopped;

    /** Stops the comparison; {@link #run} then returns with {@code stopped} set. Safe from any thread. */
    public void stop() {
        stopped = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
    }

    public boolean isStopped() { return stopped; }

    /** Command for a dry run. {@code mirror} is "make the destination identical"; otherwise "only fill in missing files". */
    static List<String> args(Context c, String src, String dst, boolean mirror) {
        ArrayList<String> a = new ArrayList<>();
        // Same transfer flags as TransferService uses for the real run, plus --dry-run.
        if (mirror) {
            Collections.addAll(a, "sync", "--dry-run", "--create-empty-src-dirs", "--delete-after");
            // 0.7.0: the comparison method chosen in settings, exactly as the real sync uses it.
            a.addAll(Settings.compareArgs(c, src, dst));
        }
        else Collections.addAll(a, "copy", "--dry-run", "--create-empty-src-dirs", "--ignore-existing");
        // 0.7.4: names a memory card cannot keep are left out, as in the real run.
        a.addAll(Settings.cardNameFilters(dst));
        // One attempt only: rclone's default of three would compare everything again after a single unreadable file.
        Collections.addAll(a, "--use-json-log", "-v", "--stats", "1s", "--stats-log-level", "NOTICE",
                "--checkers", String.valueOf(Settings.checkers(c)), "--retries", "1", "--low-level-retries", "3",
                "--contimeout", Settings.connectTimeout(c), "--timeout", Settings.idleTimeout(c), "--", src, dst);
        return a;
    }

    /** Blocks until the comparison ends. Call from a background thread; the listener runs on that thread. */
    public Result run(Context c, String src, String dst, boolean mirror, Listener listener) {
        Snapshot s = new Snapshot();
        ArrayList<FileOps.SyncChange> deletes = new ArrayList<>(), others = new ArrayList<>();
        // Error text can hold host names or tokens: it is only matched against known phrases, never shown.
        StringBuilder errors = new StringBuilder();
        long started = android.os.SystemClock.elapsedRealtime(), lastReport = 0;
        int exit;
        try {
            Process p = Rclone.start(c, args(c, src, dst, mirror));
            process = p;
            if (stopped) p.destroyForcibly();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    read(line, s, deletes, others, errors);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (listener != null && now - lastReport >= 250) {
                        lastReport = now;
                        s.elapsedMs = now - started;
                        listener.progress(copy(s));
                    }
                }
            }
            exit = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            exit = 130;
        } catch (Exception e) {
            exit = -1;
            if (e.getMessage() != null) errors.append(e.getMessage());
        } finally {
            Process p = process;
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
        s.elapsedMs = android.os.SystemClock.elapsedRealtime() - started;
        ArrayList<FileOps.SyncChange> shown = new ArrayList<>(deletes);
        shown.addAll(others);
        String problem = null;
        if (!stopped && exit != 0) problem = "無法比對：" + FileOps.friendlyError(errors.toString());
        return new Result(s, shown, deletes.size(), problem, stopped);
    }

    private static Snapshot copy(Snapshot s) {
        Snapshot o = new Snapshot();
        o.listed = s.listed; o.checks = s.checks; o.bytes = s.bytes; o.elapsedMs = s.elapsedMs;
        o.updates = s.updates; o.deletes = s.deletes; o.folders = s.folders; o.timeOnly = s.timeOnly; o.other = s.other;
        return o;
    }

    /** Package-private for the tests: one log line into the running totals. */
    static void read(String line, Snapshot s, List<FileOps.SyncChange> deletes, List<FileOps.SyncChange> others, StringBuilder errors) {
        if (line.isEmpty() || line.charAt(0) != '{') return;
        JSONObject o;
        try { o = new JSONObject(line); } catch (JSONException e) { return; }
        JSONObject stats = o.optJSONObject("stats");
        if (stats != null) {
            s.listed = stats.optLong("listed", s.listed);
            s.checks = stats.optLong("checks", s.checks);
            s.bytes = Math.max(s.bytes, stats.optLong("bytes", 0));
            return;
        }
        String action = o.optString("skipped");
        if (!action.isEmpty()) {
            FileOps.SyncChange ch = FileOps.classify(action, o.optString("object"));
            if (ch == null) { s.timeOnly++; return; }
            switch (ch.kind) {
                case FileOps.SyncChange.UPDATE: s.updates++; break;
                case FileOps.SyncChange.DELETE: s.deletes++; break;
                case FileOps.SyncChange.FOLDER: s.folders++; break;
                default: s.other++; break;
            }
            List<FileOps.SyncChange> into = ch.kind == FileOps.SyncChange.DELETE ? deletes : others;
            if (into.size() < KEEP) into.add(ch);
            return;
        }
        if ("error".equals(o.optString("level")) && errors.length() < 8192) errors.append(o.optString("msg")).append('\n');
    }
}

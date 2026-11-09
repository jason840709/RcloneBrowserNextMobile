package tw.jason.rclone;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Finds and deletes the duplicates 0.7.1 wrote (0.7.2).
 *
 * 0.7.1 stored names a memory card cannot hold as full-width look-alikes. To keep a name that already had a real
 * full-width character apart, rclone wrote it with a "‛" (U+201B) in front: a NAS folder "A？B" was written again as
 * "A‛？B", next to the "A？B" an earlier version had copied. rclone itself cannot read names containing "‛"
 * (it lists them as "A‛‛？B" and then cannot open them, with or without an encoding setting), so a sync can never
 * remove these copies. This class works on the phone's file system directly.
 *
 * An item counts as a duplicate only when its name contains "‛" directly before one of the characters 0.7.1's
 * encoding produced, and the same name without the "‛" exists in the same folder. Items without that original are
 * listed but never deleted. Nothing is renamed.
 */
public final class DuplicateCleanup {
    private DuplicateCleanup() {}

    static final char QUOTE = '‛';
    /** Characters rclone's Windows-style encoding writes (full-width ＜＞＂：？＊｜＼／．, ␀–␟ and ␠), plus the quote itself. */
    private static final String ENCODED = "＜＞＂：？＊｜＼／．␠‛";

    public static final class Result {
        public final ArrayList<File> duplicates = new ArrayList<>();
        public final ArrayList<File> withoutOriginal = new ArrayList<>();
        public long folders, files;
    }

    public interface Progress { void scanned(long items); }

    /** The name 0.7.1 meant, or null if this name is not one 0.7.1's encoding could have produced. */
    public static String original(String name) {
        if (name.indexOf(QUOTE) < 0) return null;
        StringBuilder b = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == QUOTE && i + 1 < name.length()) {
                char next = name.charAt(i + 1);
                if (ENCODED.indexOf(next) >= 0 || next >= '␀' && next <= '␟') { changed = true; continue; }
            }
            b.append(ch);
        }
        return changed ? b.toString() : null;
    }

    /** Local destination folders of past transfers, without folders inside other listed folders. */
    static List<File> roots(Context c) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        JSONArray rows = TransferService.history(c);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String dst = r.optString("dst");
            if (!dst.startsWith("/")) continue;
            boolean dir = r.optBoolean("directory", true);
            String kind = r.optString("kind");
            if (!dir && !TransferService.MIRROR.equals(kind) && !TransferService.FILL.equals(kind)) dst = MainActivity.parent(dst);
            paths.add(dst.replaceAll("/+$", ""));
        }
        ArrayList<File> out = new ArrayList<>();
        for (String p : paths) {
            boolean inside = false;
            for (String q : paths) if (!q.equals(p) && p.startsWith(q + "/")) inside = true;
            File f = new File(p);
            if (!inside && f.isDirectory()) out.add(f);
        }
        return out;
    }

    /** Walks the folders; does not go into duplicates (everything inside them goes with them). */
    public static Result scan(List<File> roots, Progress progress) {
        Result r = new Result();
        ArrayDeque<File> todo = new ArrayDeque<>(roots);
        long seen = 0;
        while (!todo.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) break;
            File dir = todo.pop();
            String[] names = dir.list();
            if (names == null) continue;
            for (String n : names) {
                File f = new File(dir, n);
                if (++seen % 500 == 0 && progress != null) progress.scanned(seen);
                String orig = original(n);
                if (orig != null) {
                    if (new File(dir, orig).exists()) {
                        r.duplicates.add(f);
                        if (f.isDirectory()) r.folders++; else r.files++;
                    } else r.withoutOriginal.add(f);
                    continue;
                }
                if (f.isDirectory()) todo.push(f);
            }
        }
        if (progress != null) progress.scanned(seen);
        return r;
    }

    /** Deletes the duplicates found by {@link #scan}; returns the number of files and folders removed. */
    public static long delete(List<File> items) {
        long n = 0;
        for (File f : items) n += remove(f);
        return n;
    }

    private static long remove(File f) {
        long n = 0;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) n += remove(k);
        if (f.delete()) n++;
        return n;
    }
}

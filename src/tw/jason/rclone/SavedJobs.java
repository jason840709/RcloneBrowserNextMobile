package tw.jason.rclone;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transfers the user chose to keep for running again ("常用工作"). Each one stores what a transfer
 * record stores: source, destination, whether the source is a folder, and the kind (copy, move,
 * fill or mirror). Running one goes through the same checks and queue as a fresh transfer.
 * Stored in app-private storage next to the transfer history; paths only, no account data.
 */
final class SavedJobs {
    private SavedJobs() {}

    static final class Job {
        final String id, src, dst, kind;
        final boolean directory;
        String name;
        long created, lastRun;
        Job(String id, String name, String src, String dst, boolean directory, String kind, long created, long lastRun) {
            this.id = id; this.name = name; this.src = src; this.dst = dst; this.directory = directory; this.kind = kind;
            this.created = created; this.lastRun = lastRun;
        }
        boolean isSync() { return TransferService.FILL.equals(kind) || TransferService.MIRROR.equals(kind); }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("src", src).put("dst", dst).put("directory", directory)
                    .put("kind", kind).put("created", created).put("lastRun", lastRun);
        }
        static Job from(JSONObject o) {
            return new Job(o.optString("id"), o.optString("name"), o.optString("src"), o.optString("dst"), o.optBoolean("directory"),
                    o.optString("kind", TransferService.COPY), o.optLong("created"), o.optLong("lastRun"));
        }
    }

    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "saved-jobs.json")); }

    static synchronized List<Job> list(Context c) {
        ArrayList<Job> jobs = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(new String(file(c).readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o != null && !o.optString("src").isEmpty() && !o.optString("dst").isEmpty()) jobs.add(Job.from(o));
            }
        } catch (Exception ignored) {}
        return jobs;
    }

    static synchronized Job add(Context c, String name, String src, String dst, boolean directory, String kind) throws IOException {
        List<Job> jobs = list(c);
        Job job = new Job(UUID.randomUUID().toString(), name, src, dst, directory, kind, System.currentTimeMillis(), 0);
        jobs.add(0, job);
        write(c, jobs);
        return job;
    }

    static synchronized void rename(Context c, String id, String name) throws IOException {
        List<Job> jobs = list(c);
        for (Job j : jobs) if (j.id.equals(id)) j.name = name;
        write(c, jobs);
    }

    static synchronized void delete(Context c, String id) throws IOException {
        List<Job> jobs = list(c);
        for (int i = jobs.size() - 1; i >= 0; i--) if (jobs.get(i).id.equals(id)) jobs.remove(i);
        write(c, jobs);
    }

    static synchronized void markRun(Context c, String id) {
        List<Job> jobs = list(c);
        for (Job j : jobs) if (j.id.equals(id)) j.lastRun = System.currentTimeMillis();
        try { write(c, jobs); } catch (IOException ignored) {}
    }

    /** True when the same transfer is already saved, so the record menu can say so instead of saving twice. */
    static synchronized boolean contains(Context c, String src, String dst, String kind) {
        for (Job j : list(c)) if (j.src.equals(src) && j.dst.equals(dst) && j.kind.equals(kind)) return true;
        return false;
    }

    private static void write(Context c, List<Job> jobs) throws IOException {
        JSONArray rows = new JSONArray();
        try { for (Job j : jobs) rows.put(j.json()); } catch (JSONException e) { throw new IOException("無法儲存常用工作。"); }
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(rows.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (IOException e) {
            if (out != null) f.failWrite(out);
            throw new IOException("無法儲存常用工作。");
        }
    }
}

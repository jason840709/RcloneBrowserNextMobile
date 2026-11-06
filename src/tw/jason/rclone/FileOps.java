package tw.jason.rclone;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin boundary between the screens and the engine. Every rclone command, its arguments and its
 * safety checks are the ones the 0.1.0 screen used; only their location changed. Transfers still go
 * through {@link TransferService}, which is untouched.
 */
final class FileOps {
    private FileOps() {}

    static final String PHONE = "/storage/emulated/0";
    static final String DOWNLOADS = PHONE + "/Download";

    static final class Entry {
        final String name, path;
        final boolean dir;
        final long size, time;
        Entry(String n, String p, boolean d, long s, long t) { name = n; path = p; dir = d; size = s; time = t; }
    }

    static String join(String p, String n) {
        return p + (p.endsWith("/") || p.endsWith(":") ? "" : "/") + n;
    }

    static boolean isLocal(String path) { return path.startsWith("/"); }

    static List<Entry> list(Context c, String destination) throws IOException {
        ArrayList<Entry> entries = new ArrayList<>();
        if (isLocal(destination)) {
            File[] fs = new File(destination).listFiles();
            if (fs == null) throw new IOException("無法讀取這個資料夾。");
            for (File f : fs) entries.add(new Entry(f.getName(), f.getAbsolutePath(), f.isDirectory(), f.length(), f.lastModified()));
            return entries;
        }
        Rclone.Result r = Rclone.run(c, "lsjson", "--contimeout", "10s", "--timeout", "20s", "--retries", "1", "--low-level-retries", "1", "--", destination);
        if (r.code != 0) throw new IOException(friendlyError(r.err));
        try {
            JSONArray items = new JSONArray(r.out);
            for (int i = 0; i < items.length(); i++) {
                JSONObject j = items.getJSONObject(i);
                String name = j.getString("Name");
                entries.add(new Entry(name, join(destination, name), j.getBoolean("IsDir"), j.optLong("Size"), parseTime(j.optString("ModTime"))));
            }
        } catch (JSONException e) {
            throw new IOException("無法讀取資料夾內容。");
        }
        return entries;
    }

    static long parseTime(String value) {
        try { return java.time.Instant.parse(value).toEpochMilli(); } catch (Exception e) { return 0; }
    }

    /** Maps engine error text to a short reason. Raw engine text can contain hosts or tokens, so it is never shown. */
    static String friendlyError(String e) {
        String s = e == null ? "" : e.toLowerCase();
        if (s.contains("permission denied") || s.contains("access denied") || s.contains("403")) return "沒有存取權限，請確認帳號與資料夾權限。";
        if (s.contains("not found") || s.contains("not exist")) return "找不到這個位置。";
        if (s.contains("timeout") || s.contains("逾時")) return "連線逾時，請確認網路與主機是否開著。";
        if (s.contains("refused") || s.contains("no such host")) return "連不到主機，請確認位址與連接埠。";
        return "請確認網路、連線設定及檔案權限。";
    }

    static Rclone.Result mkdir(Context c, String path) { return Rclone.run(c, "mkdir", "--", path); }

    static Rclone.Result stat(Context c, String path) { return Rclone.run(c, "lsjson", "--stat", "--", path); }

    static Rclone.Result delete(Context c, Entry e) { return Rclone.run(c, e.dir ? "purge" : "deletefile", "--", e.path); }

    static Rclone.Result version(Context c) { return Rclone.run(c, "version"); }

    /** Remote name (with colon) to backend type, e.g. "nas:" to "smb". */
    static LinkedHashMap<String, String> remotes(Context c) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Rclone.Result r = Rclone.run(c, "listremotes", "--long");
        if (r.code == 0) {
            for (String line : r.out.split("\n")) {
                String t = line.trim();
                int colon = t.indexOf(':');
                if (colon < 1) continue;
                map.put(t.substring(0, colon + 1), t.substring(colon + 1).trim());
            }
            return map;
        }
        r = Rclone.run(c, "listremotes");
        if (r.code == 0) for (String s : r.out.split("\n")) if (s.trim().endsWith(":")) map.put(s.trim(), "");
        return map;
    }

    static Rclone.Result deleteRemote(Context c, String remoteWithColon) {
        return Rclone.run(c, "config", "delete", "--", remoteWithColon.substring(0, remoteWithColon.length() - 1));
    }

    static final String[] NEW_REMOTE_TYPES = {"smb", "sftp", "webdav"};

    /** type: 0 SMB, 1 SFTP, 2 WebDAV. The password is obscured by rclone, as in 0.1.0. */
    static Rclone.Result createRemote(Context c, int type, String name, String host, String user, String pass, String extra) {
        ArrayList<String> args = new ArrayList<>(Arrays.asList("config", "create", "--obscure", "--non-interactive", "--",
                name, NEW_REMOTE_TYPES[type], type == 2 ? "url" : "host", host, "user", user, "pass", pass));
        if (!extra.isEmpty()) {
            args.add(type == 0 ? "domain" : type == 1 ? "port" : "vendor");
            args.add(extra);
        }
        return Rclone.run(c, args.toArray(new String[0]));
    }

    static Rclone.Result syncPreview(Context c, String src, String dst) {
        return Rclone.run(c, "sync", "--dry-run", "--use-json-log", "--create-empty-src-dirs", "--delete-after", "-v", "--", src, dst);
    }

    /** Dry run of a folder copy that skips existing names, mirroring the flags TransferService uses for copy. */
    static Rclone.Result fillPreview(Context c, String src, String dst) {
        return Rclone.run(c, "copy", "--dry-run", "--use-json-log", "--create-empty-src-dirs", "--ignore-existing", "-v", "--", src, dst);
    }

    static final class SyncChange {
        static final int UPDATE = 0, DELETE = 1, FOLDER = 2, OTHER = 3;
        final int kind; final String object, action;
        SyncChange(int k, String o, String a) { kind = k; object = o; action = a; }
    }

    static final class SyncPlan {
        final ArrayList<SyncChange> changes = new ArrayList<>();
        int updates, deletes, folders;
        /** Entries whose content stays the same; rclone only adjusts their modification time. */
        int timeOnly;
    }

    /** Same classification as the 0.1.0 preview text: copy → 新增或更新, delete/rmdir → 刪除, mkdir → 建立資料夾. */
    static SyncPlan parseSyncPreview(String raw) throws JSONException {
        SyncPlan plan = new SyncPlan();
        for (String line : raw.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            JSONObject entry = new JSONObject(line);
            String action = entry.optString("skipped"), object = entry.optString("object");
            if (action.isEmpty()) continue;
            int kind;
            String a = action.toLowerCase();
            // rclone 1.68 reports "make directory", "update modification time" and
            // "set directory modification time"; older wording such as "mkdir" is kept too.
            if (a.contains("modification time")) { plan.timeOnly++; continue; }
            if (a.equals("copy")) { kind = SyncChange.UPDATE; plan.updates++; }
            else if (a.contains("delete") || a.equals("rmdir") || a.contains("remove directory")) { kind = SyncChange.DELETE; plan.deletes++; }
            else if (a.equals("mkdir") || a.contains("make directory")) { kind = SyncChange.FOLDER; plan.folders++; }
            else kind = SyncChange.OTHER;
            plan.changes.add(new SyncChange(kind, object, action));
        }
        return plan;
    }

    /** Validates first, then queues, so a bad target never leaves half the items queued. */
    static void enqueueAll(Context c, List<Entry> items, String target, boolean move) {
        for (Entry e : items) TransferService.validateTransfer(e.path, join(target, e.name), e.dir);
        for (Entry e : items) TransferService.enqueue(c, e.path, join(target, e.name), e.dir, move, false);
    }

    /** Reads, checks and installs an unencrypted rclone config. Returns the remote count. */
    static int importConfig(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("無法開啟這個檔案。");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 2 * 1024 * 1024) throw new IOException("設定檔過大。");
            }
            String s = b.toString("UTF-8");
            if (s.startsWith("RCLONE_ENCRYPT") || !s.matches("(?s).*\\[[^\\]\\r\\n]+\\].*"))
                throw new IOException("請選擇未加密的 rclone 設定檔。");
            File tmp = new File(c.getFilesDir(), "import.conf");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(b.toByteArray()); }
            Rclone.Result check = Rclone.run(c, "--config", tmp.getAbsolutePath(), "listremotes");
            if (check.code != 0) { tmp.delete(); throw new IOException("無法讀取這份設定檔。"); }
            File target = new File(c.getFilesDir(), "rclone.conf");
            if (!tmp.renameTo(target)) throw new IOException("無法儲存設定。");
            int count = 0;
            for (String line : check.out.split("\n")) if (line.trim().endsWith(":")) count++;
            return count;
        }
    }

    // ---- Transfer status, read from TransferService's text without changing it ----

    static final class Progress {
        boolean busy;
        long bytes = -1, total = -1;
        String speed = "";
        int waiting;
        String headline = "";
        float fraction() { return total > 0 && bytes >= 0 ? Math.min(1f, bytes / (float) total) : -1f; }
    }

    private static final java.util.regex.Pattern BYTES = java.util.regex.Pattern.compile("([\\d.]+) (B|KB|MB|GB)(?: / ([\\d.]+) (B|KB|MB|GB))?");
    private static final java.util.regex.Pattern SPEED = java.util.regex.Pattern.compile("([\\d.]+ (?:B|KB|MB|GB))/秒");
    private static final java.util.regex.Pattern QUEUE = java.util.regex.Pattern.compile("等待 (\\d+) 項$");

    static Progress progress() {
        Progress p = new Progress();
        p.busy = TransferService.busy();
        String s = TransferService.status();
        java.util.regex.Matcher q = QUEUE.matcher(s);
        if (q.find()) { p.waiting = Integer.parseInt(q.group(1)); s = s.substring(0, q.start()).replaceAll("[ ·]+$", ""); }
        p.headline = s;
        int dot = s.indexOf(" · ");
        if (dot > 0 && s.substring(0, dot).endsWith("中")) {
            String rest = s.substring(dot + 3);
            java.util.regex.Matcher m = BYTES.matcher(rest);
            if (m.lookingAt()) {
                p.bytes = bytes(m.group(1), m.group(2));
                if (m.group(3) != null) p.total = bytes(m.group(3), m.group(4));
            }
            java.util.regex.Matcher sp = SPEED.matcher(rest);
            if (sp.find()) p.speed = sp.group(1) + "/秒";
        }
        return p;
    }

    private static long bytes(String number, String unit) {
        double v;
        try { v = Double.parseDouble(number); } catch (NumberFormatException e) { return -1; }
        switch (unit) {
            case "KB": return (long) (v * 1024);
            case "MB": return (long) (v * 1024 * 1024);
            case "GB": return (long) (v * 1024 * 1024 * 1024);
            default: return (long) v;
        }
    }

    static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1073741824) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0);
    }

    static final Map<String, String> TYPE_NAMES = new LinkedHashMap<>();
    static {
        String[][] names = {{"smb", "SMB"}, {"sftp", "SFTP"}, {"ftp", "FTP"}, {"webdav", "WebDAV"}, {"drive", "Google 雲端硬碟"},
                {"onedrive", "OneDrive"}, {"dropbox", "Dropbox"}, {"s3", "S3"}, {"b2", "Backblaze B2"}, {"box", "Box"},
                {"mega", "MEGA"}, {"pcloud", "pCloud"}, {"crypt", "加密"}, {"local", "本機"}, {"http", "HTTP"}, {"azureblob", "Azure Blob"}, {"gphotos", "Google 相簿"}};
        for (String[] n : names) TYPE_NAMES.put(n[0], n[1]);
    }

    static String typeName(String type) {
        String n = TYPE_NAMES.get(type);
        return n != null ? n : type;
    }

    static int typeIcon(String type) {
        if (type == null) return R.drawable.ic_cloud;
        switch (type) {
            case "smb": case "sftp": case "ftp": return R.drawable.ic_server;
            case "crypt": return R.drawable.ic_lock;
            case "local": case "alias": case "union": return R.drawable.ic_folder;
            default: return R.drawable.ic_cloud;
        }
    }
}

package tw.jason.rclone;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Storage on this device: internal storage plus any mounted memory card or USB drive. */
final class Storage {
    private Storage() {}

    static final int INTERNAL = 0, CARD = 1, USB = 2;

    static final class Volume {
        final String root, label;
        final int kind;
        final long free, total;
        Volume(String root, String label, int kind, long free, long total) {
            this.root = root; this.label = label; this.kind = kind; this.free = free; this.total = total;
        }
    }

    private static List<Volume> cached;
    private static long cachedAt;

    /** Mounted volumes, internal first. Cached briefly because path labels ask for it often. */
    static synchronized List<Volume> volumes(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cached != null && now - cachedAt < 3000) return cached;
        cached = scan(c.getApplicationContext());
        cachedAt = now;
        return cached;
    }

    static synchronized void invalidate() { cached = null; }

    private static List<Volume> scan(Context c) {
        ArrayList<Volume> out = new ArrayList<>();
        out.add(make(FileOps.PHONE, "本機", INTERNAL));
        StorageManager sm = c.getSystemService(StorageManager.class);
        int cards = 0, usbs = 0;
        ArrayList<String[]> removable = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30 && sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v.isPrimary()) continue;
                String state = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                File dir = v.getDirectory();
                if (dir == null) continue;
                removable.add(new String[]{dir.getAbsolutePath(), String.valueOf(v.getDescription(c))});
            }
        } else {
            File[] dirs = c.getExternalFilesDirs(null);
            for (File f : dirs) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/data/");
                if (i <= 0) continue;
                String root = p.substring(0, i);
                if (root.equals(FileOps.PHONE)) continue;
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(f))
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState(f))) continue;
                String desc = "";
                if (sm != null) { StorageVolume v = sm.getStorageVolume(f); if (v != null) desc = String.valueOf(v.getDescription(c)); }
                removable.add(new String[]{root, desc});
            }
        }
        for (String[] r : removable) {
            boolean usb = r[1].toLowerCase(Locale.ROOT).contains("usb");
            String label;
            if (usb) { usbs++; label = usbs == 1 ? "USB 隨身碟" : "USB 隨身碟 " + usbs; }
            else { cards++; label = cards == 1 ? "記憶卡" : "記憶卡 " + cards; }
            out.add(make(r[0], label, usb ? USB : CARD));
        }
        return out;
    }

    private static Volume make(String root, String label, int kind) {
        long free = 0, total = 0;
        try { StatFs s = new StatFs(root); free = s.getAvailableBytes(); total = s.getTotalBytes(); } catch (Exception ignored) {}
        return new Volume(root, label, kind, free, total);
    }

    /** The volume that holds a local path, or null for paths outside every volume. */
    static Volume volumeFor(Context c, String path) {
        Volume best = null;
        for (Volume v : volumes(c)) {
            if ((path.equals(v.root) || path.startsWith(v.root + "/")) && (best == null || v.root.length() > best.root.length())) best = v;
        }
        return best;
    }

    /** "記憶卡/Download/example": the volume's name, then the path inside it (0.7.2). */
    static String label(Context c, String path) {
        Volume v = volumeFor(c, path);
        if (v == null) return path;
        return v.label + path.substring(v.root.length());
    }

    static int icon(int kind) {
        switch (kind) {
            case CARD: return R.drawable.ic_sd_card;
            case USB: return R.drawable.ic_usb;
            default: return R.drawable.ic_phone;
        }
    }
}

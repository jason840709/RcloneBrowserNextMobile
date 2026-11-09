package tw.jason.rclone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * User settings, stored in one shared file so the screens and the transfer service read the same
 * values. Every default reproduces the behaviour of 0.3.0.
 */
public final class Settings {
    private Settings() {}

    static final String FILE = "settings";
    static final String ACCENT = "accent", NIGHT = "night", ICON_FOLLOWS = "iconFollows", DENSE = "dense",
            THUMBNAILS = "thumbnails", HIDDEN = "hidden", START_AT = "startAt", BACK_EXIT = "backExit",
            TRANSFERS = "transfers", CHECKERS = "checkers", COMPARE = "compare", BWLIMIT = "bwlimitMB", SLOW_NETWORK = "slowNetwork", ASK_MOBILE = "askMobileData",
            BOOKMARKS = "bookmarks", CONFIG_STAMP = "configStamp";

    static final String PURPLE = "purple", BLUE = "blue", GREEN = "green";
    static final int NIGHT_SYSTEM = 0, NIGHT_LIGHT = 1, NIGHT_DARK = 2;
    static final int START_LAST = 0, START_HOME = 1;
    static final int BACK_EXIT_NOW = 0, BACK_EXIT_TWICE = 1;
    static final int DEFAULT_TRANSFERS = 4;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    static String accent(Context c) { return prefs(c).getString(ACCENT, PURPLE); }
    static int night(Context c) { return prefs(c).getInt(NIGHT, NIGHT_SYSTEM); }
    static boolean iconFollows(Context c) { return prefs(c).getBoolean(ICON_FOLLOWS, false); }
    static boolean dense(Context c) { return prefs(c).getBoolean(DENSE, false); }
    static boolean thumbnails(Context c) { return prefs(c).getBoolean(THUMBNAILS, true); }
    static boolean hidden(Context c) { return prefs(c).getBoolean(HIDDEN, false); }
    static int startAt(Context c) { return prefs(c).getInt(START_AT, START_LAST); }
    static int backExit(Context c) { return prefs(c).getInt(BACK_EXIT, BACK_EXIT_NOW); }
    static boolean askMobileData(Context c) { return prefs(c).getBoolean(ASK_MOBILE, false); }

    // ---- Read by TransferService (core) ------------------------------------------------------

    /** Files transferred in parallel; 0.3.0 always used 4. */
    static int transfers(Context c) {
        int n = prefs(c).getInt(TRANSFERS, DEFAULT_TRANSFERS);
        return n >= 1 && n <= 16 ? n : DEFAULT_TRANSFERS;
    }

    /**
     * Files compared in parallel (rclone's checkers). Until 0.6.0 this followed the transfer count
     * (at least 8, twice the transfers); since 0.7.0 it can be set. Unset keeps the old rule.
     */
    static int checkers(Context c) {
        int n = prefs(c).getInt(CHECKERS, 0);
        return n >= 1 && n <= 64 ? n : Math.max(8, transfers(c) * 2);
    }

    // ---- Comparison (0.7.0) -------------------------------------------------------------------
    // How "make identical" decides whether a file present on both sides is copied again. Copy, move
    // and "only fill in missing files" skip files that already exist (--ignore-existing), so they never
    // overwrite and this setting does not apply to them. The choices follow the desktop app; its
    // "no content reads" mode, "time only", "hash only" and "copy everything again" are left out (see
    // the 0.7.0 notes).

    public static final int COMPARE_DEFAULT = 0, COMPARE_SIZE = 1, COMPARE_CHECKSUM = 2;
    static final String[] COMPARE_NAMES = {"大小與修改時間（預設）", "只比對大小", "大小與內容"};

    static int compare(Context c) {
        int n = prefs(c).getInt(COMPARE, COMPARE_DEFAULT);
        return n >= 0 && n < COMPARE_NAMES.length ? n : COMPARE_DEFAULT;
    }

    static String compareName(int mode) { return COMPARE_NAMES[mode >= 0 && mode < COMPARE_NAMES.length ? mode : 0]; }

    /** rclone flags for a comparison mode. The default adds none: rclone compares size and modification time. */
    public static List<String> compareFlags(int mode) {
        ArrayList<String> a = new ArrayList<>();
        if (mode == COMPARE_SIZE) a.add("--size-only");
        else if (mode == COMPARE_CHECKSUM) a.add("--checksum");
        return a;
    }

    /**
     * Time tolerance for memory cards and USB drives. FAT keeps times to 2 seconds and exFAT to 10 ms,
     * but rclone measures the precision of the phone's own storage, so without this every file on such
     * a card would look changed. Same idea as the desktop app, which reads the disk format. Returns
     * null for phone storage and remotes.
     */
    static String modifyWindow(String src, String dst) {
        String a = removableFormat(src), b = removableFormat(dst);
        // An unknown format on a card is most likely FAT, so it gets FAT's 2 seconds.
        if ("vfat".equals(a) || "vfat".equals(b) || "".equals(a) || "".equals(b)) return "2s";
        if ("exfat".equals(a) || "exfat".equals(b)) return "10ms";
        return null;
    }

    /** "vfat", "exfat", "other" for a path on a removable volume; "" when its format is unknown; null for anything else. */
    static String removableFormat(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/|$)").matcher(path == null ? "" : path);
        if (!m.find()) return null;
        try {
            return formatFromMounts(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/mounts")), java.nio.charset.StandardCharsets.UTF_8), m.group(1));
        } catch (Exception e) { return ""; }
    }

    /** Package-private for the tests: the format of the volume with this id in a mounts table. */
    public static String formatFromMounts(String mounts, String id) {
        for (String line : mounts.split("\n")) {
            String[] f = line.split(" ");
            if (f.length < 3 || !f[1].endsWith("/" + id)) continue;
            String type = f[2].toLowerCase(java.util.Locale.ROOT);
            if (type.equals("vfat") || type.equals("msdos") || type.equals("fat")) return "vfat";
            if (type.equals("exfat") || type.equals("sdfat") || type.equals("texfat")) return "exfat";
            if (!type.startsWith("fuse") && !type.equals("sdcardfs")) return "other";
        }
        return "";
    }

    // 0.7.1 stored names a memory card cannot hold (? : * " < > | …) as full-width look-alikes and read
    // full-width characters back as the half-width ones. Names that already had full-width ？ or ： (common in
    // Japanese titles) then no longer matched, so 0.7.1 copied them again under new names. 0.7.2 removed it:
    // names are never changed. A name the destination refuses is skipped and listed as an error instead.

    /**
     * 0.7.4. Names a FAT or exFAT memory card cannot keep as they are: ending in a period or a space. These file
     * systems follow Windows rules: the card's driver silently drops a trailing period ("Y." is stored as "Y") and
     * drops or refuses a trailing space. Stored under the shorter name, the copy no longer matched its source, so
     * every "make identical" copied it again and then deleted it as a destination-only file . Such names are left out on both sides and never
     * renamed; the copy a card already holds under the shortened name is then a destination-only file. Returns
     * rclone's filter flags, or none when the destination is not a card of that kind ("other" is e.g. ext4).
     */
    static List<String> cardNameFilters(String dst) {
        ArrayList<String> a = new ArrayList<>();
        String f = removableFormat(dst);
        if (f == null || f.equals("other")) return a;
        for (String p : new String[]{"*.", "* ", "*./**", "* /**"}) { a.add("--exclude"); a.add(p); }
        return a;
    }

    /** A name a FAT or exFAT card cannot keep as it is (see {@link #cardNameFilters}). */
    static boolean cardRefuses(String name) { return name.endsWith(".") || name.endsWith(" "); }

    static final String CARD_REFUSES = "記憶卡存不了結尾是句點或空格的名稱（會被系統去掉結尾，變成另一個名稱），所以沒有複製，也沒有改名。";

    /** Comparison flags plus the time tolerance, for the real sync and its preview alike. */
    static List<String> compareArgs(Context c, String src, String dst) {
        List<String> a = compareFlags(compare(c));
        String window = modifyWindow(src, dst);
        if (window != null) { a.add("--modify-window"); a.add(window); }
        return a;
    }

    /** Bandwidth limit in MB per second; 0 means unlimited, as in 0.3.0. */
    static int bandwidthMB(Context c) {
        int n = prefs(c).getInt(BWLIMIT, 0);
        return Math.max(0, n);
    }

    /** Longer waits for slow or distant servers. 0.3.0 used 15 s to connect and 60 s without data. */
    static boolean slowNetwork(Context c) { return prefs(c).getBoolean(SLOW_NETWORK, false); }
    static String connectTimeout(Context c) { return slowNetwork(c) ? "60s" : "15s"; }
    static String idleTimeout(Context c) { return slowNetwork(c) ? "300s" : "60s"; }
    /** Folder listing waits; 0.3.0 used 10 s and 20 s. */
    static String listConnectTimeout(Context c) { return slowNetwork(c) ? "45s" : "10s"; }
    static String listIdleTimeout(Context c) { return slowNetwork(c) ? "90s" : "20s"; }

    // ---- Bookmarks ----------------------------------------------------------------------------

    static List<String> bookmarks(Context c) {
        ArrayList<String> out = new ArrayList<>();
        String raw = prefs(c).getString(BOOKMARKS, null);
        if (raw == null) { out.add(FileOps.DOWNLOADS); return out; }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    static void setBookmarks(Context c, List<String> paths) {
        prefs(c).edit().putString(BOOKMARKS, new JSONArray(paths).toString()).apply();
    }

    static boolean isBookmarked(Context c, String path) { return bookmarks(c).contains(path); }

    static void toggleBookmark(Context c, String path) {
        List<String> b = bookmarks(c);
        if (!b.remove(path)) b.add(path);
        setBookmarks(c, b);
    }

    /** One-time move of values that 0.3.0 kept in the activity's own preference file. */
    static void migrate(Context c, SharedPreferences old) {
        SharedPreferences p = prefs(c);
        if (p.contains("migrated")) return;
        SharedPreferences.Editor e = p.edit().putBoolean("migrated", true);
        if (old.contains("hidden")) e.putBoolean(HIDDEN, old.getBoolean("hidden", false));
        e.apply();
    }
}

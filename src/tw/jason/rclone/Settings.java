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
final class Settings {
    private Settings() {}

    static final String FILE = "settings";
    static final String ACCENT = "accent", NIGHT = "night", ICON_FOLLOWS = "iconFollows", DENSE = "dense",
            THUMBNAILS = "thumbnails", HIDDEN = "hidden", START_AT = "startAt", BACK_EXIT = "backExit",
            TRANSFERS = "transfers", BWLIMIT = "bwlimitMB", SLOW_NETWORK = "slowNetwork", ASK_MOBILE = "askMobileData",
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

    /** Checkers keep pace with transfers; 0.3.0 used 8 with 4 transfers. */
    static int checkers(Context c) { return Math.max(8, transfers(c) * 2); }

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

package tw.jason.rclone;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Picks the home-screen icon colour. Each colour is a launcher alias of MainActivity; exactly one is
 * enabled. Purple stays on unless the user asks the icon to follow the colour theme.
 */
final class LauncherIcon {
    private LauncherIcon() {}

    private static final String[][] ALIASES = {
            {Settings.PURPLE, "tw.jason.rclone.LauncherPurple"},
            {Settings.BLUE, "tw.jason.rclone.LauncherBlue"},
            {Settings.GREEN, "tw.jason.rclone.LauncherGreen"},
    };

    static void apply(Context c) {
        String want = Settings.iconFollows(c) ? Settings.accent(c) : Settings.PURPLE;
        PackageManager pm = c.getPackageManager();
        // Enable the wanted alias first so the app never has zero launcher entries.
        for (String[] a : ALIASES) if (a[0].equals(want)) set(pm, c, a[1], true);
        for (String[] a : ALIASES) if (!a[0].equals(want)) set(pm, c, a[1], false);
    }

    private static void set(PackageManager pm, Context c, String cls, boolean on) {
        ComponentName n = new ComponentName(c, cls);
        int target = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int now = pm.getComponentEnabledSetting(n);
        boolean effectiveOn = now == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && cls.endsWith("Purple"));
        if (effectiveOn == on) return;
        pm.setComponentEnabledSetting(n, target, PackageManager.DONT_KILL_APP);
    }
}

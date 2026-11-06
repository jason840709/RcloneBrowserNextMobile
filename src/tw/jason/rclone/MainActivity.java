package tw.jason.rclone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen coordinator. Holds the two browsing windows, the clipboard and the view mode, and turns
 * user actions into calls on {@link FileOps} and {@link TransferService}. Views are built once and
 * updated in place, so state changes animate instead of rebuilding the screen.
 */
public class MainActivity extends Activity implements PaneView.Host {
    static final int SINGLE = 0, TABS = 1, SPLIT = 2;
    private static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2;

    final ExecutorService worker = Executors.newFixedThreadPool(3);
    final Handler handler = new Handler(Looper.getMainLooper());

    final PaneView[] panes = new PaneView[2];
    int mode = SINGLE, active = 0;
    final ArrayList<FileOps.Entry> clipboard = new ArrayList<>();
    boolean moving;
    LinkedHashMap<String, String> remotes = new LinkedHashMap<>();
    int sortKey = SORT_NAME;
    boolean descending, hidden;
    float splitRatio = .5f;

    private FrameLayout root, tabsFrame;
    private LinearLayout main, tabStrip, panesBox, divider, selectionBar, clipBar;
    private final TextView[] tabLabels = new TextView[2];
    private final ImageView[] tabIcons = new ImageView[2];
    private final View[] tabIndicators = new View[2];
    private TextView syncButtonLabel;
    private ImageView syncButtonIcon, swapIcon;
    private TextView swapLabel;
    private TransferUi.Pill pill;
    private TextView snackbar;
    Overlay overlay;
    private TransferUi.Panel transferPanel;

    private int insetTop, insetNav, insetLeft, insetRight, insetIme;
    private int barShownHeight;
    private boolean selectionBarShown, clipBarShown, snackShown;

    private String lastStatus = "";
    private boolean wasBusy;
    private long busyStartedAt;
    private boolean neededAccess;
    private Runnable hidePill, hideSnack;

    // ============================================================================================
    // Lifecycle

    /** Applies the light/dark choice from Settings before any resource is read. */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        applyNightOverride(this, base);
    }

    static void applyNightOverride(android.view.ContextThemeWrapper activity, Context base) {
        int night = Settings.night(base);
        if (night == Settings.NIGHT_SYSTEM) return;
        Configuration c = new Configuration();
        c.fontScale = 0; // keep the user's font size; only the night flag is overridden
        int current = base.getResources().getConfiguration().uiMode;
        c.uiMode = (current & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night == Settings.NIGHT_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        activity.applyOverrideConfiguration(c);
    }

    /** Settings that need a fresh screen when they change. */
    private String lookKey() {
        return Settings.accent(this) + "|" + Settings.night(this) + "|" + Settings.dense(this);
    }

    private String appliedLook = "";
    private long configStamp;
    private long backPressedAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getPreferences(0);
        Settings.migrate(this, prefs);
        Ui.init(this);
        appliedLook = lookKey();
        configStamp = Settings.prefs(this).getLong(Settings.CONFIG_STAMP, 0);
        FileRow.dense = Settings.dense(this);
        FileRow.Thumbs.enabled = Settings.thumbnails(this);
        LauncherIcon.apply(this);
        mode = prefs.getInt("mode", SINGLE);
        sortKey = prefs.getInt("sort", SORT_NAME);
        descending = prefs.getBoolean("descending", false);
        hidden = Settings.hidden(this);
        splitRatio = prefs.getFloat("split", .5f);
        active = prefs.getInt("active", 0);
        if (state != null) active = state.getInt("active", active);
        active = Math.max(0, Math.min(1, active));

        setupWindow();
        build();
        boolean fromHome = Settings.startAt(this) == Settings.START_HOME && state == null;
        panes[0].path = fromHome ? FileOps.PHONE : prefs.getString("left", FileOps.PHONE);
        panes[1].path = fromHome ? FileOps.DOWNLOADS : prefs.getString("right", FileOps.DOWNLOADS);
        for (PaneView p : panes) if (FileOps.isLocal(p.path) && !new java.io.File(p.path).isDirectory()) p.path = FileOps.PHONE;
        if (state != null) restoreClipboard(state);
        layoutMode(false);
        neededAccess = needsAccess(FileOps.PHONE);
        lastStatus = TransferService.status();
        reloadRemotes();
        load(panes[0], panes[0].path, 0);
        load(panes[1], panes[1].path, 0);
        handler.post(tick);
    }

    @Override public void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("active", active);
        b.putBoolean("moving", moving);
        String[] names = new String[clipboard.size()], paths = new String[clipboard.size()];
        boolean[] dirs = new boolean[clipboard.size()];
        long[] sizes = new long[clipboard.size()], times = new long[clipboard.size()];
        for (int i = 0; i < clipboard.size(); i++) {
            FileOps.Entry e = clipboard.get(i);
            names[i] = e.name; paths[i] = e.path; dirs[i] = e.dir; sizes[i] = e.size; times[i] = e.time;
        }
        b.putStringArray("clipNames", names); b.putStringArray("clipPaths", paths);
        b.putBooleanArray("clipDirs", dirs); b.putLongArray("clipSizes", sizes); b.putLongArray("clipTimes", times);
    }

    private void restoreClipboard(Bundle b) {
        String[] names = b.getStringArray("clipNames"), paths = b.getStringArray("clipPaths");
        boolean[] dirs = b.getBooleanArray("clipDirs");
        long[] sizes = b.getLongArray("clipSizes"), times = b.getLongArray("clipTimes");
        if (names == null || paths == null || dirs == null || sizes == null || times == null) return;
        for (int i = 0; i < names.length; i++) clipboard.add(new FileOps.Entry(names[i], paths[i], dirs[i], sizes[i], times[i]));
        moving = b.getBoolean("moving");
    }

    @Override protected void onPause() {
        super.onPause();
        getPreferences(0).edit().putInt("mode", mode).putString("left", panes[0].path).putString("right", panes[1].path)
                .putInt("sort", sortKey).putBoolean("descending", descending)
                .putFloat("split", splitRatio).putInt("active", active).apply();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!lookKey().equals(appliedLook)) { recreate(); return; }
        Storage.invalidate();
        hidden = Settings.hidden(this);
        FileRow.Thumbs.enabled = Settings.thumbnails(this);
        long stamp = Settings.prefs(this).getLong(Settings.CONFIG_STAMP, 0);
        if (stamp != configStamp) { configStamp = stamp; afterConfigReplaced(); }
        boolean now = needsAccess(FileOps.PHONE);
        if (neededAccess && !now) for (PaneView p : panes) if (FileOps.isLocal(p.path)) load(p, p.path, 0);
        neededAccess = now;
        for (PaneView p : panes) p.refresh();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (mode == SPLIT) layoutMode(false);
    }

    // ============================================================================================
    // Window and layout

    private void setupWindow() {
        setupWindow(this);
    }

    static void setupWindow(Activity a) {
        Window w = a.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.surface));
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            w.getDecorView(); // the insets controller exists only after the decor view is created
            android.view.WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(Ui.dark ? 0 : light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!Ui.dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
            // Android 8.0 cannot draw dark navigation buttons, so keep a scrim behind them.
            if (Build.VERSION.SDK_INT < 27 && !Ui.dark) w.setNavigationBarColor(0x66000000);
        }
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.surface);
        main = Ui.column(this);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        tabStrip = buildTabStrip();
        main.addView(tabStrip, new LinearLayout.LayoutParams(-1, -2));
        panesBox = new LinearLayout(this);
        main.addView(panesBox, new LinearLayout.LayoutParams(-1, 0, 1));

        panes[0] = new PaneView(this, 0, this);
        panes[1] = new PaneView(this, 1, this);
        tabsFrame = new FrameLayout(this);
        divider = buildDivider();

        selectionBar = Ui.row(this);
        styleBar(selectionBar);
        clipBar = Ui.row(this);
        styleBar(clipBar);
        root.addView(selectionBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(clipBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        pill = new TransferUi.Pill(this);
        pill.setOnClickListener(v -> showTransfers());
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        pp.rightMargin = Ui.dp(16);
        root.addView(pill, pp);

        snackbar = Ui.text(this, "", 14.5f, Ui.inverseInk);
        snackbar.setBackground(Ui.round(Ui.inverseSurface, 12));
        snackbar.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        snackbar.setElevation(Ui.dp(6));
        snackbar.setVisibility(View.GONE);
        snackbar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.leftMargin = sp.rightMargin = Ui.dp(12);
        root.addView(snackbar, sp);

        overlay = new Overlay(this, root, main);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, nav, left, right, ime;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets im = insets.getInsets(WindowInsets.Type.ime());
                top = sys.top; nav = sys.bottom; left = sys.left; right = sys.right; ime = im.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                nav = insets.getStableInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                ime = bottom > nav ? bottom : 0;
            }
            insetTop = top; insetNav = nav; insetLeft = left; insetRight = right; insetIme = ime;
            applyInsets();
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
    }

    private void styleBar(LinearLayout bar) {
        bar.setBackgroundColor(Ui.surfaceContainer);
        bar.setElevation(Ui.dp(3));
        bar.setVisibility(View.GONE);
        bar.setClickable(true);
    }

    private void applyInsets() {
        main.setPadding(insetLeft, 0, insetRight, insetIme > insetNav ? insetIme - insetNav : 0);
        boolean landscapeSplit = mode == SPLIT && isLandscape();
        tabStrip.setPadding(Ui.dp(8), insetTop, Ui.dp(8), 0);
        for (int i = 0; i < 2; i++) {
            boolean touchesTop = mode == TABS ? false : mode == SINGLE ? i == active : (i == 0 || landscapeSplit);
            panes[i].setTopInset(touchesTop ? insetTop : 0);
        }
        if (landscapeSplit) divider.setPadding(0, insetTop, 0, insetNav);
        else divider.setPadding(0, 0, 0, 0);
        for (LinearLayout bar : new LinearLayout[]{selectionBar, clipBar}) bar.setPadding(insetLeft + Ui.dp(4), 0, insetRight + Ui.dp(4), insetNav);
        overlay.insets(insetTop, insetNav, insetIme, insetLeft, insetRight);
        updateBottomSpace();
    }

    boolean isLandscape() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }

    /** Places the two windows for the current mode. Pane views are reused, never rebuilt. */
    void layoutMode(boolean animate) {
        for (PaneView p : panes) { ViewGroup parent = (ViewGroup) p.getParent(); if (parent != null) parent.removeView(p); }
        if (divider.getParent() != null) ((ViewGroup) divider.getParent()).removeView(divider);
        if (tabsFrame.getParent() != null) ((ViewGroup) tabsFrame.getParent()).removeView(tabsFrame);
        panesBox.removeAllViews();
        tabStrip.setVisibility(mode == TABS ? View.VISIBLE : View.GONE);
        if (mode == SINGLE) {
            panesBox.setOrientation(LinearLayout.VERTICAL);
            panesBox.addView(panes[active], new LinearLayout.LayoutParams(-1, -1));
        } else if (mode == TABS) {
            panesBox.setOrientation(LinearLayout.VERTICAL);
            tabsFrame.addView(panes[0], new FrameLayout.LayoutParams(-1, -1));
            tabsFrame.addView(panes[1], new FrameLayout.LayoutParams(-1, -1));
            panes[0].setVisibility(active == 0 ? View.VISIBLE : View.GONE);
            panes[1].setVisibility(active == 1 ? View.VISIBLE : View.GONE);
            panesBox.addView(tabsFrame, new LinearLayout.LayoutParams(-1, -1));
        } else {
            boolean land = isLandscape();
            panesBox.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            orientDivider(land);
            panesBox.addView(panes[0], land ? new LinearLayout.LayoutParams(0, -1, splitRatio) : new LinearLayout.LayoutParams(-1, 0, splitRatio));
            panesBox.addView(divider, land ? new LinearLayout.LayoutParams(-2, -1) : new LinearLayout.LayoutParams(-1, -2));
            panesBox.addView(panes[1], land ? new LinearLayout.LayoutParams(0, -1, 1 - splitRatio) : new LinearLayout.LayoutParams(-1, 0, 1 - splitRatio));
        }
        for (PaneView p : panes) if (mode != TABS) p.setVisibility(View.VISIBLE);
        if (animate && Ui.animationsOn()) {
            panesBox.setAlpha(0f);
            panesBox.animate().alpha(1f).setDuration(220).setInterpolator(Ui.STANDARD).start();
        }
        applyInsets();
        updateActiveLook();
        updateBars();
    }

    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        layoutMode(true);
    }

    // ---- Tabs ---------------------------------------------------------------------------------

    private LinearLayout buildTabStrip() {
        LinearLayout strip = Ui.row(this);
        strip.setBackgroundColor(Ui.surface);
        for (int i = 0; i < 2; i++) {
            final int k = i;
            FrameLayout tab = new FrameLayout(this);
            tab.setBackground(Ui.press(0, 12));
            LinearLayout content = Ui.row(this);
            content.setGravity(Gravity.CENTER);
            content.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            tabIcons[i] = Ui.icon(this, R.drawable.ic_phone, Ui.muted);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
            ip.rightMargin = Ui.dp(8);
            content.addView(tabIcons[i], ip);
            tabLabels[i] = Ui.oneLine(Ui.title(this, "", 15, Ui.muted), TextUtils.TruncateAt.MIDDLE);
            content.addView(tabLabels[i]);
            tab.addView(content, new FrameLayout.LayoutParams(-1, -1));
            tabIndicators[i] = new View(this);
            tabIndicators[i].setBackground(Ui.roundTop(Ui.primary, 3));
            FrameLayout.LayoutParams ind = new FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            tab.addView(tabIndicators[i], ind);
            tab.setOnClickListener(v -> switchTab(k));
            strip.addView(tab, new LinearLayout.LayoutParams(0, Ui.dp(44), 1));
        }
        return strip;
    }

    private void switchTab(int k) {
        if (k == active) return;
        PaneView from = panes[active], to = panes[k];
        active = k;
        if (mode == TABS) {
            // Cancel first: a pending end action from a quick earlier switch must not hide the new tab.
            from.animate().cancel();
            to.animate().cancel();
            from.setAlpha(1f);
            to.setVisibility(View.VISIBLE);
            to.bringToFront();
            if (Ui.animationsOn()) {
                to.setAlpha(0f);
                to.animate().alpha(1f).setDuration(180).setInterpolator(Ui.STANDARD)
                        .withEndAction(() -> { if (panes[active] != from) from.setVisibility(View.GONE); }).start();
            } else { to.setAlpha(1f); from.setVisibility(View.GONE); }
        }
        updateActiveLook();
        updateBars();
    }

    private void updateTabs() {
        for (int i = 0; i < 2; i++) {
            boolean on = i == active;
            tabLabels[i].setText(panes[i].label(panes[i].path));
            tabLabels[i].setTextColor(on ? Ui.ink : Ui.muted);
            tabIcons[i].setImageResource(locationIcon(panes[i].path));
            tabIcons[i].setImageTintList(android.content.res.ColorStateList.valueOf(on ? Ui.primary : Ui.muted));
            float target = on ? 1f : 0f;
            if (tabIndicators[i].getAlpha() != target) tabIndicators[i].animate().alpha(target).scaleX(on ? 1f : .4f).setDuration(200).setInterpolator(Ui.STANDARD).start();
            ((View) tabLabels[i].getParent().getParent()).setContentDescription(panes[i].label(panes[i].path) + (on ? "，目前分頁" : "，分頁"));
        }
    }

    // ---- Split divider ------------------------------------------------------------------------

    private LinearLayout buildDivider() {
        LinearLayout d = new LinearLayout(this);
        d.setGravity(Gravity.CENTER);
        // Same purple family as the active header, strong enough to read as the boundary between windows.
        d.setBackgroundColor(Ui.splitBar);
        LinearLayout swap = Ui.row(this);
        swap.setGravity(Gravity.CENTER);
        swap.setPadding(Ui.dp(10), 0, Ui.dp(12), 0);
        swap.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.onSplitBar, .25f)), null, Ui.round(0xffffffff, 18)));
        swapIcon = Ui.icon(this, R.drawable.ic_swap_vert, Ui.onSplitBar);
        swap.addView(swapIcon, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        swapLabel = Ui.title(this, "交換", 14, Ui.onSplitBar);
        swapLabel.setPadding(Ui.dp(6), 0, 0, 0);
        swap.addView(swapLabel);
        swap.setOnClickListener(v -> swapPanes());
        swap.setTag("swap");
        d.addView(swap);
        View handle = new View(this);
        handle.setBackground(Ui.round(Ui.alpha(Ui.onSplitBar, .55f), 2));
        handle.setTag("handle");
        d.addView(handle);
        LinearLayout sync = Ui.row(this);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(Ui.dp(12), 0, Ui.dp(14), 0);
        // A filled chip so it reads as a button that opens something, not a status label.
        sync.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.onSplitBar, .25f)),
                Ui.round(Ui.alpha(Ui.onSplitBar, .18f), 18), Ui.round(0xffffffff, 18)));
        syncButtonIcon = Ui.icon(this, R.drawable.ic_sync, Ui.onSplitBar);
        sync.addView(syncButtonIcon, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        syncButtonLabel = Ui.title(this, "同步設定", 14, Ui.onSplitBar);
        syncButtonLabel.setPadding(Ui.dp(6), 0, 0, 0);
        sync.addView(syncButtonLabel);
        sync.setOnClickListener(v -> openSync());
        sync.setTag("sync");
        d.addView(sync);
        // Drag anywhere on the divider (outside its buttons) to resize the two windows.
        d.setOnTouchListener(new View.OnTouchListener() {
            float startRatio, start;
            @Override public boolean onTouch(View v, MotionEvent e) {
                boolean land = panesBox.getOrientation() == LinearLayout.HORIZONTAL;
                float pos = land ? e.getRawX() : e.getRawY();
                float size = land ? panesBox.getWidth() - divider.getWidth() : panesBox.getHeight() - divider.getHeight();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: start = pos; startRatio = splitRatio; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (size <= 0) return true;
                        setSplitRatio(startRatio + (pos - start) / size);
                        return true;
                    default: return true;
                }
            }
        });
        return d;
    }

    private void orientDivider(boolean landscape) {
        divider.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View handle = divider.findViewWithTag("handle");
        View sync = divider.findViewWithTag("sync");
        handle.setLayoutParams(landscape ? new LinearLayout.LayoutParams(Ui.dp(4), 0, 1) : new LinearLayout.LayoutParams(0, Ui.dp(4), 1));
        ((LinearLayout.LayoutParams) handle.getLayoutParams()).gravity = Gravity.CENTER;
        if (landscape) {
            handle.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(4), Ui.dp(32)));
            LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) handle.getLayoutParams();
            hp.topMargin = hp.bottomMargin = Ui.dp(24);
        } else {
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(4));
            hp.leftMargin = hp.rightMargin = Ui.dp(8);
            handle.setLayoutParams(hp);
        }
        divider.setMinimumHeight(landscape ? 0 : Ui.dp(44));
        divider.setMinimumWidth(landscape ? Ui.dp(52) : 0);
        swapIcon.setImageResource(landscape ? R.drawable.ic_swap_horiz : R.drawable.ic_swap_vert);
        View swap = divider.findViewWithTag("swap");
        swap.setContentDescription(landscape ? "交換左右窗格" : "交換上下窗格");
        swap.setLayoutParams(new LinearLayout.LayoutParams(-2, landscape ? Ui.dp(48) : Ui.dp(36)));
        swapLabel.setVisibility(landscape ? View.GONE : View.VISIBLE);
        syncButtonLabel.setVisibility(landscape ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, landscape ? Ui.dp(48) : Ui.dp(32));
        sp.rightMargin = landscape ? 0 : Ui.dp(8);
        sync.setLayoutParams(sp);
        sync.setPadding(Ui.dp(landscape ? 14 : 12), 0, Ui.dp(14), 0);
        // Push the sync button to the far end and keep the handle centred.
        divider.removeView(handle);
        divider.removeView(sync);
        View spacerA = new View(this), spacerB = new View(this);
        spacerA.setTag("spacer");
        spacerB.setTag("spacer");
        for (int i = divider.getChildCount() - 1; i >= 0; i--) if ("spacer".equals(divider.getChildAt(i).getTag())) divider.removeViewAt(i);
        divider.addView(spacerA, landscape ? new LinearLayout.LayoutParams(1, 0, 1) : new LinearLayout.LayoutParams(0, 1, 1));
        divider.addView(handle);
        divider.addView(spacerB, landscape ? new LinearLayout.LayoutParams(1, 0, 1) : new LinearLayout.LayoutParams(0, 1, 1));
        divider.addView(sync);
    }

    private void setSplitRatio(float r) {
        splitRatio = Math.max(.22f, Math.min(.78f, r));
        LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) panes[0].getLayoutParams();
        LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) panes[1].getLayoutParams();
        if (a == null || b == null) return;
        a.weight = splitRatio;
        b.weight = 1 - splitRatio;
        panesBox.requestLayout();
    }

    private void swapPanes() {
        PaneView a = panes[0];
        panes[0] = panes[1];
        panes[1] = a;
        active = 1 - active;
        layoutMode(true);
    }

    private void updateSyncButton() {
        boolean land = isLandscape();
        String from = active == 0 ? (land ? "左側" : "上方") : (land ? "右側" : "下方");
        String to = active == 0 ? (land ? "右側" : "下方") : (land ? "左側" : "上方");
        ((View) syncButtonLabel.getParent()).setContentDescription("同步設定，目前由" + from + "到" + to + "，先選方式再預覽，不會直接開始");
    }

    void updateActiveLook() {
        for (int i = 0; i < 2; i++) panes[i].setActiveLook(i == active, mode == SPLIT);
        if (mode == TABS) updateTabs();
        if (mode == SPLIT) updateSyncButton();
    }

    // ============================================================================================
    // Bottom bars, pill and snackbar

    void updateBars() {
        PaneView p = panes[active];
        boolean selecting = !p.selected.isEmpty();
        boolean clip = !selecting && !clipboard.isEmpty();
        if (selecting) fillSelectionBar(p);
        if (clip) fillClipBar();
        selectionBarShown = slide(selectionBar, selecting, selectionBarShown);
        clipBarShown = slide(clipBar, clip, clipBarShown);
        selectionBar.post(this::updateBottomSpace);
    }

    private boolean slide(View bar, boolean show, boolean wasShown) {
        if (show == wasShown) return show;
        bar.animate().cancel();
        if (show) {
            bar.setVisibility(View.VISIBLE);
            if (Ui.animationsOn()) {
                bar.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
                bar.setTranslationY(bar.getTranslationY() == 0 ? bar.getMeasuredHeight() : bar.getTranslationY());
                bar.animate().translationY(0).setDuration(280).setInterpolator(Ui.ENTER).withEndAction(null).start();
            } else bar.setTranslationY(0);
        } else {
            if (Ui.animationsOn()) {
                bar.animate().translationY(bar.getHeight()).setDuration(180).setInterpolator(Ui.EXIT)
                        .withEndAction(() -> { bar.setVisibility(View.GONE); bar.setTranslationY(0); }).start();
            } else bar.setVisibility(View.GONE);
        }
        return show;
    }

    /** Lists keep their last rows reachable above whatever floats at the bottom. */
    private void updateBottomSpace() {
        int bar = selectionBarShown ? selectionBar.getHeight() : clipBarShown ? clipBar.getHeight() : 0;
        if ((selectionBarShown || clipBarShown) && bar == 0) bar = Ui.dp(68) + insetNav;
        barShownHeight = bar;
        int base = Math.max(bar, insetNav);
        for (int i = 0; i < 2; i++) {
            boolean touchesBottom = mode != SPLIT || i == 1 || isLandscape();
            panes[i].setBottomPadding(touchesBottom ? base : 0);
        }
        placeFloating();
    }

    private void placeFloating() {
        int base = Math.max(barShownHeight, insetNav) + (insetIme > insetNav ? insetIme - insetNav : 0);
        float snackLift = base + Ui.dp(12);
        snackbar.setTranslationY(-snackLift);
        float pillLift = base + Ui.dp(16) + (snackShown ? snackbar.getHeight() + Ui.dp(8) : 0);
        if (pill.getVisibility() == View.VISIBLE && Ui.animationsOn()) pill.animate().translationY(-pillLift).setDuration(220).setInterpolator(Ui.STANDARD).start();
        else pill.setTranslationY(-pillLift);
    }

    private View barAction(int icon, String label, boolean enabled, Runnable action) {
        LinearLayout b = Ui.column(this);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, Ui.dp(10), 0, Ui.dp(8));
        b.setMinimumHeight(Ui.dp(68));
        int color = enabled ? Ui.ink : Ui.alpha(Ui.ink, .38f);
        b.addView(Ui.icon(this, icon, color), new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
        TextView t = Ui.oneLine(Ui.text(this, label, 12.5f, color), TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(2), Ui.dp(4), Ui.dp(2), 0);
        b.addView(t, new LinearLayout.LayoutParams(-1, -2));
        b.setContentDescription(label);
        if (enabled) { b.setBackground(Ui.press(0, 16)); b.setOnClickListener(v -> action.run()); }
        else b.setEnabled(false);
        return b;
    }

    private void fillSelectionBar(PaneView p) {
        selectionBar.removeAllViews();
        int count = p.selected.size();
        boolean one = count == 1;
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, -2, 1);
        if (mode == SPLIT) {
            boolean land = isLandscape();
            String where = active == 0 ? (land ? "右側" : "下方") : (land ? "左側" : "上方");
            selectionBar.addView(barAction(R.drawable.ic_copy, "複製到" + where, true, () -> transferToOther(false)), w);
            selectionBar.addView(barAction(R.drawable.ic_move, "移動到" + where, true, () -> transferToOther(true)), new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            selectionBar.addView(barAction(R.drawable.ic_copy, "複製", true, () -> toClipboard(p, false)), w);
            selectionBar.addView(barAction(R.drawable.ic_move, "移動", true, () -> toClipboard(p, true)), new LinearLayout.LayoutParams(0, -2, 1));
        }
        selectionBar.addView(barAction(R.drawable.ic_edit, "重新命名", one, () -> rename(p, p.selectedEntries().get(0))), new LinearLayout.LayoutParams(0, -2, 1));
        selectionBar.addView(barAction(R.drawable.ic_delete, "刪除", true, () -> delete(p, p.selectedEntries())), new LinearLayout.LayoutParams(0, -2, 1));
        selectionBar.addView(barAction(R.drawable.ic_info, "詳細資料", one, () -> details(p.selectedEntries().get(0))), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void fillClipBar() {
        clipBar.removeAllViews();
        clipBar.setPadding(insetLeft + Ui.dp(16), Ui.dp(10), insetRight + Ui.dp(12), Ui.dp(10) + insetNav);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Ui.round(Ui.primaryContainer, 12));
        tile.addView(Ui.icon(this, moving ? R.drawable.ic_move : R.drawable.ic_copy, Ui.primary), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
        tp.rightMargin = Ui.dp(14);
        clipBar.addView(tile, tp);
        LinearLayout texts = Ui.column(this);
        String what = clipboard.size() == 1 ? "「" + clipboard.get(0).name + "」" : clipboard.size() + " 項";
        String verb = moving ? "移動" : "複製";
        texts.addView(Ui.oneLine(Ui.title(this, clipboard.size() == 1 ? verb + what : verb + " " + what, 15.5f, Ui.ink), TextUtils.TruncateAt.MIDDLE));
        TextView rule = Ui.text(this, moving ? "同名檔案略過、原檔保留；其餘傳完才刪原檔" : "同名檔案會略過", 12.5f, Ui.muted);
        rule.setMaxLines(2);
        rule.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(rule);
        clipBar.addView(texts, Ui.weight(1));
        clipBar.addView(Ui.button(this, "取消", Ui.ButtonStyle.TEXT, v -> { clipboard.clear(); updateBars(); }));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.leftMargin = Ui.dp(4);
        clipBar.addView(Ui.button(this, "貼上", Ui.ButtonStyle.FILLED, v -> paste()), pp);
    }

    void snack(String message) {
        handler.removeCallbacks(hideSnack != null ? hideSnack : () -> {});
        snackbar.setText(message);
        snackbar.animate().cancel();
        snackbar.setVisibility(View.VISIBLE);
        snackShown = true;
        snackbar.post(() -> {
            placeFloating();
            if (Ui.animationsOn()) {
                snackbar.setAlpha(0f);
                snackbar.setScaleY(.9f);
                snackbar.animate().alpha(1f).scaleY(1f).setDuration(200).setInterpolator(Ui.ENTER).withEndAction(null).start();
            }
        });
        hideSnack = () -> {
            snackShown = false;
            snackbar.animate().alpha(0f).setDuration(160).setInterpolator(Ui.EXIT).withEndAction(() -> { if (!snackShown) snackbar.setVisibility(View.GONE); }).start();
            placeFloating();
        };
        handler.postDelayed(hideSnack, 3200);
    }

    // ============================================================================================
    // PaneView.Host

    @Override public void activate(PaneView p) {
        if (panes[active] == p) return;
        active = p == panes[0] ? 0 : 1;
        updateActiveLook();
        updateBars();
    }

    @Override public void openEntry(PaneView p, FileOps.Entry e) {
        if (e.dir) { load(p, e.path, 1); return; }
        if (!FileOps.isLocal(e.path)) {
            overlay.new Dialog(e.name, "下載到本機的 Download 資料夾後，再從那裡開啟。那裡已有同名檔案時不會重新下載，開到的會是舊的那份。")
                    .action("取消", Ui.ButtonStyle.TEXT, null)
                    .action("下載", Ui.ButtonStyle.FILLED, () -> whenNetworkOk(true, () -> {
                        try {
                            askNotifications();
                            TransferService.enqueue(this, e.path, FileOps.join(FileOps.DOWNLOADS, e.name), false, false, false);
                            transferStarted();
                        } catch (Exception ex) { error(ex); }
                    })).show();
            return;
        }
        try {
            Storage.Volume v = Storage.volumeFor(this, e.path);
            String authority = v == null || v.kind == Storage.INTERNAL ? "tw.jason.rclone.files" : VolumeProvider.AUTHORITY;
            Uri u = new Uri.Builder().scheme("content").authority(authority).path(e.path).build();
            Intent in = new Intent(Intent.ACTION_VIEW).setDataAndType(u, getContentResolver().getType(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(in, "開啟「" + e.name + "」"));
        } catch (Exception ex) {
            message(null, "手機裡沒有能開啟這個檔案的 App。");
        }
    }

    @Override public void selectionChanged(PaneView p) {
        if (p == panes[active]) updateBars();
    }

    @Override public void navigate(PaneView p, String path) {
        load(p, path, isAncestor(path, p.path) ? -1 : 1);
    }

    @Override public void retry(PaneView p) {
        load(p, p.failed.isEmpty() ? p.path : p.failed, 0);
    }

    @Override public boolean needsAccess(String path) {
        if (!FileOps.isLocal(path)) return false;
        if (Build.VERSION.SDK_INT >= 30) return !Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    @Override public void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())), 42);
            } catch (Exception e) {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 42);
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("askedNotify", false)) {
            getPreferences(0).edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == 42) for (PaneView pane : panes) if (FileOps.isLocal(pane.path)) load(pane, pane.path, 0);
    }

    @Override public String rootLabel(String root) {
        if (root.equals("/")) return "根目錄";
        if (root.endsWith(":")) return root.substring(0, root.length() - 1);
        Storage.Volume v = Storage.volumeFor(this, root);
        return v != null && v.root.equals(root) ? v.label : root;
    }

    /** Volume root for a local path ("/" when it is outside every volume); the remote name for remotes. */
    @Override public String localRoot(String path) {
        if (!FileOps.isLocal(path)) return path.substring(0, path.indexOf(':') + 1);
        Storage.Volume v = Storage.volumeFor(this, path);
        return v != null ? v.root : "/";
    }

    @Override public int locationIcon(String path) {
        if (FileOps.isLocal(path)) {
            Storage.Volume v = Storage.volumeFor(this, path);
            return Storage.icon(v != null ? v.kind : Storage.INTERNAL);
        }
        int colon = path.indexOf(':');
        return FileOps.typeIcon(remotes.get(path.substring(0, colon + 1)));
    }

    @Override public Comparator<FileOps.Entry> comparator() {
        final int dir = descending ? -1 : 1;
        return (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int c;
            if (sortKey == SORT_DATE) c = Long.compare(a.time, b.time);
            else if (sortKey == SORT_SIZE && !a.dir) c = Long.compare(a.size, b.size);
            else c = 0;
            if (c == 0) c = natural(a.name, b.name);
            return dir * c;
        };
    }

    /** Name order that puts "2" before "10", case-insensitive. */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    @Override public boolean showHidden() { return hidden; }

    // ============================================================================================
    // Loading

    void load(PaneView p, String destination, int direction) {
        final int generation = ++p.generation;
        p.setLoading(true);
        worker.execute(() -> {
            List<FileOps.Entry> entries = null;
            String failure = null;
            try { entries = FileOps.list(this, destination); }
            catch (Exception e) { failure = e.getMessage() == null ? "無法讀取這個資料夾。" : e.getMessage(); }
            final List<FileOps.Entry> result = entries;
            final String err = failure;
            handler.post(() -> {
                if (isDestroyed() || generation != p.generation) return;
                p.setLoading(false);
                if (err == null) p.showEntries(destination, result, direction);
                else p.showError(destination, err);
                if (p == panes[active]) updateBars();
                if (mode == TABS) updateTabs();
            });
        });
    }

    boolean isRoot(String path) { return path.endsWith(":") || path.equals("/") || localRoot(path).equals(path); }

    static String parent(String p) {
        int slash = p.lastIndexOf('/');
        String dest = slash >= 0 ? p.substring(0, slash) : p.substring(0, p.indexOf(':') + 1);
        if (!FileOps.isLocal(p) && dest.length() < p.indexOf(':') + 1) dest = p.substring(0, p.indexOf(':') + 1);
        return dest.isEmpty() ? "/" : dest;
    }

    static boolean isAncestor(String maybeParent, String child) {
        if (maybeParent.equals(child)) return false;
        String prefix = maybeParent.endsWith("/") || maybeParent.endsWith(":") ? maybeParent : maybeParent + "/";
        return child.startsWith(prefix);
    }

    void reloadRemotes() {
        worker.execute(() -> {
            LinkedHashMap<String, String> found = FileOps.remotes(this);
            handler.post(() -> {
                if (isDestroyed()) return;
                remotes = found;
                for (PaneView p : panes) p.refresh();
                if (mode == TABS) updateTabs();
            });
        });
    }

    // ============================================================================================
    // File actions

    void toClipboard(PaneView p, boolean move) {
        clipboard.clear();
        clipboard.addAll(p.selectedEntries());
        moving = move;
        p.clearSelection();
        updateBars();
    }

    void paste() {
        if (clipboard.isEmpty()) return;
        PaneView target = panes[active];
        if (target.loading || !target.error.isEmpty()) { message(null, "等目前資料夾讀取完成再貼上。"); return; }
        boolean remote = !FileOps.isLocal(target.path) || !FileOps.isLocal(clipboard.get(0).path);
        whenNetworkOk(remote, () -> {
            try {
                askNotifications();
                FileOps.enqueueAll(this, new ArrayList<>(clipboard), target.path, moving);
                clipboard.clear();
                updateBars();
                transferStarted();
            } catch (Exception e) { error(e); }
        });
    }

    void transferToOther(boolean move) {
        PaneView from = panes[active], to = panes[1 - active];
        ArrayList<FileOps.Entry> items = from.selectedEntries();
        if (items.isEmpty()) return;
        if (to.loading || !to.error.isEmpty()) { message(null, "等另一個窗格讀取完成再傳送。"); return; }
        whenNetworkOk(!FileOps.isLocal(from.path) || !FileOps.isLocal(to.path), () -> {
            try {
                askNotifications();
                FileOps.enqueueAll(this, items, to.path, move);
                from.clearSelection();
                transferStarted();
            } catch (Exception e) { error(e); }
        });
    }

    void transferStarted() {
        if (hidePill != null) handler.removeCallbacks(hidePill);
        pill.showBusy(FileOps.progress(), runningVerb());
        busyStartedAt = System.currentTimeMillis() - 2000;
        wasBusy = true;
        placeFloating();
    }

    void rename(PaneView p, FileOps.Entry e) {
        EditText edit = field(e.name, "新名稱", false);
        int dot = e.dir ? -1 : e.name.lastIndexOf('.');
        edit.setSelection(0, dot > 0 ? dot : e.name.length());
        Overlay.Dialog d = overlay.new Dialog("重新命名", null).view(edit);
        d.action("取消", Ui.ButtonStyle.TEXT, null);
        d.button("確定", Ui.ButtonStyle.FILLED, () -> {
            String name = edit.getText().toString().trim();
            if (name.equals(e.name)) { d.dismiss(); return; }
            String bad = invalidName(name);
            if (bad != null) { edit.setError(bad); return; }
            d.dismiss();
            String target = FileOps.join(p.path, name);
            command(() -> FileOps.stat(this, target), r -> {
                if (r.code == 0) { message(null, "這裡已經有「" + name + "」。"); return; }
                try {
                    TransferService.enqueue(this, e.path, target, e.dir, true, false);
                    p.clearSelection();
                    transferStarted();
                } catch (Exception ex) { error(ex); }
            });
        });
        d.show();
        Overlay.showKeyboard(edit);
    }

    void newFolder(PaneView p) {
        EditText edit = field("", "資料夾名稱", false);
        Overlay.Dialog d = overlay.new Dialog("新增資料夾", null).view(edit);
        d.action("取消", Ui.ButtonStyle.TEXT, null);
        d.button("建立", Ui.ButtonStyle.FILLED, () -> {
            String name = edit.getText().toString().trim();
            String bad = invalidName(name);
            if (bad != null) { edit.setError(bad); return; }
            d.dismiss();
            p.setLoading(true);
            command(() -> FileOps.mkdir(this, FileOps.join(p.path, name)), r -> {
                if (r.code == 0) load(p, p.path, 0);
                else { p.setLoading(false); message("無法建立資料夾", FileOps.friendlyError(r.err)); }
            });
        });
        d.show();
        Overlay.showKeyboard(edit);
    }

    void delete(PaneView p, ArrayList<FileOps.Entry> items) {
        if (items.isEmpty()) return;
        boolean anyDir = false;
        for (FileOps.Entry e : items) anyDir |= e.dir;
        String title = items.size() == 1 ? "刪除「" + items.get(0).name + "」？" : "刪除 " + items.size() + " 項？";
        String body = anyDir ? "資料夾裡的檔案也會一起刪除，刪除後無法復原。" : "刪除後無法復原。";
        overlay.new Dialog(title, body)
                .action("取消", Ui.ButtonStyle.TEXT, null)
                .action("刪除", Ui.ButtonStyle.DANGER, () -> {
                    p.clearSelection();
                    p.setLoading(true);
                    worker.execute(() -> {
                        int failed = 0;
                        String reason = "";
                        for (FileOps.Entry e : items) {
                            Rclone.Result r = FileOps.delete(this, e);
                            if (r.code != 0) { failed++; reason = FileOps.friendlyError(r.err); }
                        }
                        final int f = failed;
                        final String why = reason;
                        handler.post(() -> {
                            if (isDestroyed()) return;
                            load(p, p.path, 0);
                            if (f > 0) message(f == items.size() ? "沒有刪除" : "有 " + f + " 項沒有刪除", why);
                            else snack(items.size() == 1 ? "已刪除「" + items.get(0).name + "」" : "已刪除 " + items.size() + " 項");
                        });
                    });
                }).show();
    }

    void details(FileOps.Entry e) {
        LinearLayout c = Ui.column(this);
        TextView title = overlay.sheetTitle(e.name);
        title.setMaxLines(3);
        c.addView(title);
        detailRow(c, "種類", FileRow.kindName(e));
        if (!e.dir) detailRow(c, "大小", FileOps.size(e.size) + (e.size >= 1024 ? "（" + String.format(java.util.Locale.US, "%,d", e.size) + " 位元組）" : ""));
        detailRow(c, "修改時間", FileRow.fullDate(e.time));
        detailRow(c, "位置", displayPath(e.path));
        View pad = new View(this);
        c.addView(pad, new LinearLayout.LayoutParams(-1, Ui.dp(16)));
        overlay.new Sheet(scroll(c)).show();
    }

    private void detailRow(LinearLayout c, String label, String value) {
        LinearLayout r = Ui.column(this);
        r.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), Ui.dp(8));
        r.addView(Ui.text(this, label, 13, Ui.muted));
        TextView v = Ui.text(this, value, 15.5f, Ui.ink);
        v.setTextIsSelectable(true);
        v.setPadding(0, Ui.dp(2), 0, 0);
        r.addView(v);
        c.addView(r);
    }

    @Override public String displayPath(String path) {
        if (!FileOps.isLocal(path)) return path;
        Storage.Volume v = Storage.volumeFor(this, path);
        if (v == null) return path;
        return path.equals(v.root) ? v.label : v.label + "/" + path.substring(v.root.length() + 1);
    }

    // ============================================================================================
    // Sync (split view)

    static final int SYNC_FILL = 0, SYNC_MIRROR = 1;

    /** Opens the sync sheet. Choosing and previewing never touch files; only the final button queues work. */
    void openSync() {
        PaneView from = panes[active], to = panes[1 - active];
        if (from.loading || to.loading || !from.error.isEmpty() || !to.error.isEmpty()) { message(null, "等兩邊的資料夾都讀取完成再同步。"); return; }
        if (!from.selected.isEmpty() || !to.selected.isEmpty()) { message("同步會處理整個資料夾", "請先取消選取。只想同步一部分時，先打開那個子資料夾再同步。"); return; }
        try { TransferService.validateTransfer(from.path, to.path, true); } catch (Exception e) { error(e); return; }
        new SyncSheet(from, to).show();
    }

    private final class SyncSheet {
        PaneView src, dst;
        int mode = getPreferences(0).getInt("syncMode", SYNC_FILL);
        final LinearLayout content = Ui.column(MainActivity.this);
        final TextView title;
        final LinearLayout route = Ui.row(MainActivity.this), body = Ui.column(MainActivity.this), buttons = Ui.row(MainActivity.this);
        Overlay.Sheet sheet;
        int run;

        SyncSheet(PaneView from, PaneView to) {
            src = from;
            dst = to;
            title = overlay.sheetTitle("同步設定");
            content.addView(title);
            route.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(4));
            content.addView(route);
            content.addView(body);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            buttons.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(16));
            content.addView(buttons);
        }

        void show() {
            settings();
            sheet = overlay.new Sheet(scroll(content));
            sheet.show();
        }

        private void drawRoute(boolean allowFlip) {
            route.removeAllViews();
            route.addView(routeEnd(locationIcon(src.path), src.label(src.path), "來源"), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout flip = Ui.column(MainActivity.this);
            flip.setGravity(Gravity.CENTER);
            flip.setMinimumWidth(Ui.dp(56));
            flip.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
            ImageView arrow = Ui.icon(MainActivity.this, R.drawable.ic_arrow_forward, allowFlip ? Ui.primary : Ui.muted);
            flip.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            if (allowFlip) {
                TextView t = Ui.text(MainActivity.this, "對調", 12.5f, Ui.primary);
                t.setSingleLine(true);
                t.setPadding(0, Ui.dp(2), 0, 0);
                flip.addView(t, new LinearLayout.LayoutParams(-2, -2));
                flip.setBackground(Ui.press(0, 14));
                flip.setContentDescription("對調來源和目的地");
                flip.setOnClickListener(v -> {
                    try { TransferService.validateTransfer(dst.path, src.path, true); } catch (Exception e) { error(e); return; }
                    PaneView x = src; src = dst; dst = x;
                    settings();
                });
            }
            route.addView(flip, new LinearLayout.LayoutParams(-2, -2));
            route.addView(routeEnd(locationIcon(dst.path), dst.label(dst.path), "目的地"), new LinearLayout.LayoutParams(0, -2, 1));
        }

        /** Step 1: direction and method. */
        void settings() {
            run++;
            title.setText("同步設定");
            drawRoute(true);
            body.removeAllViews();
            // Rule for every sync method: the source is never changed; only the destination is.
            TextView rule = Ui.title(MainActivity.this, "「" + src.label(src.path) + "」不會被改動，只有「" + dst.label(dst.path) + "」會變。", 14, Ui.onPrimaryContainer);
            rule.setBackground(Ui.round(Ui.primaryContainer, 12));
            rule.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.leftMargin = rl.rightMargin = Ui.dp(16);
            rl.topMargin = Ui.dp(4);
            body.addView(rule, rl);
            TextView how = overlay.sectionLabel("方式");
            body.addView(how);
            body.addView(option(SYNC_FILL, "只補上缺少的檔案",
                    "只複製「" + dst.label(dst.path) + "」沒有的檔案。已有同名檔案就略過，原有的檔案不刪除、不覆蓋。"));
            body.addView(option(SYNC_MIRROR, "讓目的地和來源一模一樣",
                    "缺的補上；大小或修改時間不同的，換成來源的版本；「" + dst.label(dst.path) + "」多出的檔案會刪除。"));
            TextView note = Ui.text(MainActivity.this, "按「預覽變更」只會比對兩邊，不會動到任何檔案。", 13, Ui.muted);
            note.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), 0);
            body.addView(note);
            buttons.removeAllViews();
            buttons.addView(Ui.button(MainActivity.this, "取消", Ui.ButtonStyle.TEXT, v -> sheet.dismiss()));
            buttons.addView(Ui.button(MainActivity.this, "預覽變更", Ui.ButtonStyle.FILLED, v -> preview()), marginLeft(8));
        }

        private View option(final int value, String name, String detail) {
            boolean on = mode == value;
            LinearLayout card = Ui.row(MainActivity.this);
            card.setGravity(Gravity.TOP);
            card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.ripple),
                    Ui.stroke(on ? Ui.primaryContainer : Ui.surface, on ? Ui.primary : Ui.line, on ? 2 : 1, 16), Ui.round(0xffffffff, 16)));
            FrameLayout radio = new FrameLayout(MainActivity.this);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setStroke(Ui.dp(2), on ? Ui.primary : Ui.outline);
            radio.setBackground(ring);
            if (on) {
                View dot = new View(MainActivity.this);
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d.setColor(Ui.primary);
                dot.setBackground(d);
                radio.addView(dot, Ui.frame(Ui.dp(10), Ui.dp(10), Gravity.CENTER));
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20));
            rp.topMargin = Ui.dp(1);
            rp.rightMargin = Ui.dp(14);
            card.addView(radio, rp);
            LinearLayout texts = Ui.column(MainActivity.this);
            texts.addView(Ui.title(MainActivity.this, name, 15.5f, on ? Ui.onPrimaryContainer : Ui.ink));
            TextView t = Ui.text(MainActivity.this, detail, 13.5f, Ui.muted);
            t.setLineSpacing(0, 1.2f);
            t.setPadding(0, Ui.dp(4), 0, 0);
            texts.addView(t);
            card.addView(texts, Ui.weight(1));
            card.setContentDescription(name + "，" + detail + (on ? "，已選" : ""));
            card.setOnClickListener(v -> {
                if (mode == value) return;
                mode = value;
                getPreferences(0).edit().putInt("syncMode", mode).apply();
                settings();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = lp.rightMargin = Ui.dp(16);
            lp.bottomMargin = Ui.dp(10);
            card.setLayoutParams(lp);
            return card;
        }

        /** Step 2: dry run, list the changes, then offer the one button that queues the work. */
        void preview() {
            final int ticket = ++run;
            final String from = src.path, to = dst.path;
            final int chosen = mode;
            title.setText(chosen == SYNC_FILL ? "預覽：只補上缺少的檔案" : "預覽：讓目的地和來源一模一樣");
            drawRoute(false);
            body.removeAllViews();
            LinearLayout waiting = Ui.row(MainActivity.this);
            waiting.setPadding(Ui.dp(24), Ui.dp(20), Ui.dp(24), Ui.dp(28));
            ProgressBar spin = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleSmall);
            spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
            waiting.addView(spin, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
            TextView wt = Ui.text(MainActivity.this, "正在比對兩邊的檔案…", 15, Ui.ink);
            wt.setPadding(Ui.dp(12), 0, 0, 0);
            waiting.addView(wt);
            body.addView(waiting);
            buttons.removeAllViews();
            buttons.addView(Ui.button(MainActivity.this, "上一步", Ui.ButtonStyle.TEXT, v -> settings()));
            command(() -> chosen == SYNC_FILL ? FileOps.fillPreview(MainActivity.this, from, to) : FileOps.syncPreview(MainActivity.this, from, to), r -> {
                if (sheet.closing || ticket != run) return;
                body.removeAllViews();
                FileOps.SyncPlan plan = null;
                String problem = null;
                if (r.code != 0) problem = "無法比對：" + FileOps.friendlyError(r.err);
                else try { plan = FileOps.parseSyncPreview(r.err); } catch (JSONException ex) { problem = "無法讀取比對結果，沒有執行任何變更。"; }
                if (problem != null) {
                    TextView t = Ui.text(MainActivity.this, problem, 15, Ui.error);
                    t.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(12));
                    body.addView(t);
                    return;
                }
                if (plan.changes.isEmpty()) {
                    TextView t = Ui.title(MainActivity.this, chosen == SYNC_FILL ? "目的地已經有來源的所有檔案，不需要複製。" : "兩邊的內容已經一樣，不需要同步。", 15.5f, Ui.success);
                    t.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(16));
                    body.addView(t);
                    if (plan.timeOnly > 0) body.addView(timeOnlyNote(plan.timeOnly));
                    buttons.addView(Ui.button(MainActivity.this, "完成", Ui.ButtonStyle.TONAL, v -> sheet.dismiss()), marginLeft(8));
                    return;
                }
                LinearLayout chips = Ui.row(MainActivity.this);
                chips.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(8));
                String added = chosen == SYNC_FILL ? "新增 " : "新增或更新 ";
                if (plan.updates > 0) chips.addView(chip(added + plan.updates, Ui.primaryContainer, Ui.onPrimaryContainer));
                if (plan.deletes > 0) chips.addView(chip("刪除 " + plan.deletes, Ui.errorContainer, Ui.onErrorContainer));
                if (plan.folders > 0) chips.addView(chip("建立資料夾 " + plan.folders, Ui.surfaceHigh, Ui.ink));
                body.addView(chips);
                LinearLayout listBox = Ui.column(MainActivity.this);
                for (FileOps.SyncChange ch : plan.changes) listBox.addView(changeRow(ch, chosen == SYNC_FILL));
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(listBox);
                body.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(Ui.dp(300), Ui.dp(44) * plan.changes.size() + Ui.dp(8))));
                if (plan.timeOnly > 0) body.addView(timeOnlyNote(plan.timeOnly));
                TextView note = Ui.text(MainActivity.this, "比對之後檔案若有變動，實際結果會跟著不同。", 12.5f, Ui.muted);
                note.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), 0);
                body.addView(note);
                String go;
                Ui.ButtonStyle style = Ui.ButtonStyle.FILLED;
                if (chosen == SYNC_FILL) go = plan.updates > 0 ? "開始複製 " + plan.updates + " 項" : "開始複製";
                else if (plan.deletes > 0) { go = "同步並刪除 " + plan.deletes + " 項"; style = Ui.ButtonStyle.DANGER; }
                else go = "開始同步";
                buttons.addView(Ui.button(MainActivity.this, go, style, v -> {
                    sheet.dismiss();
                    whenNetworkOk(!FileOps.isLocal(from) || !FileOps.isLocal(to), () -> {
                        try {
                            askNotifications();
                            TransferService.enqueue(MainActivity.this, from, to, true, false, chosen == SYNC_MIRROR);
                            transferStarted();
                        } catch (Exception ex) { error(ex); }
                    });
                }), marginLeft(8));
            });
        }
    }

    private TextView timeOnlyNote(int count) {
        TextView t = Ui.text(this, "另有 " + count + " 項只調整修改時間，不改內容。", 13.5f, Ui.muted);
        t.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), 0);
        return t;
    }

    private View routeEnd(int icon, String name, String role) {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Ui.round(Ui.primaryContainer, 12));
        tile.addView(Ui.icon(this, icon, Ui.primary), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        box.addView(tile, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        TextView n = Ui.oneLine(Ui.title(this, name, 15, Ui.ink), TextUtils.TruncateAt.MIDDLE);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, Ui.dp(6), 0, 0);
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        TextView r = Ui.text(this, role, 12.5f, Ui.muted);
        r.setGravity(Gravity.CENTER);
        box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private TextView chip(String text, int bg, int fg) {
        TextView t = Ui.title(this, text, 13, fg);
        t.setBackground(Ui.round(bg, 8));
        t.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(4);
        lp.rightMargin = Ui.dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private View changeRow(FileOps.SyncChange ch, boolean fillOnly) {
        LinearLayout r = Ui.row(this);
        r.setMinimumHeight(Ui.dp(44));
        r.setPadding(Ui.dp(24), 0, Ui.dp(24), 0);
        int icon, tint;
        String label;
        switch (ch.kind) {
            case FileOps.SyncChange.UPDATE: icon = R.drawable.ic_add; tint = Ui.primary; label = fillOnly ? "新增" : "新增或更新"; break;
            case FileOps.SyncChange.DELETE: icon = R.drawable.ic_delete; tint = Ui.error; label = "刪除"; break;
            case FileOps.SyncChange.FOLDER: icon = R.drawable.ic_new_folder; tint = Ui.muted; label = "建立資料夾"; break;
            default: icon = R.drawable.ic_sync; tint = Ui.muted; label = "變更（" + ch.action + "）"; break;
        }
        ImageView i = Ui.icon(this, icon, tint);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20));
        ip.rightMargin = Ui.dp(14);
        r.addView(i, ip);
        r.addView(Ui.oneLine(Ui.text(this, ch.object, 14.5f, ch.kind == FileOps.SyncChange.DELETE ? Ui.error : Ui.ink), TextUtils.TruncateAt.MIDDLE), Ui.weight(1));
        r.setContentDescription(label + "：" + ch.object);
        return r;
    }

    private LinearLayout.LayoutParams marginLeft(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(dp);
        return lp;
    }

    // ============================================================================================
    // Locations and connections

    @Override public void showLocations(PaneView p, View anchor) {
        Storage.invalidate();
        LinearLayout c = Ui.column(this);
        final Overlay.Sheet[] holder = new Overlay.Sheet[1];
        c.addView(overlay.sheetTitle("位置"));

        c.addView(overlay.sectionLabel("儲存空間"));
        List<Storage.Volume> vols = Storage.volumes(this);
        for (Storage.Volume v : vols) c.addView(volumeRow(p, v, holder));
        if (vols.size() == 1) {
            TextView none = Ui.text(this, "沒有偵測到記憶卡或 USB 隨身碟。插入後會出現在這裡。", 13.5f, Ui.muted);
            none.setPadding(Ui.dp(24), Ui.dp(2), Ui.dp(24), Ui.dp(8));
            c.addView(none);
        }

        List<String> marks = Settings.bookmarks(this);
        if (!marks.isEmpty()) {
            c.addView(overlay.sectionLabel("書籤"));
            for (final String path : marks) {
                ImageView more = Ui.iconButton(this, R.drawable.ic_more, "「" + p.label(path) + "」的選項", Ui.muted, null);
                more.setOnClickListener(v -> overlay.new Menu(v).item("移除書籤", false, () -> {
                    Settings.toggleBookmark(this, path);
                    holder[0].dismiss();
                    snack("已移除書籤「" + p.label(path) + "」");
                }).show());
                String where = displayPath(parent(path));
                c.addView(locationRow(p, R.drawable.ic_bookmark, p.label(path), where, path, holder, more));
            }
        }

        c.addView(overlay.sectionLabel("連線"));
        for (Map.Entry<String, String> r : remotes.entrySet()) {
            final String remote = r.getKey();
            ImageView more = Ui.iconButton(this, R.drawable.ic_more, "「" + rootLabel(remote) + "」的選項", Ui.muted, null);
            more.setOnClickListener(v -> overlay.new Menu(v).item("移除連線", false, () -> removeRemote(remote, holder[0])).show());
            c.addView(locationRow(p, FileOps.typeIcon(r.getValue()), rootLabel(remote), r.getValue().isEmpty() ? null : FileOps.typeName(r.getValue()), remote, holder, more));
        }
        c.addView(overlay.sheetRow(R.drawable.ic_add, Ui.surfaceHigh, Ui.ink, "新增連線", null, null, v -> { holder[0].dismiss(); addRemote(); }));
        View pad = new View(this);
        c.addView(pad, new LinearLayout.LayoutParams(-1, Ui.dp(12)));
        holder[0] = overlay.new Sheet(scroll(c)).show();
    }

    /** Storage row with free space and a usage bar. */
    private View volumeRow(PaneView p, Storage.Volume v, Overlay.Sheet[] holder) {
        String space = v.total > 0 ? "可用 " + FileOps.size(v.free) + "，共 " + FileOps.size(v.total) : null;
        boolean here = localRoot(p.path).equals(v.root);
        LinearLayout row = overlay.sheetRow(Storage.icon(v.kind), here ? Ui.primary : Ui.primaryContainer, here ? Ui.onPrimary : Ui.primary,
                v.label, space, null, x -> { holder[0].dismiss(); if (!p.path.equals(v.root)) load(p, v.root, isAncestor(v.root, p.path) ? -1 : 1); });
        if (v.total > 0) {
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) (1000 * (v.total - v.free) / Math.max(1, v.total)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.primary, .18f)));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(4));
            bp.topMargin = Ui.dp(6);
            texts.addView(bar, bp);
        }
        if (here) {
            row.setContentDescription(v.label + "，目前位置" + (space == null ? "" : "，" + space));
            ((TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(0)).setTypeface(Ui.medium);
        }
        return row;
    }

    private View locationRow(PaneView p, int icon, String title, String subtitle, String target, Overlay.Sheet[] holder, View trailing) {
        // Connections mark the one the window is inside; bookmarks only mark an exact match, so a
        // folder and its bookmarked parent are never highlighted at the same time.
        boolean current = target.endsWith(":") ? p.path.equals(target) || isAncestor(target, p.path) : p.path.equals(target);
        LinearLayout row = overlay.sheetRow(icon, current ? Ui.primary : Ui.primaryContainer, current ? Ui.onPrimary : Ui.primary, title, subtitle, trailing,
                v -> { holder[0].dismiss(); if (!p.path.equals(target)) load(p, target, isAncestor(target, p.path) ? -1 : 1); });
        if (current) {
            row.setContentDescription(title + "，目前位置");
            ((TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(0)).setTypeface(Ui.medium);
        }
        return row;
    }

    void removeRemote(String remote, Overlay.Sheet sheet) {
        if (TransferService.busy()) { message(null, "等傳輸完成再移除連線。"); return; }
        String name = rootLabel(remote);
        overlay.new Dialog("移除「" + name + "」？", "只會移除這支手機上的連線設定，NAS 或雲端上的檔案不受影響。要改帳號或密碼，可以移除後重新新增。")
                .action("取消", Ui.ButtonStyle.TEXT, null)
                .action("移除連線", Ui.ButtonStyle.DANGER, () -> {
                    if (sheet != null) sheet.dismiss();
                    command(() -> FileOps.deleteRemote(this, remote), r -> {
                        if (r.code != 0) { message(null, "無法移除這個連線。"); return; }
                        clipboard.clear();
                        for (PaneView p : panes) {
                            if (p.path.startsWith(remote) || p.failed.startsWith(remote)) {
                                ++p.generation;
                                p.entries = new ArrayList<>();
                                p.selected.clear();
                                load(p, FileOps.PHONE, -1);
                            }
                        }
                        updateBars();
                        reloadRemotes();
                        snack("已移除「" + name + "」");
                    });
                }).show();
    }

    void addRemote() {
        if (TransferService.busy()) { message(null, "等傳輸完成再新增連線。"); return; }
        LinearLayout c = Ui.column(this);
        final Overlay.Sheet[] holder = new Overlay.Sheet[1];
        c.addView(overlay.sheetTitle("新增連線"));
        c.addView(overlay.sheetRow(R.drawable.ic_server, Ui.primaryContainer, Ui.primary, "SMB", "多數 NAS 與 Windows 共用資料夾", null, v -> { holder[0].dismiss(); remoteForm(0); }));
        c.addView(overlay.sheetRow(R.drawable.ic_server, Ui.primaryContainer, Ui.primary, "SFTP", "可用 SSH 登入的電腦或伺服器", null, v -> { holder[0].dismiss(); remoteForm(1); }));
        c.addView(overlay.sheetRow(R.drawable.ic_cloud, Ui.primaryContainer, Ui.primary, "WebDAV", "用網址連線，例如 Nextcloud", null, v -> { holder[0].dismiss(); remoteForm(2); }));
        c.addView(overlay.sheetRow(R.drawable.ic_download, Ui.surfaceHigh, Ui.ink, "匯入 rclone 設定檔", "Google 雲端硬碟、OneDrive 等服務用這個加入；會取代目前所有連線", null, v -> { holder[0].dismiss(); importConfig(); }));
        View pad = new View(this);
        c.addView(pad, new LinearLayout.LayoutParams(-1, Ui.dp(12)));
        holder[0] = overlay.new Sheet(scroll(c)).show();
    }

    private static final class Field {
        final LinearLayout box;
        final EditText edit;
        final TextView error;
        Field(LinearLayout box, EditText edit, TextView error) { this.box = box; this.edit = edit; this.error = error; }
        String value() { return edit.getText().toString(); }
        void fail(String message) {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
            edit.requestFocus();
        }
        void clear() { error.setVisibility(View.GONE); }
    }

    private Field formField(LinearLayout form, String label, String hint, int inputType) {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(24), Ui.dp(6), Ui.dp(24), Ui.dp(6));
        TextView l = Ui.title(this, label, 13.5f, Ui.muted);
        l.setPadding(Ui.dp(2), 0, 0, Ui.dp(6));
        box.addView(l);
        EditText e = field("", hint, false);
        e.setInputType(inputType);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        TextView err = Ui.text(this, "", 12.5f, Ui.error);
        err.setPadding(Ui.dp(2), Ui.dp(4), 0, 0);
        err.setVisibility(View.GONE);
        box.addView(err);
        form.addView(box);
        Field f = new Field(box, e, err);
        e.setOnFocusChangeListener((v, has) -> { if (has) f.clear(); });
        return f;
    }

    void remoteForm(final int type) {
        String[] titles = {"新增 SMB 連線", "新增 SFTP 連線", "新增 WebDAV 連線"};
        LinearLayout form = Ui.column(this);
        form.addView(overlay.sheetTitle(titles[type]));
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        Field name = formField(form, "名稱", "例如：家裡 NAS", text);
        Field host = formField(form, type == 2 ? "網址" : "主機", type == 2 ? "https://nas.example/dav" : "IP 位址或主機名稱", type == 2 ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI : text);
        Field user = formField(form, "帳號", "", text);
        Field pass = formField(form, "密碼", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Field extra = type == 0 ? formField(form, "網域（選填）", "通常不用填", text)
                : type == 1 ? formField(form, "連接埠（選填）", "預設 22", InputType.TYPE_CLASS_NUMBER)
                : formField(form, "服務類型（選填）", "一般不用填；Nextcloud 填 nextcloud", text);
        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(16));
        form.addView(buttons);
        final Overlay.Sheet sheet = overlay.new Sheet(scroll(form));
        sheet.cancelable = true;
        buttons.addView(Ui.button(this, "取消", Ui.ButtonStyle.TEXT, v -> sheet.dismiss()));
        final TextView save = Ui.button(this, "儲存", Ui.ButtonStyle.FILLED, null);
        save.setOnClickListener(v -> {
            String nm = name.value().trim(), h = host.value().trim(), u = user.value(), pw = pass.value(), x = extra.value().trim();
            String bad = invalidName(nm);
            if (bad != null) { name.fail(bad); return; }
            if (remotes.containsKey(nm + ":")) { name.fail("這個名稱已經用過了。"); return; }
            if (h.isEmpty()) { host.fail(type == 2 ? "請填寫網址。" : "請填寫主機。"); return; }
            if (type == 2 && !h.startsWith("https://") && !h.startsWith("http://")) { host.fail("網址要以 https:// 或 http:// 開頭。"); return; }
            if (type == 1 && !x.isEmpty()) {
                try { int port = Integer.parseInt(x); if (port < 1 || port > 65535) throw new NumberFormatException(); }
                catch (NumberFormatException e) { extra.fail("連接埠要介於 1 到 65535。"); return; }
            }
            if (TransferService.busy()) { message(null, "等傳輸完成再新增連線。"); return; }
            save.setEnabled(false);
            save.setAlpha(.5f);
            save.setText("儲存中…");
            command(() -> FileOps.createRemote(this, type, nm, h, u, pw, x), r -> {
                save.setEnabled(true);
                save.setAlpha(1f);
                save.setText("儲存");
                if (r.code != 0) { message("無法儲存連線", "rclone 沒有接受這組設定。請確認主機或網址的格式，再試一次。"); return; }
                sheet.dismiss();
                reloadRemotes();
                load(panes[active], nm + ":", 1);
            });
        });
        buttons.addView(save, marginLeft(8));
        sheet.show();
        Overlay.showKeyboard(name.edit);
    }

    void importConfig() {
        if (TransferService.busy()) { message(null, "傳輸進行中，等完成後再匯入設定檔。"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, 41); } catch (Exception e) { message(null, "手機裡沒有能選擇檔案的 App。"); }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == 42) { for (PaneView p : panes) if (FileOps.isLocal(p.path)) load(p, p.path, 0); return; }
        if (req != 41 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (remotes.isEmpty()) { runImport(uri); return; }
        overlay.new Dialog("匯入這份設定檔？", "目前的 " + remotes.size() + " 個連線會被設定檔裡的連線取代。要保留目前的連線，請先到設定匯出備份。")
                .action("取消", Ui.ButtonStyle.TEXT, null)
                .action("取代並匯入", Ui.ButtonStyle.FILLED, () -> runImport(uri)).show();
    }

    private void runImport(final Uri uri) {
        if (TransferService.busy()) { message(null, "傳輸進行中，等完成後再匯入設定檔。"); return; }
        final Overlay.Dialog progress = busyDialog("正在讀取設定…");
        worker.execute(() -> {
            try {
                int count = FileOps.importConfig(this, uri);
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    configStamp = System.currentTimeMillis();
                    Settings.prefs(this).edit().putLong(Settings.CONFIG_STAMP, configStamp).apply();
                    afterConfigReplaced();
                    snack("已匯入 " + count + " 個連線");
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    progress.dismiss();
                    error(e);
                });
            }
        });
    }

    /** After the connection list is replaced, drop anything that pointed at the old connections. */
    void afterConfigReplaced() {
        clipboard.clear();
        for (PaneView p : panes) {
            ++p.generation;
            p.setLoading(false);
            p.error = "";
            p.failed = "";
            p.selected.clear();
            if (!FileOps.isLocal(p.path)) { p.path = FileOps.PHONE; p.entries = new ArrayList<>(); }
            load(p, p.path, 0);
        }
        reloadRemotes();
        updateBars();
    }

    // ============================================================================================
    // Menu

    @Override public void showMenu(PaneView p, View anchor) {
        final Overlay.Menu m = overlay.new Menu(anchor);
        m.add(modePicker(m));
        m.divider();
        m.item("新增資料夾", false, () -> newFolder(p));
        m.item("輸入路徑", false, () -> enterPath(p));
        m.item("重新整理", false, () -> load(p, p.path, 0));
        final boolean marked = Settings.isBookmarked(this, p.path);
        m.item(marked ? "移除書籤" : "加入書籤", false, () -> {
            Settings.toggleBookmark(this, p.path);
            snack(marked ? "已移除書籤「" + p.label(p.path) + "」" : "已加入書籤「" + p.label(p.path) + "」，點左上角的位置圖示就能前往");
        });
        m.divider();
        m.add(sortPicker());
        m.add(hiddenToggle());
        m.divider();
        m.item("傳輸紀錄", false, this::showTransfers);
        m.item("設定", false, () -> startActivity(new Intent(this, SettingsActivity.class)));
        m.show();
    }

    private View modePicker(Overlay.Menu menu) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        int[] icons = {R.drawable.ic_mode_single, R.drawable.ic_mode_tabs, isLandscape() ? R.drawable.ic_mode_split_h : R.drawable.ic_mode_split};
        String[] names = {"單頁", "分頁", "雙窗格"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            boolean on = mode == i;
            LinearLayout b = Ui.column(this);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), Ui.dp(8));
            b.setBackground(Ui.press(on ? Ui.primaryContainer : 0, 14));
            b.addView(Ui.icon(this, icons[i], on ? Ui.onPrimaryContainer : Ui.muted), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            TextView t = Ui.text(this, names[i], 12.5f, on ? Ui.onPrimaryContainer : Ui.muted);
            if (on) t.setTypeface(Ui.medium);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(4), 0, 0);
            b.addView(t, new LinearLayout.LayoutParams(-1, -2));
            b.setContentDescription(names[i] + (on ? "，目前的瀏覽方式" : ""));
            b.setOnClickListener(v -> { menu.dismiss(); setMode(k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(2);
            row.addView(b, lp);
        }
        return row;
    }

    private View sortPicker() {
        final LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        fillSort(row);
        return row;
    }

    private void fillSort(final LinearLayout row) {
        row.removeAllViews();
        String[] names = {"名稱", "日期", "大小"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            boolean on = sortKey == i;
            LinearLayout chip = Ui.row(this);
            chip.setGravity(Gravity.CENTER);
            chip.setMinimumHeight(Ui.dp(36));
            chip.setBackground(on ? Ui.press(Ui.primaryContainer, 10) : Ui.press(0, 10));
            if (!on) chip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.ripple), Ui.stroke(0, Ui.line, 1, 10), Ui.round(0xffffffff, 10)));
            TextView t = Ui.text(this, names[i], 14, on ? Ui.onPrimaryContainer : Ui.muted);
            if (on) t.setTypeface(Ui.medium);
            chip.addView(t);
            if (on) {
                ImageView arrow = Ui.icon(this, descending ? R.drawable.ic_arrow_down : R.drawable.ic_arrow_up, Ui.onPrimaryContainer);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16));
                ap.leftMargin = Ui.dp(4);
                chip.addView(arrow, ap);
            }
            String[][] orders = {{"由 A 到 Z", "由 Z 到 A"}, {"由舊到新", "由新到舊"}, {"由小到大", "由大到小"}};
            chip.setContentDescription("依" + names[i] + "排序" + (on ? "，目前" + orders[i][descending ? 1 : 0] + "，再選一次反轉" : ""));
            chip.setOnClickListener(v -> {
                if (sortKey == k) descending = !descending;
                else { sortKey = k; descending = k != SORT_NAME; }
                fillSort(row);
                for (PaneView p : panes) p.refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(4);
            row.addView(chip, lp);
        }
    }

    private View hiddenToggle() {
        final LinearLayout r = Ui.row(this);
        r.setMinimumHeight(Ui.dp(48));
        r.setPadding(Ui.dp(16), 0, Ui.dp(14), 0);
        r.setBackground(Ui.press(0, 0));
        r.addView(Ui.text(this, "顯示隱藏檔", 15.5f, Ui.ink), Ui.weight(1));
        final ImageView check = Ui.icon(this, R.drawable.ic_check, Ui.onPrimary);
        check.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        final Runnable paint = () -> {
            check.setBackground(hidden ? Ui.round(Ui.primary, 5) : Ui.stroke(0, Ui.muted, 2, 5));
            check.setImageAlpha(hidden ? 255 : 0);
        };
        paint.run();
        r.addView(check, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        r.setContentDescription(hidden ? "顯示隱藏檔，已開啟" : "顯示隱藏檔，已關閉");
        r.setOnClickListener(v -> {
            hidden = !hidden;
            Settings.prefs(this).edit().putBoolean(Settings.HIDDEN, hidden).apply();
            paint.run();
            r.setContentDescription(hidden ? "顯示隱藏檔，已開啟" : "顯示隱藏檔，已關閉");
            for (PaneView p : panes) p.refresh();
        });
        return r;
    }

    void enterPath(PaneView p) {
        EditText edit = field(p.path, "/storage/emulated/0/Download", false);
        edit.selectAll();
        Overlay.Dialog d = overlay.new Dialog("前往路徑", "手機路徑從 /storage/emulated/0 開始；連線裡的資料夾寫成「連線名稱:資料夾」，例如 nas:相片。").view(edit);
        d.action("取消", Ui.ButtonStyle.TEXT, null);
        d.button("前往", Ui.ButtonStyle.FILLED, () -> {
            String s = edit.getText().toString().trim();
            if (s.isEmpty()) { edit.setError("請輸入路徑。"); return; }
            // "/sdcard/..." and other aliases point at the same storage; show it under 手機.
            if (FileOps.isLocal(s)) {
                try {
                    String real = new java.io.File(s).getCanonicalPath();
                    if (real.equals(FileOps.PHONE) || real.startsWith(FileOps.PHONE + "/")) s = real;
                } catch (java.io.IOException ignored) {}
            }
            d.dismiss();
            load(p, s, isAncestor(s, p.path) ? -1 : 1);
        });
        d.show();
        Overlay.showKeyboard(edit);
    }

    // ============================================================================================
    // Transfers

    void showTransfers() {
        final Overlay.Sheet[] holder = new Overlay.Sheet[1];
        transferPanel = new TransferUi.Panel(this, () ->
                overlay.new Dialog("取消所有傳輸？", "進行中和排隊中的傳輸都會取消，已經傳完的檔案會保留。")
                        .action("繼續傳輸", Ui.ButtonStyle.TEXT, null)
                        .action("取消全部", Ui.ButtonStyle.DANGER, TransferService::cancel).show());
        transferPanel.update(this);
        holder[0] = overlay.new Sheet(transferPanel);
        holder[0].onDismiss = () -> transferPanel = null;
        holder[0].show();
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            FileOps.Progress pr = FileOps.progress();
            String status = TransferService.status();
            boolean changed = !status.equals(lastStatus);
            lastStatus = status;
            if (pr.busy) {
                if (!wasBusy) busyStartedAt = System.currentTimeMillis() - 2000;
                if (hidePill != null) handler.removeCallbacks(hidePill);
                pill.showBusy(pr, runningVerb());
                placeFloating();
            } else if (wasBusy) {
                finishPill();
            }
            if ((wasBusy || changed) && !pr.busy) for (PaneView p : panes) if (!p.loading) load(p, p.path, 0);
            wasBusy = pr.busy;
            if (transferPanel != null) transferPanel.update(MainActivity.this);
            handler.postDelayed(this, pr.busy || transferPanel != null ? 500 : 1000);
        }
    };

    private String runningVerb() {
        JSONArray rows = TransferService.history(this);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && "進行中".equals(r.optString("state"))) return r.optString("operation");
        }
        return "傳輸";
    }

    /** After a run of transfers ends, briefly report how it went. */
    private void finishPill() {
        JSONArray rows = TransferService.history(this);
        int failed = 0, cancelled = 0, done = 0, nothingNew = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || r.optLong("time") < busyStartedAt) continue;
            String s = r.optString("state");
            if (s.startsWith("失敗") || s.startsWith("無法")) failed++;
            else if (s.startsWith("已取消") || s.startsWith("已中斷")) cancelled++;
            else if (s.contains("沒有新增")) nothingNew++;
            else if (s.startsWith("完成")) done++;
        }
        if (failed > 0) pill.showResult(R.drawable.ic_error, Ui.error, failed + " 項失敗");
        else if (cancelled > 0 && done + nothingNew == 0) pill.showResult(R.drawable.ic_cancel, Ui.onPrimaryContainer, "已取消");
        else if (cancelled > 0) pill.showResult(R.drawable.ic_check_circle, Ui.success, "完成 " + (done + nothingNew) + " 項，取消 " + cancelled + " 項");
        else if (done == 0 && nothingNew > 0) pill.showResult(R.drawable.ic_info, Ui.onPrimaryContainer, "沒有新增檔案");
        else pill.showResult(R.drawable.ic_check_circle, Ui.success, "傳輸完成");
        placeFloating();
        hidePill = () -> { pill.disappear(); };
        handler.postDelayed(hidePill, failed > 0 ? 8000 : 4000);
    }

    // ============================================================================================
    // Helpers

    interface Work { Rclone.Result run(); }
    interface Done { void done(Rclone.Result r); }

    void command(Work work, Done done) {
        worker.execute(() -> {
            Rclone.Result r;
            try { r = work.run(); } catch (Exception e) { r = new Rclone.Result(1, "", e.getMessage() == null ? "" : e.getMessage()); }
            final Rclone.Result result = r;
            handler.post(() -> { if (!isDestroyed()) done.done(result); });
        });
    }

    EditText field(String value, String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(Ui.ink);
        e.setHintTextColor(Ui.alpha(Ui.muted, .7f));
        e.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.stroke(Ui.surface, Ui.primary, 2, 12));
        bg.addState(new int[]{}, Ui.stroke(Ui.surface, Ui.outline, 1, 12));
        e.setBackground(bg);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        s.addView(content);
        return s;
    }

    String invalidName(String name) {
        if (name.isEmpty()) return "名稱不能空白。";
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains(":") || name.contains("\n")) return "名稱不能是 . 或 ..，也不能包含 / 或 :。";
        return null;
    }

    Overlay.Dialog busyDialog(String text) {
        LinearLayout row = Ui.row(this);
        ProgressBar spin = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
        row.addView(spin, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView t = Ui.text(this, text, 15.5f, Ui.ink);
        t.setPadding(Ui.dp(16), 0, 0, 0);
        row.addView(t);
        Overlay.Dialog d = overlay.new Dialog(null, null).view(row);
        d.cancelable = false;
        d.buttons.setVisibility(View.GONE);
        return d.show();
    }

    void message(String title, String body) {
        overlay.new Dialog(title, body).action("知道了", Ui.ButtonStyle.TONAL, null).show();
    }

    void error(Throwable e) {
        message(null, e.getMessage() == null ? "操作沒有完成，請再試一次。" : e.getMessage());
    }

    /** When the setting is on and the phone is on mobile data, confirm before a network transfer. */
    void whenNetworkOk(boolean usesNetwork, Runnable go) {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean cellular = caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
        if (!usesNetwork || !cellular || !Settings.askMobileData(this)) { go.run(); return; }
        overlay.new Dialog("要用行動數據傳輸嗎？", "手機目前使用行動數據，大檔案可能用掉不少流量。")
                .action("取消", Ui.ButtonStyle.TEXT, null)
                .action("仍要傳輸", Ui.ButtonStyle.FILLED, go).show();
    }

    // ============================================================================================
    // Back

    @Override public void onBackPressed() {
        if (overlay.back()) return;
        PaneView p = panes[active];
        if (p.searching) { p.endSearch(); return; }
        if (!p.selected.isEmpty()) { p.clearSelection(); return; }
        if (!p.error.isEmpty() && p.loadedOnce) { p.error = ""; p.failed = ""; p.refresh(); return; }
        if (!isRoot(p.path)) { load(p, parent(p.path), -1); return; }
        if (Settings.backExit(this) == Settings.BACK_EXIT_TWICE) {
            long now = SystemClock.elapsedRealtime();
            if (now - backPressedAt > 2000) { backPressedAt = now; snack("再按一次返回鍵離開"); return; }
        }
        super.onBackPressed();
    }

    /** Kept for anything that still reads the 0.1.0 preview text. */
    public static String formatSyncPreview(String raw) throws JSONException {
        FileOps.SyncPlan plan = FileOps.parseSyncPreview(raw);
        if (plan.changes.isEmpty()) return "沒有需要變更的檔案。";
        StringBuilder result = new StringBuilder();
        for (FileOps.SyncChange c : plan.changes) {
            String label = c.kind == FileOps.SyncChange.UPDATE ? "新增或更新" : c.kind == FileOps.SyncChange.DELETE ? "刪除" : c.kind == FileOps.SyncChange.FOLDER ? "建立資料夾" : "變更（" + c.action + "）";
            result.append(label).append("  ").append(c.object).append("\n");
        }
        return "新增或更新 " + plan.updates + " 項 · 刪除 " + plan.deletes + " 項" + (plan.folders > 0 ? " · 建立 " + plan.folders + " 個資料夾" : "") + "\n\n" + result;
    }
}

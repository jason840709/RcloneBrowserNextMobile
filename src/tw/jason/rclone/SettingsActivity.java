package tw.jason.rclone;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settings screen. Values live in {@link Settings}; the file screen picks them up when it resumes. */
public class SettingsActivity extends Activity {
    private static final int IMPORT = 51, EXPORT = 52;
    private static final String PROJECT_PAGE = "https://github.com/jason840709/RcloneBrowserNextMobile";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout header, list;
    private ScrollView scroll;
    private Overlay overlay;
    private TextView snackbar;
    private Runnable hideSnack;
    private int insetTop, insetBottom;

    // Rows whose text changes after the screen is built
    private TextView fileAccessState, notifyState, cacheState, engineState;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MainActivity.applyNightOverride(this, base);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        MainActivity.setupWindow(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.surface);
        LinearLayout page = Ui.column(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        header = Ui.row(this);
        header.setPadding(Ui.dp(4), 0, Ui.dp(16), 0);
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "返回", Ui.ink, v -> finish()));
        TextView title = Ui.title(this, "設定", 20, Ui.ink);
        title.setPadding(Ui.dp(8), 0, 0, 0);
        header.addView(title, Ui.weight(1));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = Ui.column(this);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        snackbar = Ui.text(this, "", 14.5f, Ui.inverseInk);
        snackbar.setBackground(Ui.round(Ui.inverseSurface, 12));
        snackbar.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        snackbar.setElevation(Ui.dp(6));
        snackbar.setVisibility(View.GONE);
        snackbar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.leftMargin = sp.rightMargin = Ui.dp(12);
        root.addView(snackbar, sp);

        overlay = new Overlay(this, root, page);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = sys.top; bottom = sys.bottom; left = sys.left; right = sys.right;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getStableInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            insetTop = top; insetBottom = bottom;
            header.setPadding(Ui.dp(4) + left, top, Ui.dp(16) + right, 0);
            header.setMinimumHeight(top + Ui.dp(56));
            scroll.setPadding(left, 0, right, bottom + Ui.dp(16));
            snackbar.setTranslationY(-(bottom + Ui.dp(12)));
            overlay.insets(top, bottom, ime, left, right);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStates();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (overlay.back()) return;
        super.onBackPressed();
    }

    // ============================================================================================

    private void build() {
        list.removeAllViews();

        section("外觀");
        accentRow();
        segmentedRow("深淺色", new String[]{"依照系統", "淺色", "深色"}, Settings.night(this), i -> {
            Settings.prefs(this).edit().putInt(Settings.NIGHT, i).apply();
            recreate();
        });
        switchRow("App 圖示跟著配色", "主畫面圖示的顏色跟著配色。有些手機會因此移除主畫面上的捷徑，要從 App 清單重新拖出來。",
                Settings.iconFollows(this), on -> {
                    Settings.prefs(this).edit().putBoolean(Settings.ICON_FOLLOWS, on).apply();
                    LauncherIcon.apply(this);
                });
        switchRow("緊湊清單", "每一列比較矮，一頁可以看到更多檔案。", Settings.dense(this),
                on -> Settings.prefs(this).edit().putBoolean(Settings.DENSE, on).apply());
        switchRow("顯示縮圖", "只用於本機與記憶卡裡的圖片和影片。", Settings.thumbnails(this),
                on -> Settings.prefs(this).edit().putBoolean(Settings.THUMBNAILS, on).apply());

        section("瀏覽");
        switchRow("顯示隱藏檔", "名稱以句點開頭的檔案和資料夾。", Settings.hidden(this),
                on -> Settings.prefs(this).edit().putBoolean(Settings.HIDDEN, on).apply());
        choiceRow("開啟 App 時", new String[]{"回到上次的位置", "從本機開始"}, Settings.startAt(this),
                i -> Settings.prefs(this).edit().putInt(Settings.START_AT, i).apply());
        choiceRow("在最上層資料夾按返回鍵", new String[]{"直接離開", "再按一次才離開"}, Settings.backExit(this),
                i -> Settings.prefs(this).edit().putInt(Settings.BACK_EXIT, i).apply());

        section("傳輸");
        final int[] counts = {1, 2, 4, 8};
        choiceRow("同時傳輸的檔案數", new String[]{"1 個", "2 個", "4 個（預設）", "8 個"}, indexOf(counts, Settings.transfers(this), 2),
                i -> Settings.prefs(this).edit().putInt(Settings.TRANSFERS, counts[i]).apply(),
                "數量越多，大量小檔案傳得越快；有些伺服器會限制同時連線數。");
        final int[] limits = {0, 1, 5, 10, 50};
        choiceRow("傳輸速度上限", new String[]{"不限制（預設）", "每秒 1 MB", "每秒 5 MB", "每秒 10 MB", "每秒 50 MB"}, indexOf(limits, Settings.bandwidthMB(this), 0),
                i -> Settings.prefs(this).edit().putInt(Settings.BWLIMIT, limits[i]).apply(),
                "限制傳輸的速度，避免佔滿網路。");
        switchRow("網路較慢時延長等待", "連線最多等 60 秒、傳輸中沒有資料最多等 5 分鐘；一般是 15 秒和 1 分鐘。",
                Settings.slowNetwork(this), on -> Settings.prefs(this).edit().putBoolean(Settings.SLOW_NETWORK, on).apply());
        switchRow("用行動數據傳輸前先詢問", "要傳的檔案在 NAS 或雲端連線上、而手機正在使用行動數據時，先跳出確認。",
                Settings.askMobileData(this), on -> Settings.prefs(this).edit().putBoolean(Settings.ASK_MOBILE, on).apply());
        note("傳輸設定從下一個開始的傳輸生效，進行中的傳輸不受影響。");

        section("連線設定檔");
        actionRow("匯入 rclone 設定檔", "用設定檔裡的連線取代目前所有連線。", this::startImport);
        actionRow("匯出 rclone 設定檔", "把目前的連線存成一個檔案備份。檔案裡的密碼只做簡單編碼，拿到檔案的人都能還原成原本的密碼，請妥善保管。", this::startExport);

        section("權限");
        fileAccessState = actionRow("檔案存取", "", this::openFileAccess);
        notifyState = actionRow("通知", "", this::openNotificationSettings);

        section("暫存");
        cacheState = actionRow("清除暫存資料", "計算中…", this::clearCache);

        section("關於");
        infoRow("版本", versionName());
        engineState = infoRow("傳輸引擎", "讀取中…");
        actionRow("授權說明", null, this::licenses);
        actionRow("專案網頁", "GitHub 上的原始碼、使用手冊與問題回報", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_PAGE))); }
            catch (Exception e) { message("手機裡沒有能開啟網頁的 App。"); }
        });

        worker.execute(() -> {
            Rclone.Result r = FileOps.version(this);
            String engine = r.code == 0 && !r.out.isEmpty() ? r.out.split("\n")[0].trim().replaceFirst("-[A-Za-z].*$", "") : "無法讀取";
            handler.post(() -> { if (!isDestroyed()) engineState.setText(engine); });
        });
    }

    private void refreshStates() {
        if (fileAccessState == null) return;
        boolean files = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        fileAccessState.setText(files ? "已允許" : "未允許：無法瀏覽本機和記憶卡。點這裡前往系統設定。");
        fileAccessState.setTextColor(files ? Ui.muted : Ui.error);
        boolean notify = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        notifyState.setText(notify ? "已允許" : "未允許：背景傳輸時看不到進度通知。點這裡前往系統設定。");
        notifyState.setTextColor(notify ? Ui.muted : Ui.error);
        worker.execute(() -> {
            long size = sizeOf(getCacheDir());
            handler.post(() -> { if (!isDestroyed()) cacheState.setText((size > 0 ? "目前 " + FileOps.size(size) : "目前沒有暫存資料") + "。連線設定與傳輸紀錄不受影響。"); });
        });
    }

    // ============================================================================================
    // Row builders

    interface IntPick { void pick(int i); }
    interface Toggle { void set(boolean on); }

    private void section(String name) {
        TextView t = Ui.title(this, name, 14, Ui.primary);
        t.setPadding(Ui.dp(24), Ui.dp(list.getChildCount() == 0 ? 8 : 24), Ui.dp(24), Ui.dp(6));
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        list.addView(t);
    }

    private void note(String text) {
        TextView t = Ui.text(this, text, 13, Ui.muted);
        t.setPadding(Ui.dp(24), Ui.dp(4), Ui.dp(24), 0);
        list.addView(t);
    }

    private LinearLayout baseRow(String title) {
        LinearLayout r = Ui.row(this);
        r.setMinimumHeight(Ui.dp(56));
        r.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(20), Ui.dp(10));
        r.setBackground(Ui.press(0, 0));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 16, Ui.ink));
        r.addView(texts, Ui.weight(1));
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** Title on top, control underneath (colour swatches, segmented choices). */
    private LinearLayout verticalRow(String title) {
        LinearLayout r = baseRow(title);
        r.setBackground(null);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.START);
        r.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private TextView subtitle(LinearLayout row, String text) {
        LinearLayout texts = (LinearLayout) row.getChildAt(0);
        TextView s = Ui.text(this, text == null ? "" : text, 13.5f, Ui.muted);
        s.setLineSpacing(0, 1.15f);
        s.setPadding(0, Ui.dp(2), 0, 0);
        s.setVisibility(text == null ? View.GONE : View.VISIBLE);
        texts.addView(s);
        return s;
    }

    private TextView actionRow(String title, String detail, Runnable action) {
        LinearLayout r = baseRow(title);
        TextView s = subtitle(r, detail);
        r.setOnClickListener(v -> action.run());
        return s;
    }

    private TextView infoRow(String title, String value) {
        LinearLayout r = baseRow(title);
        r.setBackground(null);
        TextView s = subtitle(r, value);
        s.setTextIsSelectable(true);
        return s;
    }

    private void choiceRow(String title, String[] options, int current, IntPick pick) {
        choiceRow(title, options, current, pick, null);
    }

    private void choiceRow(String title, String[] options, int current, IntPick pick, String explain) {
        LinearLayout r = baseRow(title);
        final TextView value = subtitle(r, options[current]);
        final int[] now = {current};
        r.setContentDescription(title + "，目前是" + options[current]);
        r.setOnClickListener(v -> {
            LinearLayout box = Ui.column(this);
            final Overlay.Dialog[] d = new Overlay.Dialog[1];
            for (int i = 0; i < options.length; i++) {
                final int k = i;
                LinearLayout opt = Ui.row(this);
                opt.setMinimumHeight(Ui.dp(48));
                opt.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
                opt.setBackground(Ui.press(0, 12));
                opt.addView(radio(i == now[0]), radioParams());
                opt.addView(Ui.text(this, options[i], 16, Ui.ink), Ui.weight(1));
                opt.setContentDescription(options[i] + (i == now[0] ? "，已選" : ""));
                opt.setOnClickListener(x -> {
                    now[0] = k;
                    value.setText(options[k]);
                    r.setContentDescription(title + "，目前是" + options[k]);
                    pick.pick(k);
                    d[0].dismiss();
                });
                box.addView(opt, new LinearLayout.LayoutParams(-1, -2));
            }
            d[0] = overlay.new Dialog(title, explain).view(box);
            d[0].action("取消", Ui.ButtonStyle.TEXT, null);
            d[0].show();
        });
    }

    private View radio(boolean on) {
        FrameLayout f = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(2), on ? Ui.primary : Ui.outline);
        f.setBackground(ring);
        if (on) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Ui.primary);
            dot.setBackground(g);
            f.addView(dot, Ui.frame(Ui.dp(10), Ui.dp(10), Gravity.CENTER));
        }
        return f;
    }

    private LinearLayout.LayoutParams radioParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20));
        lp.rightMargin = Ui.dp(16);
        return lp;
    }

    private void switchRow(String title, String detail, boolean value, Toggle toggle) {
        LinearLayout r = baseRow(title);
        subtitle(r, detail);
        final Switch sw = new Switch(this, value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(32));
        lp.leftMargin = Ui.dp(16);
        r.addView(sw, lp);
        final String extra = detail == null ? "" : "。" + detail;
        r.setContentDescription(title + "，" + (value ? "已開啟" : "已關閉") + extra);
        r.setOnClickListener(v -> {
            sw.setOn(!sw.on, true);
            r.setContentDescription(title + "，" + (sw.on ? "已開啟" : "已關閉") + extra);
            toggle.set(sw.on);
        });
    }

    private void segmentedRow(String title, String[] options, int current, IntPick pick) {
        LinearLayout r = verticalRow(title);
        LinearLayout seg = Ui.row(this);
        seg.setBackground(Ui.stroke(0, Ui.line, 1, 20));
        seg.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        for (int i = 0; i < options.length; i++) {
            final int k = i;
            boolean on = i == current;
            TextView b = Ui.text(this, options[i], 14.5f, on ? Ui.onPrimaryContainer : Ui.ink);
            if (on) b.setTypeface(Ui.medium);
            b.setGravity(Gravity.CENTER);
            b.setMinimumHeight(Ui.dp(38));
            b.setBackground(Ui.press(on ? Ui.primaryContainer : 0, 18));
            b.setContentDescription(title + "，" + options[i] + (on ? "，已選" : ""));
            b.setOnClickListener(v -> { if (k != current) pick.pick(k); });
            seg.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(10);
        r.addView(seg, lp);
    }

    /** Colour families from the desktop app: purple, blue, green. */
    private void accentRow() {
        LinearLayout r = verticalRow("配色");
        LinearLayout swatches = Ui.row(this);
        String[] keys = {Settings.PURPLE, Settings.BLUE, Settings.GREEN};
        String[] names = {"紫", "藍", "綠"};
        String current = Settings.accent(this);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean on = key.equals(current);
            int[] pal = Ui.palette(key, Ui.dark);
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), Ui.dp(8));
            item.setBackground(Ui.press(on ? Ui.surfaceHigh : 0, 16));
            FrameLayout dot = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(pal[9]);
            if (on) circle.setStroke(Ui.dp(3), pal[11]);
            dot.setBackground(circle);
            if (on) dot.addView(Ui.icon(this, R.drawable.ic_check, pal[10]), Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
            item.addView(dot, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
            TextView label = Ui.text(this, names[i], 14, on ? Ui.ink : Ui.muted);
            if (on) label.setTypeface(Ui.medium);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(6), 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
            item.setContentDescription(names[i] + "色" + (on ? "，目前的配色" : ""));
            item.setOnClickListener(v -> {
                if (key.equals(Settings.accent(this))) return;
                Settings.prefs(this).edit().putString(Settings.ACCENT, key).apply();
                LauncherIcon.apply(this);
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(4);
            swatches.addView(item, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(8);
        r.addView(swatches, lp);
    }

    private static int indexOf(int[] values, int v, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return fallback;
    }

    /** Material-style switch: a pill track with a thumb that grows when on. */
    private static final class Switch extends View {
        boolean on;
        private float pos;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
            pos = on ? 1f : 0f;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setOn(boolean value, boolean animate) {
            on = value;
            if (!animate || !Ui.animationsOn()) { pos = on ? 1f : 0f; invalidate(); return; }
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(160);
            a.setInterpolator(Ui.STANDARD);
            a.addUpdateListener(x -> { pos = (Float) x.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), stroke = Ui.dp(2);
            track.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pos > .5f ? Ui.primary : Ui.surfaceHighest);
            c.drawRoundRect(track, h / 2, h / 2, paint);
            if (pos <= .5f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(Ui.outline);
                c.drawRoundRect(track, h / 2, h / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            float radius = Ui.dp(8) + pos * Ui.dp(4);
            float cx = h / 2 + pos * (w - h);
            paint.setColor(pos > .5f ? Ui.onPrimary : Ui.outline);
            c.drawCircle(cx, h / 2, radius, paint);
        }
    }

    // ============================================================================================
    // Actions

    private void startImport() {
        if (TransferService.busy()) { message("傳輸進行中，等完成後再匯入設定檔。"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, IMPORT); } catch (Exception e) { message("手機裡沒有能選擇檔案的 App。"); }
    }

    private void startExport() {
        File conf = new File(getFilesDir(), "rclone.conf");
        if (!conf.isFile() || conf.length() == 0) { message("目前沒有連線可以匯出。"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "rclone.conf");
        try { startActivityForResult(i, EXPORT); } catch (Exception e) { message("手機裡沒有能儲存檔案的 App。"); }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == IMPORT) {
            worker.execute(() -> {
                int existing = FileOps.remotes(this).size();
                handler.post(() -> {
                    if (isDestroyed()) return;
                    if (existing == 0) { runImport(uri); return; }
                    overlay.new Dialog("匯入這份設定檔？", "目前的 " + existing + " 個連線會被設定檔裡的連線取代。要保留目前的連線，請先匯出備份。")
                            .action("取消", Ui.ButtonStyle.TEXT, null)
                            .action("取代並匯入", Ui.ButtonStyle.FILLED, () -> runImport(uri)).show();
                });
            });
        } else if (req == EXPORT) {
            worker.execute(() -> {
                String problem = null;
                try (InputStream in = new FileInputStream(new File(getFilesDir(), "rclone.conf"));
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Exception e) { problem = "無法寫入這個位置。"; }
                final String p = problem;
                handler.post(() -> { if (!isDestroyed()) { if (p == null) snack("已匯出設定檔"); else message(p); } });
            });
        }
    }

    private void runImport(Uri uri) {
        if (TransferService.busy()) { message("傳輸進行中，等完成後再匯入設定檔。"); return; }
        worker.execute(() -> {
            String problem = null;
            int count = 0;
            try { count = FileOps.importConfig(this, uri); }
            catch (Exception e) { problem = e.getMessage() == null ? "無法匯入這份設定檔。" : e.getMessage(); }
            final String p = problem;
            final int n = count;
            handler.post(() -> {
                if (isDestroyed()) return;
                if (p != null) { message(p); return; }
                Settings.prefs(this).edit().putLong(Settings.CONFIG_STAMP, System.currentTimeMillis()).apply();
                snack("已匯入 " + n + " 個連線");
            });
        });
    }

    private void openFileAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            else
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void openNotificationSettings() {
        Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        try { startActivity(i); }
        catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }

    private void clearCache() {
        if (TransferService.busy()) { message("傳輸進行中，等完成後再清除暫存資料。"); return; }
        worker.execute(() -> {
            long size = sizeOf(getCacheDir());
            File[] items = getCacheDir().listFiles();
            if (items != null) for (File f : items) deleteTree(f);
            handler.post(() -> {
                if (isDestroyed()) return;
                snack(size > 0 ? "已清除 " + FileOps.size(size) : "目前沒有暫存資料");
                refreshStates();
            });
        });
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private void licenses() {
        StringBuilder text = new StringBuilder();
        for (String name : new String[]{"THIRD-PARTY.txt", "RCLONE-LICENSE.txt"}) {
            try (InputStream in = getAssets().open(name)) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                text.append(b.toString("UTF-8")).append("\n\n");
            } catch (Exception ignored) {}
        }
        LinearLayout c = Ui.column(this);
        c.addView(overlay.sheetTitle("授權說明"));
        TextView t = Ui.text(this, text.toString().trim(), 12.5f, Ui.muted);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(24), 0, Ui.dp(24), Ui.dp(24));
        c.addView(t);
        ScrollView s = new ScrollView(this);
        s.addView(c);
        overlay.new Sheet(s).show();
    }

    private void message(String body) {
        overlay.new Dialog(null, body).action("知道了", Ui.ButtonStyle.TONAL, null).show();
    }

    private void snack(String text) {
        if (hideSnack != null) handler.removeCallbacks(hideSnack);
        snackbar.setText(text);
        snackbar.setVisibility(View.VISIBLE);
        snackbar.setAlpha(0f);
        snackbar.animate().alpha(1f).setDuration(180).start();
        hideSnack = () -> snackbar.animate().alpha(0f).setDuration(160).withEndAction(() -> snackbar.setVisibility(View.GONE)).start();
        handler.postDelayed(hideSnack, 3000);
    }
}

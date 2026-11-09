package tw.jason.rclone;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The transfer list page and the detail page of one job (0.7.0). Until 0.6.0 transfers were shown in
 * a bottom sheet that said only the source folder's name and two counts; it did not say which way the
 * files went, which method ran, or what was deleted and skipped. Every card here shows the method and
 * "from → to"; the detail page shows all counts, the settings used and the files of each group,
 * like the desktop app's job list. Both pages read TransferService only and act through {@link Host}.
 */
final class TransferPages {
    private TransferPages() {}

    /** What the pages ask the activity to do. Records are TransferService JSON rows. */
    interface Host {
        /** Location as the user sees it, e.g. "內部儲存空間/DCIM" or "nas/Photos". */
        String place(String path);
        void cancelAll();
        void cancelJob(JSONObject job, boolean running);
        void clearFinished();
        void runAgain(JSONObject record);
        void saveJob(JSONObject record);
        boolean isSaved(JSONObject record);
        void showSavedJobs();
        void deleteRecord(JSONObject record);
        void chooseCompare();
        void openDetail(String id);
    }

    // ---- Shared wording ---------------------------------------------------------------------------

    static boolean isRunning(JSONObject r) { return "進行中".equals(r.optString("state")); }
    static boolean isWaiting(JSONObject r) { return "等待中".equals(r.optString("state")); }
    static boolean isOld(JSONObject r) { return r.optString("src").isEmpty() || r.optString("kind").isEmpty(); }

    /** Stored state without exit codes, which mean nothing to the reader. Waiting is "排隊中" on screen. */
    static String stateText(JSONObject r) {
        String s = r.optString("state").replaceAll("（\\d+）", "");
        if (s.equals("等待中")) return "排隊中";
        if (s.equals("進行中")) return r.optBoolean("cancelling") ? "正在取消" : r.optString("operation") + "中";
        return s;
    }

    static int stateIcon(String state) {
        if (state.contains("沒有新增")) return R.drawable.ic_info;
        if (state.startsWith("完成")) return R.drawable.ic_check_circle;
        if (state.startsWith("失敗") || state.startsWith("無法")) return R.drawable.ic_error;
        if (state.equals("排隊中")) return R.drawable.ic_schedule;
        if (state.endsWith("中")) return R.drawable.ic_sync;
        return R.drawable.ic_cancel;
    }

    static int stateTint(String state) {
        if (state.startsWith("完成") && !state.contains("沒有新增")) return Ui.success;
        if (state.startsWith("失敗") || state.startsWith("無法")) return Ui.error;
        if (state.endsWith("中") && !state.equals("排隊中")) return Ui.primary;
        return Ui.muted;
    }

    /** "同步：讓目的地和來源一模一樣", "複製" … Old records only know the verb. */
    static String method(JSONObject r) {
        String kind = r.optString("kind");
        return kind.isEmpty() ? r.optString("operation") : MainActivity.kindLabel(kind);
    }

    static long n(JSONObject sum, String key) { return sum == null ? 0 : sum.optLong(key); }

    /** Errors as rclone counts them (every failed attempt), or the files with errors if that is more. */
    static long errors(JSONObject sum) { return Math.max(n(sum, "errors"), n(sum, "fileErrors")); }

    /** Files that went across, whatever the method calls it. */
    static long copied(String kind, JSONObject sum) {
        long a = n(sum, "added") + n(sum, "replaced") + n(sum, "moved");
        return TransferService.MIRROR.equals(kind) ? n(sum, "added") : a;
    }

    /**
     * One line for a card: "新增 12 · 更新 3 · 刪除 5 · 不用傳 1,024 · 出錯 1 次". Deletions always show for
     * "make identical", including zero, and say why when they have not happened.
     */
    static String countsLine(JSONObject r, JSONObject sum) {
        if (sum == null || sum.length() == 0) return "";
        String kind = r.optString("kind");
        ArrayList<String> p = new ArrayList<>();
        boolean running = isRunning(r);
        switch (kind) {
            case TransferService.MIRROR:
                p.add("已新增 " + MainActivity.count(n(sum, "added")));
                p.add("已更新 " + MainActivity.count(n(sum, "replaced")));
                p.add(deletionShort(r, sum));
                p.add("不用傳 " + MainActivity.count(n(sum, "same")));
                break;
            case TransferService.FILL:
                p.add("已新增 " + MainActivity.count(n(sum, "added")));
                p.add("同名略過 " + MainActivity.count(n(sum, "same")));
                break;
            case TransferService.MOVE:
                p.add("已移動 " + MainActivity.count(copied(kind, sum)));
                if (n(sum, "same") > 0) p.add("同名略過 " + MainActivity.count(n(sum, "same")));
                break;
            default:
                p.add("已複製 " + MainActivity.count(copied(kind, sum)));
                if (n(sum, "same") > 0) p.add("同名略過 " + MainActivity.count(n(sum, "same")));
                break;
        }
        // Files that failed when known; otherwise rclone's error count (e.g. a folder that could not be listed).
        if (n(sum, "fileErrors") > 0) p.add("出錯 " + MainActivity.count(n(sum, "fileErrors")) + " 個檔案");
        else if (errors(sum) > 0) p.add("出錯 " + MainActivity.count(errors(sum)) + " 次");
        if (running && n(sum, "checks") > 0 && n(sum, "totalTransfers") == 0) p.add(0, "已比對 " + MainActivity.count(n(sum, "checks")) + " 項");
        return TextUtils.join(" · ", p);
    }

    /** Deletion state of a "make identical" sync in a few words. */
    static String deletionShort(JSONObject r, JSONObject sum) {
        long deleted = n(sum, "deleted"), planned = n(sum, "onlyDst");
        if (sum.optBoolean("deletesSkipped")) return "因為出錯沒有刪除";
        if (isRunning(r) && deleted == 0 && planned > 0) return "傳完後要刪除 " + MainActivity.count(planned);
        return "已刪除 " + MainActivity.count(deleted);
    }

    /** "11/9 14:03", with the year when it is not this year. */
    static String clock(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        String f = now.get(Calendar.YEAR) == t.get(Calendar.YEAR) ? "M/d HH:mm:ss" : "yyyy/M/d HH:mm:ss";
        return new SimpleDateFormat(f, Locale.TAIWAN).format(new Date(ms));
    }

    /** rclone's ISO time ("2023-12-31T16:00:00.000000000Z") in local time, to the second. */
    static String isoClock(String iso) {
        try {
            java.time.ZonedDateTime z = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault());
            return z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"));
        } catch (Exception e) { return iso; }
    }

    /**
     * The destination did not keep modification times, and the comparison now chosen would copy unchanged files
     * again. Comparing by size or content would not, so the warning is then left out.
     */
    static boolean timeWarning(Context c, JSONObject r) {
        return r.has("timeNotKept") && Settings.compare(c) == Settings.COMPARE_DEFAULT;
    }

    /** Time used: from the record's start and end, or rclone's own elapsed time while running. */
    static long usedMs(JSONObject r, JSONObject sum) {
        long s = r.optLong("started"), e = r.optLong("ended");
        if (s > 0 && e >= s) return e - s;
        return (long) (sum == null ? 0 : sum.optDouble("elapsed", 0) * 1000);
    }

    // ---- Small views --------------------------------------------------------------------------

    private static TextView sectionLabel(Context c, String s) {
        TextView t = Ui.title(c, s, 13.5f, Ui.muted);
        t.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private static LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = lp.rightMargin = Ui.dp(16);
        lp.bottomMargin = Ui.dp(10);
        return lp;
    }

    private static View tile(Context c, int icon) {
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(Ui.round(Ui.primaryContainer, 12));
        tile.addView(Ui.icon(c, icon, Ui.onPrimaryContainer), Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        return tile;
    }

    /** Small state label with its icon, e.g. ✓ 完成. */
    private static LinearLayout stateChip(Context c, String state) {
        LinearLayout chip = Ui.row(c);
        int tint = stateTint(state);
        chip.addView(Ui.icon(c, stateIcon(state), tint), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        TextView t = Ui.title(c, state, 13, tint);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(4), 0, 0, 0);
        chip.addView(t);
        return chip;
    }

    /** "從 X" / "到 Y": the direction in words, as the sync sheet says it. */
    private static View route(Context c, Host host, JSONObject r, float size, boolean wrap) {
        LinearLayout box = Ui.column(c);
        if (isOld(r)) {
            box.addView(Ui.text(c, "舊版紀錄，沒有記下從哪裡傳到哪裡", size - 0.5f, Ui.muted));
            return box;
        }
        String dst = r.optString("dst");
        // Copy and move of one item store the full target path; the user picked the folder.
        if (!r.optBoolean("directory", true) && !TransferService.MIRROR.equals(r.optString("kind")) && !TransferService.FILL.equals(r.optString("kind")))
            dst = MainActivity.parent(dst);
        box.addView(routeLine(c, "從", host.place(r.optString("src")), size, wrap));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(2);
        box.addView(routeLine(c, "到", host.place(dst), size, wrap), lp);
        return box;
    }

    private static View routeLine(Context c, String label, String place, float size, boolean wrap) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView l = Ui.text(c, label, size, Ui.muted);
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(24), -2));
        TextView p = Ui.text(c, place, size, Ui.ink);
        if (!wrap) Ui.oneLine(p, TextUtils.TruncateAt.MIDDLE);
        row.addView(p, Ui.weight(1));
        return row;
    }

    private static ProgressBar bar(Context c) {
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.primary, .2f)));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
        return bar;
    }

    /** Fraction done by bytes, else by files; -1 while unknown. */
    static float fraction(JSONObject st) {
        if (st == null) return -1;
        long b = st.optLong("bytes"), tb = st.optLong("totalBytes"), t = st.optLong("transfers"), tt = st.optLong("totalTransfers");
        if (tb > 0) return Math.min(1f, b / (float) tb);
        if (tt > 0) return Math.min(1f, t / (float) tt);
        return -1;
    }

    /** "1.2 GB / 3.4 GB · 12.0 MB/秒" while running. */
    static String bytesLine(JSONObject st) {
        if (st == null) return "正在準備…";
        long b = st.optLong("bytes"), tb = st.optLong("totalBytes");
        if (b == 0 && tb == 0) return "正在比對，還沒找到要傳的檔案";
        String s = "已傳 " + FileOps.size(b) + (tb > 0 ? " / " + FileOps.size(tb) : "");
        double speed = st.optDouble("speed", 0);
        if (speed > 0) s += " · " + FileOps.size((long) speed) + "/秒";
        return s;
    }

    /** "已用 2 分 3 秒 · 還要約 5 分 10 秒". The total can still grow while rclone compares, so the time left is an estimate. */
    static String timeLine(JSONObject st) {
        if (st == null) return "";
        String s = "已用 " + MainActivity.duration((long) (st.optDouble("elapsedTime") * 1000));
        double eta = st.optDouble("eta", -1);
        if (st.optLong("totalBytes") > 0 && eta > 0 && !st.isNull("eta")) s += " · 還要約 " + MainActivity.duration((long) (eta * 1000));
        return s;
    }

    /** ScrollView that remembers when a finger was last on it, so the list does not shift under a tap. */
    static final class TouchScroll extends ScrollView {
        long touchedAt;
        boolean touching;
        TouchScroll(Context c) { super(c); setClipToPadding(false); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            touching = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE;
            touchedAt = android.os.SystemClock.uptimeMillis();
            return super.dispatchTouchEvent(e);
        }
        boolean busy() { return touching || android.os.SystemClock.uptimeMillis() - touchedAt < 700; }
    }

    // ============================================================================================
    // List page

    static final class ListPage {
        final Overlay.Page page;
        private final Context c;
        private final Host host;
        private final TouchScroll scroll;
        private final LinearLayout content;
        private final TextView stopAll;
        private final TextView clear;
        private String signature = "";
        // Running card parts, updated in place every tick
        private JSONObject running;
        private ProgressBar runBar;
        private TextView runState, runBytes, runCounts, runTime;

        ListPage(Context c, Overlay overlay, Host host) {
            this.c = c;
            this.host = host;
            page = overlay.new Page("傳輸紀錄");
            stopAll = Ui.button(c, "取消全部", Ui.ButtonStyle.DANGER_TEXT, v -> host.cancelAll());
            stopAll.setContentDescription("取消全部傳輸，會先確認");
            page.actions.addView(stopAll);
            clear = Ui.button(c, "清除紀錄", Ui.ButtonStyle.TEXT, v -> host.clearFinished());
            clear.setContentDescription("清除已結束的紀錄，會先確認");
            page.actions.addView(clear);
            scroll = new TouchScroll(c);
            content = Ui.column(c);
            content.setPadding(0, 0, 0, Ui.dp(24));
            scroll.addView(content);
            page.setContent(scroll);
            update();
        }

        void update() {
            JSONArray queue = TransferService.queue(), rows = TransferService.history(c);
            JSONObject run = null;
            ArrayList<JSONObject> waiting = new ArrayList<>(), done = new ArrayList<>();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject r = queue.optJSONObject(i);
                if (r == null) continue;
                if (isRunning(r)) run = r; else waiting.add(r);
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null && !isRunning(r) && !isWaiting(r)) done.add(r);
            }
            stopAll.setVisibility(run != null || !waiting.isEmpty() ? View.VISIBLE : View.GONE);
            clear.setVisibility(done.isEmpty() ? View.GONE : View.VISIBLE);
            StringBuilder sig = new StringBuilder();
            if (run != null) sig.append(run.optString("id")).append(run.optBoolean("cancelling"));
            sig.append('|');
            for (JSONObject r : waiting) sig.append(r.optString("id")).append(';');
            sig.append('|');
            for (JSONObject r : done) sig.append(r.optString("id")).append(r.optString("state")).append(r.optLong("time")).append(';');
            boolean rebuild = !sig.toString().equals(signature) && !(signature.length() > 0 && scroll.busy());
            if (rebuild) {
                signature = sig.toString();
                build(run, waiting, done);
            }
            if (running != null && run != null && running.optString("id").equals(run.optString("id"))) fillRunning(run);
        }

        private void build(JSONObject run, ArrayList<JSONObject> waiting, ArrayList<JSONObject> done) {
            content.removeAllViews();
            running = run;
            runBar = null;
            if (run != null) {
                content.addView(sectionLabel(c, "進行中"));
                content.addView(runningCard(run), cardParams());
            }
            if (!waiting.isEmpty()) {
                content.addView(sectionLabel(c, "排隊中 " + waiting.size() + " 項"));
                for (JSONObject r : waiting) content.addView(waitingCard(r), cardParams());
            }
            if (!done.isEmpty()) {
                content.addView(sectionLabel(c, "已結束"));
                for (JSONObject r : done) content.addView(doneCard(r), cardParams());
                TextView hint = Ui.text(c, "點一筆紀錄，可以看詳細結果、再做一次或存成常用工作。", 12.5f, Ui.muted);
                hint.setPadding(Ui.dp(24), Ui.dp(4), Ui.dp(24), 0);
                content.addView(hint);
            }
            if (run == null && waiting.isEmpty() && done.isEmpty()) {
                LinearLayout empty = Ui.column(c);
                empty.setGravity(Gravity.CENTER_HORIZONTAL);
                empty.setPadding(Ui.dp(32), Ui.dp(72), Ui.dp(32), Ui.dp(32));
                FrameLayout disc = new FrameLayout(c);
                disc.setBackground(Ui.round(Ui.surfaceContainer, 28));
                disc.addView(Ui.icon(c, R.drawable.ic_swap_vert, Ui.muted), Ui.frame(Ui.dp(28), Ui.dp(28), Gravity.CENTER));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64));
                dl.bottomMargin = Ui.dp(16);
                empty.addView(disc, dl);
                empty.addView(Ui.title(c, "還沒有傳輸紀錄", 17, Ui.ink));
                TextView m = Ui.text(c, "複製、移動和同步開始後，會在這裡看到進度和結果。", 14.5f, Ui.muted);
                m.setGravity(Gravity.CENTER);
                m.setPadding(0, Ui.dp(6), 0, 0);
                empty.addView(m);
                content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        /** Top of every card: method tile, name, method, state. */
        private LinearLayout head(JSONObject r, String state) {
            LinearLayout head = Ui.row(c);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
            tp.rightMargin = Ui.dp(14);
            head.addView(tile(c, MainActivity.kindIcon(r.optString("kind", TransferService.COPY))), tp);
            LinearLayout names = Ui.column(c);
            names.addView(Ui.oneLine(Ui.title(c, r.optString("name"), 16, Ui.ink), TextUtils.TruncateAt.MIDDLE));
            names.addView(Ui.oneLine(Ui.text(c, method(r), 13, Ui.muted), TextUtils.TruncateAt.END));
            head.addView(names, Ui.weight(1));
            if (state != null) {
                LinearLayout chip = stateChip(c, state);
                chip.setPadding(Ui.dp(8), 0, 0, 0);
                head.addView(chip);
            }
            return head;
        }

        private LinearLayout cardBox(JSONObject r) {
            LinearLayout card = Ui.column(c);
            card.setBackground(Ui.press(Ui.surfaceContainer, 20));
            card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(12));
            card.setOnClickListener(v -> host.openDetail(r.optString("id")));
            return card;
        }

        private static LinearLayout.LayoutParams gap(int dp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(dp);
            return lp;
        }

        private View runningCard(JSONObject r) {
            LinearLayout card = cardBox(r);
            LinearLayout head = head(r, null);
            ImageView x = Ui.iconButton(c, R.drawable.ic_close, "取消傳輸「" + r.optString("name") + "」，會先確認", Ui.muted, v -> host.cancelJob(r, true));
            head.addView(x, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
            card.addView(head);
            card.addView(route(c, host, r, 14, false), gap(10));
            runState = Ui.title(c, "", 14, Ui.primary);
            card.addView(runState, gap(10));
            runBar = bar(c);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(6));
            bp.topMargin = Ui.dp(6);
            card.addView(runBar, bp);
            runBytes = Ui.text(c, "", 13.5f, Ui.ink);
            runCounts = Ui.text(c, "", 13.5f, Ui.ink);
            runTime = Ui.text(c, "", 13, Ui.muted);
            card.addView(runBytes, gap(8));
            card.addView(runCounts, gap(2));
            LinearLayout foot = Ui.row(c);
            foot.addView(runTime, Ui.weight(1));
            foot.addView(Ui.text(c, "看詳細", 13, Ui.primary));
            foot.addView(Ui.icon(c, R.drawable.ic_chevron_right, Ui.primary), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
            card.addView(foot, gap(6));
            fillRunning(r);
            return card;
        }

        private void fillRunning(JSONObject r) {
            if (runBar == null) return;
            JSONObject st = TransferService.liveStats(), sum = TransferService.liveSummary(r.optString("id"));
            boolean stopping = r.optBoolean("cancelling");
            float f = fraction(st);
            runBar.setIndeterminate(f < 0 || stopping);
            if (f >= 0) runBar.setProgress(Math.round(f * 1000));
            runState.setText(stopping ? "正在取消…已經傳完的檔案會保留" : r.optString("operation") + "中" + (f >= 0 ? " " + Math.round(f * 100) + "%" : ""));
            runBytes.setText(stopping ? "" : bytesLine(st));
            runBytes.setVisibility(stopping ? View.GONE : View.VISIBLE);
            String counts = stopping ? "" : countsLine(r, sum);
            runCounts.setText(counts);
            runCounts.setVisibility(counts.isEmpty() ? View.GONE : View.VISIBLE);
            runTime.setText(stopping ? "" : timeLine(st));
            ((View) runBar.getParent()).setContentDescription(r.optString("name") + "，" + method(r) + "，" + runState.getText()
                    + "，從" + host.place(r.optString("src")) + "到" + host.place(r.optString("dst")) + "，" + runBytes.getText() + "，" + counts + "，查看詳細結果");
        }

        private View waitingCard(JSONObject r) {
            LinearLayout card = cardBox(r);
            LinearLayout head = head(r, "排隊中");
            ImageView x = Ui.iconButton(c, R.drawable.ic_close, "取消傳輸「" + r.optString("name") + "」，不另外確認", Ui.muted, v -> host.cancelJob(r, false));
            head.addView(x, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
            card.addView(head);
            card.addView(route(c, host, r, 14, false), gap(10));
            card.setContentDescription(r.optString("name") + "，" + method(r) + "，排隊中，從" + host.place(r.optString("src")) + "到" + host.place(r.optString("dst")) + "，查看詳細結果");
            return card;
        }

        private View doneCard(JSONObject r) {
            String state = stateText(r);
            LinearLayout card = cardBox(r);
            card.addView(head(r, state));
            card.addView(route(c, host, r, 14, false), gap(10));
            JSONObject sum = r.optJSONObject("result");
            String counts = countsLine(r, sum);
            if (sum == null) counts = isOld(r) || !r.has("created") ? "更新前的紀錄，沒有記下數字" : "沒有傳任何檔案";
            TextView cl = Ui.text(c, counts, 13.5f, sum == null ? Ui.muted : Ui.ink);
            card.addView(cl, gap(8));
            if (timeWarning(c, r)) {
                LinearLayout warn = Ui.row(c);
                warn.addView(Ui.icon(c, R.drawable.ic_info, Ui.error), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
                TextView w = Ui.text(c, "目的地沒有保留修改時間，下次同步會重傳內容沒變的檔案", 13, Ui.ink);
                w.setPadding(Ui.dp(6), 0, 0, 0);
                warn.addView(w, Ui.weight(1));
                card.addView(warn, gap(6));
            }
            LinearLayout foot = Ui.row(c);
            long used = usedMs(r, sum);
            String when = FileRow.when(r.optLong("time")) + (r.optLong("started") > 0 && used > 0 ? " · 用了 " + MainActivity.duration(used) : "");
            foot.addView(Ui.text(c, when, 12.5f, Ui.muted), Ui.weight(1));
            foot.addView(Ui.text(c, "看詳細", 13, Ui.primary));
            foot.addView(Ui.icon(c, R.drawable.ic_chevron_right, Ui.primary), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
            card.addView(foot, gap(6));
            card.setContentDescription(r.optString("name") + "，" + method(r) + "，" + state + "，"
                    + (isOld(r) ? "舊版紀錄" : "從" + host.place(r.optString("src")) + "到" + host.place(r.optString("dst"))) + "，" + counts + "，" + when + "，查看詳細結果");
            return card;
        }
    }

    // ============================================================================================
    // Detail page

    /** One list group on the detail page: key in the report, label, and the explanation under its number. */
    private static final class Group {
        final String key, label, hint;
        final String[] lists;
        final long count;
        Group(String key, String label, String hint, long count, String... lists) { this.key = key; this.label = label; this.hint = hint; this.count = count; this.lists = lists; }
    }

    /** The groups a job of this kind has, with counts. */
    static ArrayList<Group> groups(JSONObject r, JSONObject sum) {
        String kind = r.optString("kind");
        boolean dir = r.optBoolean("directory", true), run = isRunning(r);
        int compare = r.optInt("compare", Settings.COMPARE_DEFAULT);
        String differs = compare == Settings.COMPARE_SIZE ? "大小不同" : compare == Settings.COMPARE_CHECKSUM ? "內容不同" : "大小或修改時間不同";
        ArrayList<Group> g = new ArrayList<>();
        switch (kind) {
            case TransferService.MIRROR: {
                g.add(new Group("added", "新增", "只在來源，已複製過去", n(sum, "added"), TransferReport.ADDED));
                g.add(new Group("replaced", "更新", differs + "，已用來源的版本覆蓋", n(sum, "replaced"), TransferReport.REPLACED));
                g.add(new Group("same", "不用傳", "兩邊相同", n(sum, "same"), TransferReport.SAME));
                boolean pending = run && n(sum, "deleted") == 0 && n(sum, "onlyDst") > 0;
                if (sum != null && sum.optBoolean("deletesSkipped"))
                    g.add(new Group("onlyDst", "沒有刪除", "只在目的地；因為出錯，這次沒有刪", n(sum, "onlyDst"), TransferReport.ONLY_DST));
                else if (pending)
                    g.add(new Group("onlyDst", "要刪除", "只在目的地", n(sum, "onlyDst"), TransferReport.ONLY_DST));
                else
                    g.add(new Group("deleted", "已刪除", "只在目的地，已刪除", n(sum, "deleted"), TransferReport.DELETED));
                break;
            }
            case TransferService.FILL:
                g.add(new Group("added", "新增", "目的地沒有，已複製過去", n(sum, "added"), TransferReport.ADDED));
                g.add(new Group("same", "同名略過", "目的地已有同名檔案，不覆蓋", n(sum, "same"), TransferReport.SAME));
                g.add(new Group("onlyDst", "只在目的地", "保留不動", n(sum, "onlyDst"), TransferReport.ONLY_DST));
                break;
            case TransferService.MOVE:
                g.add(new Group("moved", "已移動", "已搬到目的地，來源那份已移除", copied(kind, sum), TransferReport.ADDED, TransferReport.REPLACED, TransferReport.MOVED));
                g.add(new Group("same", "同名略過", "目的地已有同名檔案，留在來源", n(sum, "same"), TransferReport.SAME));
                if (dir) g.add(new Group("onlyDst", "只在目的地", "保留不動", n(sum, "onlyDst"), TransferReport.ONLY_DST));
                break;
            default:
                g.add(new Group("added", "已複製", "目的地沒有，已複製過去", copied(kind, sum), TransferReport.ADDED, TransferReport.REPLACED));
                g.add(new Group("same", "同名略過", "目的地已有同名檔案，不覆蓋", n(sum, "same"), TransferReport.SAME));
                if (dir) g.add(new Group("onlyDst", "只在目的地", "保留不動", n(sum, "onlyDst"), TransferReport.ONLY_DST));
                break;
        }
        g.add(new Group("errors", "出錯", "沒有傳成的檔案", n(sum, "fileErrors"), TransferReport.ERRORS));
        return g;
    }

    static final class DetailPage {
        final Overlay.Page page;
        final String id;
        private final Context c;
        private final Host host;
        private final TouchScroll scroll;
        private final LinearLayout content;
        private String structure = "", listSignature = "";
        private String group;
        private long listBuiltAt;
        // Parts that change while the job runs
        private TextView stateLine, bytes, time;
        private ProgressBar progress;
        private LinearLayout tiles, statsBox, transferringBox, chips, files, warnings;

        DetailPage(Context c, Overlay overlay, Host host, String id) {
            this.c = c;
            this.host = host;
            this.id = id;
            page = overlay.new Page("傳輸詳細");
            scroll = new TouchScroll(c);
            content = Ui.column(c);
            content.setPadding(0, 0, 0, Ui.dp(32));
            scroll.addView(content);
            page.setContent(scroll);
            update();
        }

        /** Rebuilds when the job changes state; while it runs, refreshes the numbers in place. */
        void update() {
            JSONObject d = TransferService.details(c, id);
            if (d == null) { page.dismiss(); return; }
            JSONObject r = d.optJSONObject("record");
            if (r == null) return;
            String s = r.optString("state") + r.optBoolean("cancelling") + r.optLong("ended") + r.has("timeNotKept") + host.isSaved(r);
            if (!s.equals(structure)) { structure = s; build(d, r); }
            else if (isRunning(r)) fill(d, r);
        }

        private void build(JSONObject d, JSONObject r) {
            content.removeAllViews();
            listSignature = "";
            tiles = statsBox = transferringBox = chips = files = null;
            page.setTitle(r.optString("name"));
            JSONObject sum = d.optJSONObject("summary");

            // Header: state, method, route, times
            LinearLayout head = Ui.column(c);
            head.setPadding(Ui.dp(24), Ui.dp(4), Ui.dp(24), Ui.dp(4));
            LinearLayout top = Ui.row(c);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44));
            tp.rightMargin = Ui.dp(14);
            top.addView(tile(c, MainActivity.kindIcon(r.optString("kind", TransferService.COPY))), tp);
            LinearLayout names = Ui.column(c);
            names.addView(Ui.title(c, method(r), 16, Ui.ink));
            String state = stateText(r);
            stateLine = Ui.title(c, state, 14, stateTint(state));
            names.addView(stateLine);
            top.addView(names, Ui.weight(1));
            head.addView(top);
            LinearLayout routeBox = Ui.column(c);
            routeBox.setBackground(Ui.round(Ui.surfaceContainer, 16));
            routeBox.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
            routeBox.addView(route(c, host, r, 14.5f, true));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = Ui.dp(14);
            head.addView(routeBox, rl);
            if (TransferService.MIRROR.equals(r.optString("kind")) || TransferService.FILL.equals(r.optString("kind"))) {
                TextView rule = Ui.text(c, "來源不會被改動，只有目的地會變。", 13, Ui.muted);
                rule.setPadding(0, Ui.dp(6), 0, 0);
                head.addView(rule);
            }
            content.addView(head);

            boolean running = isRunning(r), waiting = isWaiting(r);
            if (running) {
                LinearLayout p = Ui.column(c);
                p.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), 0);
                progress = bar(c);
                p.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(8)));
                bytes = Ui.text(c, "", 14, Ui.ink);
                time = Ui.text(c, "", 13, Ui.muted);
                LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(-1, -2);
                g1.topMargin = Ui.dp(8);
                p.addView(bytes, g1);
                p.addView(time);
                content.addView(p);
            } else { progress = null; bytes = time = null; }

            // Actions, always visible near the top
            LinearLayout actions = Ui.row(c);
            // A text button has its own 12 dp inner space; start it so its words line up with the text above.
            actions.setPadding(running || waiting ? Ui.dp(12) : Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(4));
            if (running || waiting) {
                TextView cancel = Ui.button(c, running ? "取消這一項" : "取消排隊", Ui.ButtonStyle.DANGER_TEXT, v -> host.cancelJob(r, running));
                cancel.setContentDescription(running ? "取消這一項傳輸，會先確認" : "取消這一項，不另外確認");
                actions.addView(cancel);
            } else {
                if (!isOld(r)) {
                    actions.addView(Ui.button(c, "再做一次", Ui.ButtonStyle.TONAL, v -> host.runAgain(r)));
                    boolean saved = host.isSaved(r);
                    TextView save = Ui.button(c, saved ? "查看常用工作" : "存成常用工作", Ui.ButtonStyle.TEXT, v -> { if (saved) host.showSavedJobs(); else host.saveJob(r); });
                    save.setContentDescription(saved ? "已存過，查看常用工作" : "把這筆存成常用工作");
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
                    sp.leftMargin = Ui.dp(4);
                    actions.addView(save, sp);
                }
                View spacer = new View(c);
                actions.addView(spacer, Ui.weight(1));
                TextView del = Ui.button(c, "刪除紀錄", Ui.ButtonStyle.TEXT, v -> host.deleteRecord(r));
                del.setContentDescription("刪除這筆紀錄，不會動到任何檔案");
                actions.addView(del);
            }
            content.addView(actions);

            warnings = Ui.column(c);
            content.addView(warnings);
            fillWarnings(r, sum);

            if (sum == null && !waiting) {
                // No counts: a record from an older version, or a job that ended before it started.
                String why = isOld(r) ? "這是舊版 App 留下的紀錄，當時沒有記下從哪裡傳到哪裡，也沒有記下數字和檔案。更新後的傳輸都會記下。"
                        : !r.has("created") ? "這是更新前的紀錄，當時只記下方式和從哪裡到哪裡，沒有記下數字和檔案。更新後的傳輸都會記下。"
                        : "這一項還沒開始就結束了，沒有傳任何檔案。";
                TextView old = Ui.text(c, why, 14.5f, Ui.muted);
                old.setLineSpacing(0, 1.25f);
                old.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), 0);
                content.addView(old);
                return;
            }
            if (waiting) {
                TextView w = Ui.text(c, "還在排隊，前面的傳輸結束後就會開始。", 14.5f, Ui.muted);
                w.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), 0);
                content.addView(w);
                return;
            }

            content.addView(sectionLabel(c, running ? "目前結果" : "結果"));
            tiles = Ui.column(c);
            tiles.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
            content.addView(tiles);

            if (running) {
                content.addView(sectionLabel(c, "正在傳的檔案"));
                transferringBox = Ui.column(c);
                transferringBox.setPadding(Ui.dp(24), 0, Ui.dp(24), 0);
                content.addView(transferringBox);
            } else transferringBox = null;

            content.addView(sectionLabel(c, "比對與傳輸"));
            statsBox = Ui.column(c);
            statsBox.setPadding(Ui.dp(24), 0, Ui.dp(24), 0);
            content.addView(statsBox);

            content.addView(sectionLabel(c, "逐檔紀錄"));
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            chips = Ui.row(c);
            chips.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
            hs.addView(chips);
            content.addView(hs);
            files = Ui.column(c);
            files.setPadding(0, Ui.dp(8), 0, 0);
            content.addView(files);
            fill(d, r, true);
        }

        private void fill(JSONObject d, JSONObject r) { fill(d, r, false); }

        /** {@code force}: the user just picked a group, so redraw even right after a touch. */
        private void fill(JSONObject d, JSONObject r, boolean force) {
            JSONObject sum = d.optJSONObject("summary"), st = d.optJSONObject("stats");
            if (st == null) st = sum;
            boolean running = isRunning(r);
            if (progress != null) {
                boolean stopping = r.optBoolean("cancelling");
                float f = fraction(d.optJSONObject("stats"));
                progress.setIndeterminate(f < 0 || stopping);
                if (f >= 0) progress.setProgress(Math.round(f * 1000));
                String s = stopping ? "正在取消…已經傳完的檔案會保留" : r.optString("operation") + "中" + (f >= 0 ? " " + Math.round(f * 100) + "%" : "");
                stateLine.setText(s);
                bytes.setText(stopping ? "" : bytesLine(d.optJSONObject("stats")));
                time.setText(stopping ? "" : timeLine(d.optJSONObject("stats")));
            }
            if (tiles == null) return;
            pickDefaultGroup(groups(r, sum));
            // Rebuilding the tiles under a finger would swallow the tap.
            if (force || !scroll.busy()) fillTiles(r, sum);
            fillStats(r, sum, st);
            if (transferringBox != null) fillTransferring(d.optJSONArray("transferring"));
            fillFiles(d, r, sum, running && !force);
        }

        /** Start where something needs attention: errors, then what changed, then the first group. */
        private void pickDefaultGroup(ArrayList<Group> gs) {
            if (group != null) return;
            for (Group g : gs) if (g.key.equals("errors") && g.count > 0) group = g.key;
            if (group == null) for (Group g : gs) if (g.count > 0 && !g.key.equals("same") && !g.key.equals("onlyDst")) { group = g.key; break; }
            if (group == null) group = gs.get(0).key;
        }

        private void fillWarnings(JSONObject r, JSONObject sum) {
            warnings.removeAllViews();
            String kind = r.optString("kind");
            JSONObject t = r.optJSONObject("timeNotKept");
            if (t != null && timeWarning(c, r)) {
                String body = "例如「" + t.optString("path") + "」：來源是 " + isoClock(t.optString("src")) + "，目的地變成 " + isoClock(t.optString("dst"))
                        + "。用「" + Settings.compareName(Settings.COMPARE_DEFAULT) + "」比對時，下次「讓目的地和來源一模一樣」會把內容沒變的檔案再傳一次。"
                        + "如果這個位置一直保留不了修改時間，可以把比對方式改成「只比對大小」。";
                warnings.addView(notice(R.drawable.ic_info, Ui.error, "目的地沒有保留修改時間", body, "變更比對方式", host::chooseCompare));
            }
            if (sum != null && sum.optBoolean("deletesSkipped"))
                warnings.addView(notice(R.drawable.ic_error, Ui.error, "因為出錯，這次沒有刪除任何檔案",
                        "同步出錯時，為了安全，不會刪除目的地多出的檔案。把出錯的檔案處理好、再同步一次，才會刪除。", null, null));
            JSONArray general = sum == null ? null : sum.optJSONArray("general");
            if (general != null && general.length() > 0) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < general.length(); i++) b.append(i > 0 ? "\n" : "").append(general.optString(i));
                warnings.addView(notice(R.drawable.ic_error, Ui.error, "出錯原因", b.toString(), null, null));
            }
            if (sum != null && sum.optBoolean("recovered"))
                warnings.addView(notice(R.drawable.ic_info, Ui.primary, "中途出錯，重做後完成", "第一次有檔案沒傳成，App 自動把整個工作重做一次，重做後成功了。", null, null));
            JSONArray notes = sum == null ? null : sum.optJSONArray("notes");
            if (notes != null) for (int i = 0; i < notes.length(); i++) warnings.addView(notice(R.drawable.ic_info, Ui.primary, notes.optString(i), null, null, null));
            if (TransferService.MIRROR.equals(kind) && isRunning(r))
                warnings.addView(notice(R.drawable.ic_info, Ui.primary, "全部傳完才刪除", "目的地多出的檔案，要等所有檔案傳完、而且沒有出錯，才會刪除。", null, null));
        }

        private View notice(int icon, int tint, String title, String body, String action, Runnable run) {
            LinearLayout card = Ui.row(c);
            card.setGravity(Gravity.TOP);
            card.setBackground(Ui.round(Ui.surfaceContainer, 16));
            card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20));
            ip.rightMargin = Ui.dp(12);
            card.addView(Ui.icon(c, icon, tint), ip);
            LinearLayout texts = Ui.column(c);
            texts.addView(Ui.title(c, title, 14.5f, Ui.ink));
            if (body != null) {
                TextView b = Ui.text(c, body, 13.5f, Ui.muted);
                b.setLineSpacing(0, 1.2f);
                b.setPadding(0, Ui.dp(4), 0, 0);
                texts.addView(b);
            }
            if (action != null) {
                TextView a = Ui.button(c, action, Ui.ButtonStyle.TEXT, v -> run.run());
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
                ap.topMargin = Ui.dp(4);
                ap.leftMargin = -Ui.dp(12);
                texts.addView(a, ap);
            }
            card.addView(texts, Ui.weight(1));
            LinearLayout.LayoutParams lp = cardParams();
            lp.topMargin = Ui.dp(8);
            lp.bottomMargin = 0;
            card.setLayoutParams(lp);
            return card;
        }

        private void fillTiles(JSONObject r, JSONObject sum) {
            tiles.removeAllViews();
            ArrayList<Group> gs = groups(r, sum);
            LinearLayout row = null;
            int i = 0;
            for (Group g : gs) {
                if (i % 2 == 0) {
                    row = Ui.row(c);
                    row.setGravity(Gravity.TOP);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = Ui.dp(8);
                    tiles.addView(row, lp);
                }
                LinearLayout t = Ui.column(c);
                boolean on = g.key.equals(group);
                boolean bad = g.key.equals("errors") && g.count > 0 || g.key.equals("onlyDst") && g.label.equals("沒有刪除");
                t.setBackground(Ui.press(on ? Ui.primaryContainer : Ui.surfaceContainer, 16));
                t.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(12), Ui.dp(10));
                t.setMinimumHeight(Ui.dp(84));
                TextView num = Ui.title(c, MainActivity.count(g.count), 22, bad ? Ui.error : on ? Ui.onPrimaryContainer : Ui.ink);
                t.addView(num);
                t.addView(Ui.title(c, g.label, 14, on ? Ui.onPrimaryContainer : Ui.ink));
                TextView h = Ui.text(c, g.hint, 12.5f, Ui.muted);
                h.setLineSpacing(0, 1.15f);
                t.addView(h);
                t.setContentDescription(g.label + " " + MainActivity.count(g.count) + " 項，" + g.hint + "，在下方列出這些檔案");
                final String key = g.key;
                t.setOnClickListener(v -> { group = key; listSignature = ""; refresh(); scrollToFiles(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % 2 == 0) lp.rightMargin = Ui.dp(8);
                row.addView(t, lp);
                i++;
            }
            if (i % 2 == 1) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        }

        private void refresh() {
            JSONObject d = TransferService.details(c, id);
            if (d == null) return;
            JSONObject r = d.optJSONObject("record");
            if (r != null) fill(d, r, true);
        }

        private void scrollToFiles() {
            scroll.post(() -> { if (chips != null) scroll.smoothScrollTo(0, Math.max(0, ((View) chips.getParent()).getTop() - Ui.dp(48))); });
        }

        private void statRow(String label, String value) {
            if (value == null || value.isEmpty()) return;
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, Ui.dp(5), 0, Ui.dp(5));
            TextView l = Ui.text(c, label, 14, Ui.muted);
            row.addView(l, new LinearLayout.LayoutParams(Ui.dp(112), -2));
            TextView v = Ui.text(c, value, 14, Ui.ink);
            row.addView(v, Ui.weight(1));
            row.setContentDescription(label + "，" + value);
            statsBox.addView(row);
        }

        private void fillStats(JSONObject r, JSONObject sum, JSONObject st) {
            statsBox.removeAllViews();
            boolean running = isRunning(r);
            long listed = n(st, "listed"), checks = n(st, "checks");
            if (listed > 0) statRow("兩邊合計讀取", MainActivity.count(listed) + " 項");
            statRow("已比對", MainActivity.count(checks) + " 項");
            long t = n(st, "transfers"), tt = n(st, "totalTransfers");
            statRow("已傳檔案", MainActivity.count(t) + (tt > 0 && running ? " / " + MainActivity.count(tt) : "") + " 個");
            long b = n(st, "bytes"), tb = n(st, "totalBytes");
            statRow("資料量", FileOps.size(b) + (tb > 0 && running ? " / " + FileOps.size(tb) : ""));
            long used = usedMs(r, sum);
            if (running) {
                double speed = st == null ? 0 : st.optDouble("speed", 0);
                if (speed > 0) statRow("速度", FileOps.size((long) speed) + "/秒");
            } else if (used > 1000 && b > 0) statRow("平均速度", FileOps.size(b * 1000 / used) + "/秒");
            if (TransferService.MIRROR.equals(r.optString("kind")) && n(sum, "timeOnly") > 0)
                statRow("只更新時間", MainActivity.count(n(sum, "timeOnly")) + " 個（內容相同，沒有重新傳）");
            if (n(sum, "removedDirs") > 0) statRow("刪除的資料夾", MainActivity.count(n(sum, "removedDirs")) + " 個");
            statRow("開始", clock(r.optLong("started")));
            if (!running) statRow("結束", clock(r.optLong("ended", r.optLong("time"))));
            if (r.optLong("started") > 0) statRow("用了", MainActivity.duration(running ? (long) (st == null ? 0 : st.optDouble("elapsedTime", 0) * 1000) : used));
            if (r.has("compare")) statRow("比對方式", Settings.compareName(r.optInt("compare")));
            else if (!TransferService.MIRROR.equals(r.optString("kind")) && !isOld(r)) statRow("同名檔案", "略過，不覆蓋");
            if (r.optInt("transfers") > 0) statRow("同時處理", "傳輸 " + r.optInt("transfers") + " 個、比對 " + r.optInt("checkers") + " 個");
            int attempt = sum == null ? 1 : sum.optInt("attempt", 1), attempts = sum == null ? 0 : sum.optInt("attempts");
            if (attempt > 1) statRow("整個重做", "出錯後重做，這是第 " + attempt + " 次" + (attempts > 0 ? "（最多做 " + attempts + " 次）" : ""));
        }

        private void fillTransferring(JSONArray list) {
            transferringBox.removeAllViews();
            if (list == null || list.length() == 0) {
                transferringBox.addView(Ui.text(c, "目前沒有在傳的檔案", 13.5f, Ui.muted));
                return;
            }
            for (int i = 0; i < Math.min(8, list.length()); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null) continue;
                LinearLayout row = Ui.column(c);
                row.setPadding(0, Ui.dp(4), 0, Ui.dp(6));
                row.addView(Ui.oneLine(Ui.text(c, f.optString("name"), 14, Ui.ink), TextUtils.TruncateAt.MIDDLE));
                ProgressBar pb = bar(c);
                pb.setProgress(f.optInt("percentage") * 10);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(4));
                pp.topMargin = Ui.dp(4);
                row.addView(pb, pp);
                double speed = f.optDouble("speedAvg", f.optDouble("speed", 0));
                row.addView(Ui.text(c, f.optInt("percentage") + "% · " + FileOps.size(f.optLong("bytes")) + " / " + FileOps.size(f.optLong("size"))
                        + (speed > 0 ? " · " + FileOps.size((long) speed) + "/秒" : ""), 12.5f, Ui.muted));
                transferringBox.addView(row);
            }
        }

        private void fillFiles(JSONObject d, JSONObject r, JSONObject sum, boolean running) {
            ArrayList<Group> gs = groups(r, sum);
            pickDefaultGroup(gs);
            Group sel = gs.get(0);
            for (Group g : gs) if (g.key.equals(group)) sel = g;
            JSONObject lists = d.optJSONObject("lists");
            int kept = 0;
            if (lists != null) for (String k : sel.lists) { JSONArray a = lists.optJSONArray(k); if (a != null) kept += a.length(); }
            StringBuilder sig = new StringBuilder(group).append(kept);
            for (Group g : gs) sig.append(',').append(g.count);
            long now = android.os.SystemClock.uptimeMillis();
            if (sig.toString().equals(listSignature)) return;
            // While the job runs, the list grows; rebuild it at most every 2 seconds, and not under a finger.
            if (running && !listSignature.isEmpty() && (now - listBuiltAt < 2000 || scroll.busy())) return;
            listSignature = sig.toString();
            listBuiltAt = now;
            chips.removeAllViews();
            for (Group g : gs) {
                boolean on = g.key.equals(group);
                TextView chip = Ui.title(c, g.label + " " + MainActivity.count(g.count), 13.5f, on ? Ui.onPrimaryContainer : Ui.ink);
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setMinimumHeight(Ui.dp(36));
                chip.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
                chip.setBackground(on ? Ui.press(Ui.primaryContainer, 18) : new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Ui.ripple), Ui.stroke(Ui.surface, Ui.line, 1, 18), Ui.round(0xffffffff, 18)));
                chip.setContentDescription(g.label + " " + MainActivity.count(g.count) + " 項" + (on ? "，已選" : ""));
                final String key = g.key;
                chip.setOnClickListener(v -> { group = key; listSignature = ""; refresh(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(8);
                chips.addView(chip, lp);
            }
            files.removeAllViews();
            if (lists == null) {
                files.addView(fileNote("這筆紀錄沒有逐檔清單。"));
                return;
            }
            int shown = 0;
            for (String k : sel.lists) {
                JSONArray a = lists.optJSONArray(k);
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONArray e = a.optJSONArray(i);
                    if (e == null) continue;
                    files.addView(fileRow(e, k.equals(TransferReport.ERRORS)));
                    shown++;
                }
            }
            if (shown == 0) files.addView(fileNote(sel.count > 0 ? "這一類只記了數量，沒有檔案清單。" : "沒有這一類的檔案。"));
            else if (sel.count > shown) files.addView(fileNote("共 " + MainActivity.count(sel.count) + " 項，這裡只記下前 " + MainActivity.count(shown) + " 項。"));
        }

        private TextView fileNote(String s) {
            TextView t = Ui.text(c, s, 13.5f, Ui.muted);
            t.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), Ui.dp(4));
            return t;
        }

        private View fileRow(JSONArray e, boolean error) {
            LinearLayout row = Ui.column(c);
            row.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), Ui.dp(8));
            String path = e.optString(0);
            LinearLayout line = Ui.row(c);
            line.setGravity(Gravity.TOP);
            TextView p = Ui.text(c, path, 14, Ui.ink);
            p.setTextIsSelectable(true);
            line.addView(p, Ui.weight(1));
            long size = error ? -1 : e.optLong(1, -1);
            if (size >= 0) {
                TextView s = Ui.text(c, FileOps.size(size), 12.5f, Ui.muted);
                s.setPadding(Ui.dp(12), Ui.dp(2), 0, 0);
                line.addView(s);
            }
            row.addView(line);
            if (error) {
                TextView reason = Ui.text(c, e.optString(1), 13, Ui.error);
                reason.setPadding(0, Ui.dp(2), 0, 0);
                row.addView(reason);
                String raw = e.optString(2);
                if (!raw.isEmpty()) {
                    TextView rt = Ui.text(c, "原始訊息（英文）：" + raw, 12, Ui.muted);
                    rt.setTextIsSelectable(true);
                    rt.setPadding(0, Ui.dp(2), 0, 0);
                    row.addView(rt);
                }
            }
            return row;
        }
    }

    // ============================================================================================
    // Comparison method chooser, shared by the settings screen, the sync sheet and the detail page

    static final String COMPARE_EXPLAIN = "兩邊都有同名檔案時，用什麼判斷要不要重新傳。只用在「讓目的地和來源一模一樣」；複製、移動和「只補上缺少的檔案」遇到同名檔案一律略過，不覆蓋。";
    static final String[] COMPARE_DETAILS = {
            "大小或修改時間不同，就當成有變。只差修改時間時，如果兩邊都算得出檔案的指紋（雜湊值，例如手機和多數雲端硬碟），會先比內容，內容一樣就只更新目的地的時間、不重傳；NAS 多半算不出，會整個重傳。",
            "只看大小，不看修改時間。適合保留不了修改時間的目的地（同步後會出現「目的地沒有保留修改時間」的提醒）；大小相同、內容不同的檔案不會更新。",
            "不看修改時間，大小相同時再比檔案內容，能找出時間沒變、內容卻不同的檔案。要讀完整個檔案，檔案多時很慢；兩邊算不出共同的指紋時（例如 NAS），效果和「只比對大小」一樣。"};

    /** Radio list of the methods with one explanation each; saves the pick and runs {@code picked}. */
    static void chooseCompare(Context c, Overlay overlay, Runnable picked) {
        LinearLayout box = Ui.column(c);
        final Overlay.Dialog[] d = new Overlay.Dialog[1];
        int now = Settings.compare(c);
        for (int i = 0; i < Settings.COMPARE_NAMES.length; i++) {
            final int k = i;
            boolean on = i == now;
            LinearLayout opt = Ui.row(c);
            opt.setGravity(Gravity.TOP);
            opt.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(10));
            opt.setBackground(Ui.press(0, 12));
            FrameLayout radio = new FrameLayout(c);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setStroke(Ui.dp(2), on ? Ui.primary : Ui.outline);
            radio.setBackground(ring);
            if (on) {
                View dot = new View(c);
                android.graphics.drawable.GradientDrawable dd = new android.graphics.drawable.GradientDrawable();
                dd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dd.setColor(Ui.primary);
                dot.setBackground(dd);
                radio.addView(dot, Ui.frame(Ui.dp(10), Ui.dp(10), Gravity.CENTER));
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20));
            rp.topMargin = Ui.dp(1);
            rp.rightMargin = Ui.dp(14);
            opt.addView(radio, rp);
            LinearLayout texts = Ui.column(c);
            texts.addView(Ui.title(c, Settings.COMPARE_NAMES[i], 15.5f, Ui.ink));
            TextView t = Ui.text(c, COMPARE_DETAILS[i], 13, Ui.muted);
            t.setLineSpacing(0, 1.2f);
            t.setPadding(0, Ui.dp(3), 0, 0);
            texts.addView(t);
            opt.addView(texts, Ui.weight(1));
            opt.setContentDescription(Settings.COMPARE_NAMES[i] + "，" + COMPARE_DETAILS[i] + (on ? "，已選" : ""));
            opt.setOnClickListener(v -> {
                Settings.prefs(c).edit().putInt(Settings.COMPARE, k).apply();
                d[0].dismiss();
                if (picked != null) picked.run();
            });
            box.addView(opt, new LinearLayout.LayoutParams(-1, -2));
        }
        // The options scroll inside the dialog, so its title and buttons stay on screen on short phones.
        ScrollView sv = new ScrollView(c) {
            @Override protected void onMeasure(int w, int h) {
                int max = getResources().getDisplayMetrics().heightPixels - Ui.dp(300);
                if (max > Ui.dp(160)) h = MeasureSpec.makeMeasureSpec(Math.min(max, MeasureSpec.getSize(h) > 0 ? MeasureSpec.getSize(h) : max), MeasureSpec.AT_MOST);
                super.onMeasure(w, h);
            }
        };
        sv.addView(box);
        d[0] = overlay.new Dialog("比對方式", COMPARE_EXPLAIN).view(sv);
        d[0].action("取消", Ui.ButtonStyle.TEXT, null);
        d[0].show();
    }
}

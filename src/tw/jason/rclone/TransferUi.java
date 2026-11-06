package tw.jason.rclone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Transfer feedback: the floating progress pill and the transfer sheet. Reads TransferService only. */
final class TransferUi {
    private TransferUi() {}

    /** Circular progress. fraction < 0 spins; otherwise the arc eases to the new value. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float shown = 0f, spin = 0f;
        private boolean indeterminate = true;
        private ValueAnimator spinner, easing;

        Ring(Context c, int color) {
            super(c);
            float w = Ui.dp(2.5f);
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(w); track.setColor(Ui.alpha(color, .22f));
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(w); arc.setColor(color); arc.setStrokeCap(Paint.Cap.ROUND);
        }

        void setFraction(float f) {
            if (f < 0) {
                indeterminate = true;
                if (spinner == null) {
                    spinner = ValueAnimator.ofFloat(0f, 360f);
                    spinner.setDuration(1100);
                    spinner.setRepeatCount(ValueAnimator.INFINITE);
                    spinner.setInterpolator(new android.view.animation.LinearInterpolator());
                    spinner.addUpdateListener(a -> { spin = (Float) a.getAnimatedValue(); invalidate(); });
                }
                if (!spinner.isStarted()) spinner.start();
                return;
            }
            if (spinner != null) spinner.cancel();
            boolean was = indeterminate;
            indeterminate = false;
            if (easing != null) easing.cancel();
            if (was || !Ui.animationsOn()) { shown = f; invalidate(); return; }
            easing = ValueAnimator.ofFloat(shown, f);
            easing.setDuration(450);
            easing.setInterpolator(Ui.STANDARD);
            easing.addUpdateListener(a -> { shown = (Float) a.getAnimatedValue(); invalidate(); });
            easing.start();
        }

        void stop() { if (spinner != null) spinner.cancel(); if (easing != null) easing.cancel(); }

        @Override protected void onDraw(Canvas c) {
            float pad = arc.getStrokeWidth() / 2 + 1;
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawOval(box, track);
            if (indeterminate) c.drawArc(box, spin - 90, 100, false, arc);
            else c.drawArc(box, -90, Math.max(4f, shown * 360f), false, arc);
        }
    }

    /** Floating pill at the bottom right. Tapping it opens the transfer sheet. */
    static final class Pill extends LinearLayout {
        final Ring ring;
        final ImageView status;
        final TextView label;
        boolean visible;

        Pill(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(14), 0, Ui.dp(18), 0);
            setMinimumHeight(Ui.dp(48));
            setBackground(Ui.press(Ui.primaryContainer, 24));
            setElevation(Ui.dp(6));
            FrameLayout iconBox = new FrameLayout(c);
            ring = new Ring(c, Ui.onPrimaryContainer);
            iconBox.addView(ring, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
            status = new ImageView(c);
            status.setVisibility(GONE);
            iconBox.addView(status, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
            LayoutParams ip = new LayoutParams(Ui.dp(22), Ui.dp(22));
            ip.rightMargin = Ui.dp(10);
            addView(iconBox, ip);
            label = Ui.title(c, "", 14.5f, Ui.onPrimaryContainer);
            label.setSingleLine(true);
            addView(label);
            setVisibility(GONE);
        }

        void showBusy(FileOps.Progress p, String verb) {
            status.setVisibility(GONE);
            ring.setVisibility(VISIBLE);
            float f = p.fraction();
            ring.setFraction(f);
            String text = verb + (f >= 0 ? " " + Math.round(f * 100) + "%" : "中");
            if (p.waiting > 0) text += " · 還有 " + p.waiting + " 項";
            label.setText(text);
            setContentDescription(text + "，查看傳輸紀錄");
            appear();
        }

        void showResult(int icon, int tint, String text) {
            ring.stop();
            ring.setVisibility(GONE);
            status.setVisibility(VISIBLE);
            status.setImageResource(icon);
            status.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            label.setText(text);
            setContentDescription(text + "，查看傳輸紀錄");
            appear();
        }

        private void appear() {
            if (visible) return;
            visible = true;
            setVisibility(VISIBLE);
            animate().cancel();
            if (!Ui.animationsOn()) { setAlpha(1f); setScaleX(1f); setScaleY(1f); return; }
            setAlpha(0f); setScaleX(.8f); setScaleY(.8f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(Ui.ENTER).withEndAction(null).start();
        }

        void disappear() {
            if (!visible) return;
            visible = false;
            ring.stop();
            animate().cancel();
            animate().alpha(0f).scaleX(.85f).scaleY(.85f).setDuration(160).setInterpolator(Ui.EXIT)
                    .withEndAction(() -> { if (!visible) setVisibility(GONE); }).start();
        }
    }

    // ---- Sheet --------------------------------------------------------------------------------

    /** Live content for the transfer sheet; call update() from the activity's tick. */
    static final class Panel extends LinearLayout {
        private final LinearLayout current, history;
        private final TextView currentTitle, currentName, currentDetail;
        private final android.widget.ProgressBar bar;
        private final TextView stopAll;
        private String lastHistory = "";

        Panel(Context c, Runnable onStopAll) {
            super(c);
            setOrientation(VERTICAL);
            LinearLayout titleRow = Ui.row(c);
            titleRow.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(12), Ui.dp(8));
            TextView title = Ui.title(c, "傳輸紀錄", 20, Ui.ink);
            titleRow.addView(title, Ui.weight(1));
            stopAll = Ui.button(c, "取消全部", Ui.ButtonStyle.DANGER_TEXT, v -> onStopAll.run());
            titleRow.addView(stopAll);
            addView(titleRow);

            current = Ui.column(c);
            current.setBackground(Ui.round(Ui.surfaceContainer, 20));
            current.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
            currentTitle = Ui.title(c, "", 13.5f, Ui.primary);
            currentName = Ui.oneLine(Ui.title(c, "", 16, Ui.ink), TextUtils.TruncateAt.MIDDLE);
            bar = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.primary, .2f)));
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.primary));
            currentDetail = Ui.text(c, "", 13, Ui.muted);
            current.addView(currentTitle);
            LayoutParams np = new LayoutParams(-1, -2); np.topMargin = Ui.dp(2);
            current.addView(currentName, np);
            LayoutParams bp = new LayoutParams(-1, Ui.dp(6)); bp.topMargin = Ui.dp(12); bp.bottomMargin = Ui.dp(8);
            current.addView(bar, bp);
            current.addView(currentDetail);
            LayoutParams cp = new LayoutParams(-1, -2);
            cp.leftMargin = cp.rightMargin = Ui.dp(16);
            cp.bottomMargin = Ui.dp(8);
            addView(current, cp);

            android.widget.ScrollView scroll = new android.widget.ScrollView(c);
            history = Ui.column(c);
            history.setPadding(0, 0, 0, Ui.dp(16));
            scroll.addView(history);
            addView(scroll, new LayoutParams(-1, -2));
        }

        void update(Context c) {
            FileOps.Progress p = FileOps.progress();
            JSONArray rows = TransferService.history(c);
            JSONObject running = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null && "進行中".equals(r.optString("state"))) { running = r; break; }
            }
            stopAll.setVisibility(p.busy ? VISIBLE : GONE);
            current.setVisibility(p.busy ? VISIBLE : GONE);
            if (p.busy) {
                String verb = running != null ? running.optString("operation") : "傳輸";
                currentTitle.setText(verb + "中");
                currentName.setText(running != null ? running.optString("name") : "準備中…");
                float f = p.fraction();
                bar.setIndeterminate(f < 0);
                if (f >= 0) bar.setProgress(Math.round(f * 1000));
                StringBuilder d = new StringBuilder();
                if (p.bytes >= 0) d.append(FileOps.size(p.bytes)).append(p.total > 0 ? " / " + FileOps.size(p.total) : "");
                if (!p.speed.isEmpty()) d.append(d.length() > 0 ? "  ·  " : "").append(p.speed);
                if (p.waiting > 0) d.append(d.length() > 0 ? "  ·  " : "").append("還有 ").append(p.waiting).append(" 項等待");
                currentDetail.setText(d.length() == 0 ? "正在計算大小…" : d.toString());
            }
            String signature = rows.toString();
            if (signature.equals(lastHistory)) return;
            lastHistory = signature;
            history.removeAllViews();
            int count = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null || "進行中".equals(r.optString("state"))) continue;
                if (count == 0) {
                    TextView s = Ui.title(c, "最近", 13.5f, Ui.muted);
                    s.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(4));
                    history.addView(s);
                }
                history.addView(historyRow(c, r));
                count++;
            }
            if (count == 0 && !p.busy) {
                TextView empty = Ui.text(c, "還沒有傳輸紀錄", 15, Ui.muted);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(Ui.dp(24), Ui.dp(28), Ui.dp(24), Ui.dp(28));
                history.addView(empty, new LayoutParams(-1, -2));
            }
        }

        private View historyRow(Context c, JSONObject r) {
            // Exit codes in the stored state mean nothing to the reader; drop them for display only.
            String state = r.optString("state").replaceAll("（\\d+）", "");
            int icon, tint;
            if (state.contains("沒有新增")) { icon = R.drawable.ic_info; tint = Ui.muted; }
            else if (state.startsWith("完成")) { icon = R.drawable.ic_check_circle; tint = Ui.success; }
            else if (state.startsWith("失敗") || state.startsWith("無法")) { icon = R.drawable.ic_error; tint = Ui.error; }
            else if (state.equals("等待中")) { icon = R.drawable.ic_schedule; tint = Ui.muted; }
            else { icon = R.drawable.ic_cancel; tint = Ui.muted; }
            LinearLayout row = Ui.row(c);
            row.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), Ui.dp(10));
            ImageView i = Ui.icon(c, icon, tint);
            LayoutParams ip = new LayoutParams(Ui.dp(22), Ui.dp(22));
            ip.rightMargin = Ui.dp(16);
            row.addView(i, ip);
            LinearLayout texts = Ui.column(c);
            texts.addView(Ui.oneLine(Ui.text(c, r.optString("name"), 15.5f, Ui.ink), TextUtils.TruncateAt.MIDDLE));
            texts.addView(Ui.oneLine(Ui.text(c, r.optString("operation") + " · " + state, 13, Ui.muted), TextUtils.TruncateAt.END));
            row.addView(texts, Ui.weight(1));
            TextView time = Ui.text(c, FileRow.when(r.optLong("time")), 12.5f, Ui.muted);
            time.setPadding(Ui.dp(12), 0, 0, 0);
            row.addView(time);
            row.setContentDescription(r.optString("name") + "，" + r.optString("operation") + "，" + state);
            return row;
        }
    }
}

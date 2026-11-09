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
import java.util.ArrayList;

/** Transfer feedback: the floating progress pill and its ring. The pages are in TransferPages (0.7.0). */
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

    /** Floating pill at the bottom right. Tapping it opens the transfer page. */
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
}

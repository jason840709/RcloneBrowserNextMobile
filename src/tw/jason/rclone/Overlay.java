package tw.jason.rclone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * Sheets, menus and dialogs drawn inside the activity so they share one look and one motion system.
 * Layers stack; Back closes the top one. Everything animates from its current on-screen value, so a
 * close that starts during an open simply reverses.
 */
final class Overlay {
    final Context context;
    final FrameLayout host;
    private final View blocked;
    private final ArrayList<Layer> layers = new ArrayList<>();
    int top, nav, ime, left, right;

    Overlay(Context c, FrameLayout root, View mainContent) {
        context = c;
        blocked = mainContent;
        host = new FrameLayout(c);
        host.setClipChildren(false);
        // Above the elevated bars and the transfer pill regardless of child order.
        host.setTranslationZ(Ui.dp(32));
        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        host.setVisibility(View.GONE);
    }

    boolean showing() { return !layers.isEmpty(); }

    void insets(int top, int nav, int ime, int left, int right) {
        this.top = top; this.nav = nav; this.ime = ime; this.left = left; this.right = right;
        host.setPadding(left, 0, right, ime > nav ? ime : 0);
        for (Layer l : layers) l.applyInsets();
    }

    boolean back() {
        if (layers.isEmpty()) return false;
        Layer l = layers.get(layers.size() - 1);
        if (l.cancelable) l.dismiss();
        return true;
    }

    void dismissAll() {
        for (Layer l : new ArrayList<>(layers)) l.dismiss();
    }

    private void added(Layer l) {
        if (layers.isEmpty()) {
            host.setVisibility(View.VISIBLE);
            blocked.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        layers.add(l);
    }

    private void removed(Layer l) {
        layers.remove(l);
        if (layers.isEmpty()) {
            host.setVisibility(View.GONE);
            blocked.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(host.getWindowToken(), 0);
    }

    static void showKeyboard(final EditText edit) {
        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) edit.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    // ------------------------------------------------------------------------------------------

    abstract class Layer {
        final FrameLayout frame = new FrameLayout(context);
        final View scrim = new View(context);
        boolean cancelable = true, closing;
        Runnable onDismiss;

        Layer(boolean dim) {
            scrim.setBackgroundColor(dim ? Ui.scrim : 0);
            scrim.setOnClickListener(v -> { if (cancelable) dismiss(); });
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            frame.setClipChildren(false);
        }

        void attach() {
            added(this);
            host.addView(frame, new FrameLayout.LayoutParams(-1, -1));
            applyInsets();
        }

        void applyInsets() {}

        void dismiss() {
            if (closing) return;
            closing = true;
            hideKeyboard();
            animateOut(() -> {
                host.removeView(frame);
                removed(this);
                if (onDismiss != null) onDismiss.run();
            });
        }

        abstract void animateOut(Runnable end);
    }

    // ------------------------------------------------------------------------------------------

    /** Bottom sheet. Drag down on the handle or on content that is scrolled to its top to close. */
    final class Sheet extends Layer {
        final SheetPanel panel;
        final LinearLayout body;

        Sheet(View content) {
            super(true);
            panel = new SheetPanel(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(Ui.roundTop(Ui.surfaceLow, 28));
            panel.setClickable(true);
            panel.setElevation(Ui.dp(2));
            View handle = new View(context);
            handle.setBackground(Ui.round(Ui.alpha(Ui.outline, .45f), 2));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(4));
            hp.gravity = Gravity.CENTER_HORIZONTAL;
            hp.topMargin = Ui.dp(14);
            hp.bottomMargin = Ui.dp(6);
            panel.addView(handle, hp);
            body = Ui.column(context);
            panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
            body.addView(content, new LinearLayout.LayoutParams(-1, -2));
            frame.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }

        @Override void applyInsets() {
            panel.setPadding(0, 0, 0, ime > nav ? 0 : nav);
            int width = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int want = width > Ui.dp(640) ? Ui.dp(640) : -1;
            if (lp.width != want) { lp.width = want; panel.setLayoutParams(lp); }
            panel.maxHeight = host.getHeight() > 0 ? host.getHeight() - host.getPaddingBottom() - top - Ui.dp(24) : 0;
            panel.requestLayout();
        }

        Sheet show() {
            attach();
            scrim.setAlpha(0f);
            panel.setVisibility(View.INVISIBLE);
            panel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    panel.getViewTreeObserver().removeOnPreDrawListener(this);
                    applyInsets();
                    panel.setTranslationY(panel.getHeight());
                    panel.setVisibility(View.VISIBLE);
                    panel.animate().translationY(0).setDuration(320).setInterpolator(Ui.ENTER).setListener(null).start();
                    scrim.animate().alpha(1f).setDuration(200).start();
                    return false;
                }
            });
            return this;
        }

        @Override void animateOut(Runnable end) {
            settleOut(0f, end);
        }

        void settleOut(float velocityPxPerSec, final Runnable end) {
            float remaining = Math.max(1, panel.getHeight() - panel.getTranslationY());
            long duration = velocityPxPerSec > 0 ? (long) Math.max(120, Math.min(260, remaining / velocityPxPerSec * 1000)) : 220;
            panel.animate().translationY(panel.getHeight()).setDuration(duration).setInterpolator(Ui.EXIT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator a) { panel.animate().setListener(null); end.run(); }
                    }).start();
            scrim.animate().alpha(0f).setDuration(duration).start();
        }

        final class SheetPanel extends LinearLayout {
            int maxHeight;
            private float downY, downX, startT;
            private boolean dragging;
            private VelocityTracker tracker;
            private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();

            SheetPanel(Context c) { super(c); }

            @Override protected void onMeasure(int w, int h) {
                if (maxHeight > 0) h = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                super.onMeasure(w, h);
            }

            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); downX = e.getRawX(); dragging = false;
                        track(e, true);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        track(e, false);
                        float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                        if (!closing && dy > slop && Math.abs(dy) > Math.abs(dx) && !scrollableAbove(this, e.getRawX(), e.getRawY())) {
                            beginDrag(e);
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }

            private void beginDrag(MotionEvent e) {
                dragging = true;
                animate().cancel();
                scrim.animate().cancel();
                startT = getTranslationY();
                downY = e.getRawY();
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (closing) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); downX = e.getRawX(); dragging = false;
                        track(e, true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        track(e, false);
                        if (!dragging) {
                            if (Math.abs(e.getRawY() - downY) > slop) beginDrag(e); else return true;
                        }
                        float t = startT + e.getRawY() - downY;
                        // Downward follows the finger 1:1; upward resists so the sheet stays on the edge.
                        setTranslationY(t >= 0 ? t : -Ui.dp(24) * (1 - 1 / (1 + -t / Ui.dp(120))));
                        float progress = getHeight() > 0 ? Math.max(0f, getTranslationY()) / getHeight() : 0f;
                        scrim.setAlpha(1f - progress);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        track(e, false);
                        float v = 0;
                        if (tracker != null) { tracker.computeCurrentVelocity(1000); v = tracker.getYVelocity(); tracker.recycle(); tracker = null; }
                        if (!dragging) return true;
                        dragging = false;
                        boolean close = cancelable && (v > Ui.dp(900) || (getTranslationY() > getHeight() * 0.3f && v > -Ui.dp(300)));
                        if (close) {
                            closing = true;
                            hideKeyboard();
                            settleOut(Math.max(v, 0), () -> { host.removeView(frame); removed(Sheet.this); if (onDismiss != null) onDismiss.run(); });
                        } else {
                            animate().translationY(0).setDuration(240).setInterpolator(Ui.STANDARD).setListener(null).start();
                            scrim.animate().alpha(1f).setDuration(240).start();
                        }
                        return true;
                    }
                }
                return true;
            }

            private void track(MotionEvent e, boolean reset) {
                if (reset && tracker != null) { tracker.recycle(); tracker = null; }
                if (tracker == null) tracker = VelocityTracker.obtain();
                MotionEvent copy = MotionEvent.obtain(e);
                copy.setLocation(e.getRawX(), e.getRawY());
                tracker.addMovement(copy);
                copy.recycle();
            }
        }
    }

    /** True when a scrollable view under the finger can still scroll toward the top. */
    static boolean scrollableAbove(View v, float rawX, float rawY) {
        if (!v.isShown()) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        if (!r.contains((int) rawX, (int) rawY)) return false;
        if (v.canScrollVertically(-1)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (scrollableAbove(g.getChildAt(i), rawX, rawY)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------

    /** Popup menu anchored to the control that opened it, growing out of that corner. */
    final class Menu extends Layer {
        final LinearLayout card;
        private final View anchor;

        Menu(View anchor) {
            super(false);
            this.anchor = anchor;
            card = Ui.column(context);
            card.setBackground(Ui.round(Ui.surfaceContainer, 16));
            card.setElevation(Ui.dp(8));
            card.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            card.setClickable(true);
            frame.addView(card, new FrameLayout.LayoutParams(Ui.dp(280), -2));
        }

        Menu add(View v) { card.addView(v, new LinearLayout.LayoutParams(-1, -2)); return this; }

        Menu item(String label, boolean checked, Runnable action) {
            LinearLayout r = Ui.row(context);
            r.setMinimumHeight(Ui.dp(48));
            r.setPadding(Ui.dp(16), 0, Ui.dp(14), 0);
            r.setBackground(Ui.press(0, 0));
            r.addView(Ui.oneLine(Ui.text(context, label, 15.5f, Ui.ink), TextUtils.TruncateAt.END), Ui.weight(1));
            if (checked) {
                r.addView(Ui.icon(context, R.drawable.ic_check, Ui.primary), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
                r.setContentDescription(label + "，已開啟");
            }
            r.setOnClickListener(v -> { dismiss(); action.run(); });
            return add(r);
        }

        Menu divider() {
            LinearLayout wrap = Ui.column(context);
            wrap.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
            wrap.addView(Ui.divider(context), new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(1))));
            return add(wrap);
        }

        Menu show() {
            attach();
            card.setVisibility(View.INVISIBLE);
            card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    card.getViewTreeObserver().removeOnPreDrawListener(this);
                    place();
                    card.setVisibility(View.VISIBLE);
                    card.setAlpha(0f); card.setScaleX(.9f); card.setScaleY(.9f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Ui.ENTER).setListener(null).start();
                    return false;
                }
            });
            return this;
        }

        private void place() {
            int[] a = new int[2], h = new int[2];
            anchor.getLocationInWindow(a);
            host.getLocationInWindow(h);
            int ax = a[0] - h[0], ay = a[1] - h[1];
            int w = card.getWidth(), hgt = card.getHeight();
            boolean alignRight = ax + anchor.getWidth() / 2 > host.getWidth() / 2;
            int x = alignRight ? ax + anchor.getWidth() - w - Ui.dp(4) : ax + Ui.dp(4);
            x = Math.max(Ui.dp(8), Math.min(x, host.getWidth() - w - Ui.dp(8)));
            int y = Math.max(top + Ui.dp(4), ay + Ui.dp(4));
            int bottomLimit = host.getHeight() - host.getPaddingBottom() - nav - Ui.dp(8);
            if (y + hgt > bottomLimit) y = Math.max(top + Ui.dp(8), bottomLimit - hgt);
            card.setX(x);
            card.setY(y);
            card.setPivotX(alignRight ? w : 0);
            card.setPivotY(Math.max(0, Math.min(hgt, ay + anchor.getHeight() / 2 - y)));
        }

        @Override void animateOut(final Runnable end) {
            card.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(120).setInterpolator(Ui.EXIT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator x) { card.animate().setListener(null); end.run(); }
                    }).start();
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Centered dialog for decisions and short text entry. */
    final class Dialog extends Layer {
        final LinearLayout card;
        final LinearLayout buttons;
        private final LinearLayout body;

        Dialog(CharSequence title, CharSequence message) {
            super(true);
            card = Ui.column(context);
            card.setBackground(Ui.round(Ui.surfaceHigh, 28));
            card.setElevation(Ui.dp(6));
            card.setPadding(Ui.dp(24), Ui.dp(24), Ui.dp(24), Ui.dp(18));
            card.setClickable(true);
            if (title != null) {
                TextView t = Ui.title(context, title, 20, Ui.ink);
                t.setLineSpacing(0, 1.1f);
                card.addView(t, new LinearLayout.LayoutParams(-1, -2));
                if (Build.VERSION.SDK_INT >= 28) card.setAccessibilityPaneTitle(title);
            }
            body = Ui.column(context);
            card.addView(body, new LinearLayout.LayoutParams(-1, -2));
            if (message != null) {
                TextView m = Ui.text(context, message, 15, Ui.muted);
                m.setLineSpacing(0, 1.25f);
                m.setPadding(0, Ui.dp(title == null ? 0 : 14), 0, 0);
                body.addView(m, new LinearLayout.LayoutParams(-1, -2));
            }
            buttons = Ui.row(context);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = Ui.dp(20);
            card.addView(buttons, bp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            frame.addView(card, lp);
        }

        Dialog view(View v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(16);
            body.addView(v, lp);
            return this;
        }

        Dialog button(String label, Ui.ButtonStyle style, final Runnable action) {
            TextView b = Ui.button(context, label, style, v -> { if (action != null) action.run(); else dismiss(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Ui.dp(8);
            buttons.addView(b, lp);
            return this;
        }

        /** Button that closes the dialog first, then runs the action. */
        Dialog action(String label, Ui.ButtonStyle style, final Runnable action) {
            return button(label, style, () -> { dismiss(); if (action != null) action.run(); });
        }

        @Override void applyInsets() {
            int width = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
            int target = width <= 0 ? -1 : Math.min(width - Ui.dp(48), Ui.dp(420));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            lp.width = target <= 0 ? -1 : target;
            lp.topMargin = top;
            card.setLayoutParams(lp);
        }

        Dialog show() {
            attach();
            scrim.setAlpha(0f);
            card.setAlpha(0f); card.setScaleX(.92f); card.setScaleY(.92f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(Ui.ENTER).setListener(null).start();
            scrim.animate().alpha(1f).setDuration(180).start();
            host.post(this::applyInsets);
            return this;
        }

        @Override void animateOut(final Runnable end) {
            scrim.animate().alpha(0f).setDuration(150).start();
            card.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(150).setInterpolator(Ui.EXIT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator x) { card.animate().setListener(null); end.run(); }
                    }).start();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Building blocks for sheet and menu content

    // ------------------------------------------------------------------------------------------

    /**
     * Full-screen page (0.7.0): top bar with Back, a title and optional actions, and a body that fills the
     * rest. It slides in from the right; Back or the arrow closes it. Sheets and dialogs opened later stack
     * above it.
     */
    final class Page extends Layer {
        final LinearLayout panel, bar, actions;
        final TextView title;
        final FrameLayout body;

        Page(String titleText) {
            super(false);
            panel = Ui.column(context);
            panel.setBackgroundColor(Ui.surface);
            panel.setClickable(true);
            bar = Ui.row(context);
            bar.addView(Ui.iconButton(context, R.drawable.ic_back, "返回", Ui.ink, v -> dismiss()), new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
            title = Ui.oneLine(Ui.title(context, titleText, 20, Ui.ink), TextUtils.TruncateAt.END);
            title.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            bar.addView(title, Ui.weight(1));
            actions = Ui.row(context);
            bar.addView(actions);
            panel.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            body = new FrameLayout(context);
            panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
            frame.addView(panel, new FrameLayout.LayoutParams(-1, -1));
            if (Build.VERSION.SDK_INT >= 28) panel.setAccessibilityPaneTitle(titleText);
        }

        void setTitle(String s) {
            title.setText(s);
            if (Build.VERSION.SDK_INT >= 28) panel.setAccessibilityPaneTitle(s);
        }

        void setContent(View v) {
            body.removeAllViews();
            body.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }

        @Override void applyInsets() {
            bar.setPadding(Ui.dp(4), top, Ui.dp(8), 0);
            bar.setMinimumHeight(top + Ui.dp(56));
            body.setPadding(0, 0, 0, ime > nav ? 0 : nav);
        }

        Page show() {
            attach();
            if (!Ui.animationsOn()) return this;
            panel.setAlpha(0f);
            panel.setTranslationX(Ui.dp(48));
            panel.animate().alpha(1f).translationX(0).setDuration(260).setInterpolator(Ui.ENTER).setListener(null).start();
            return this;
        }

        @Override void animateOut(final Runnable end) {
            if (!Ui.animationsOn()) { end.run(); return; }
            panel.animate().alpha(0f).translationX(Ui.dp(48)).setDuration(180).setInterpolator(Ui.EXIT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator a) { panel.animate().setListener(null); end.run(); }
                    }).start();
        }
    }

    /** Sheet title row. */
    TextView sheetTitle(String s) {
        TextView t = Ui.title(context, s, 20, Ui.ink);
        t.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), Ui.dp(12));
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    TextView sectionLabel(String s) {
        TextView t = Ui.title(context, s, 13.5f, Ui.muted);
        t.setPadding(Ui.dp(24), Ui.dp(14), Ui.dp(24), Ui.dp(6));
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    /** List row used in sheets: 40dp tile, title, optional subtitle and trailing view. */
    LinearLayout sheetRow(int icon, int tileBg, int tileFg, String title, String subtitle, View trailing, View.OnClickListener click) {
        LinearLayout r = Ui.row(context);
        r.setMinimumHeight(Ui.dp(subtitle == null ? 56 : 64));
        r.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(trailing == null ? 20 : 4), Ui.dp(6));
        if (click != null) { r.setBackground(Ui.press(0, 0)); r.setOnClickListener(click); }
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(Ui.round(tileBg, 12));
        tile.addView(Ui.icon(context, icon, tileFg), Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40));
        tp.rightMargin = Ui.dp(16);
        r.addView(tile, tp);
        LinearLayout texts = Ui.column(context);
        texts.addView(Ui.oneLine(Ui.text(context, title, 16, Ui.ink), TextUtils.TruncateAt.MIDDLE));
        if (subtitle != null) texts.addView(Ui.oneLine(Ui.text(context, subtitle, 13, Ui.muted), TextUtils.TruncateAt.END));
        r.addView(texts, Ui.weight(1));
        if (trailing != null) r.addView(trailing);
        return r;
    }
}

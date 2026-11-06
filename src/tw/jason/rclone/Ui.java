package tw.jason.rclone;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look: colours, type, shapes, press feedback and motion curves. No behaviour lives here. */
final class Ui {
    private Ui() {}

    static float density = 1f;
    static int surface, surfaceLow, surfaceContainer, surfaceHigh, surfaceHighest, ink, muted, outline, line;
    static int primary, onPrimary, primaryContainer, onPrimaryContainer, activeHeader;
    static int error, onError, errorContainer, onErrorContainer, success, successContainer;
    static int inverseSurface, inverseInk, inversePrimary, ripple, scrim;
    static int splitBar, onSplitBar;
    static boolean dark;
    static Typeface regular, medium, bold;

    /** Material 3 easing: standard for small changes, emphasized for things entering, accelerate for exits. */
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final TimeInterpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        surface = c.getColor(R.color.surface);
        surfaceLow = c.getColor(R.color.surface_low);
        surfaceContainer = c.getColor(R.color.surface_container);
        surfaceHigh = c.getColor(R.color.surface_high);
        surfaceHighest = c.getColor(R.color.surface_highest);
        ink = c.getColor(R.color.on_surface);
        muted = c.getColor(R.color.on_surface_variant);
        outline = c.getColor(R.color.outline);
        line = c.getColor(R.color.outline_variant);
        primary = c.getColor(R.color.primary);
        onPrimary = c.getColor(R.color.on_primary);
        primaryContainer = c.getColor(R.color.primary_container);
        onPrimaryContainer = c.getColor(R.color.on_primary_container);
        activeHeader = c.getColor(R.color.active_header);
        error = c.getColor(R.color.error);
        onError = c.getColor(R.color.on_error);
        errorContainer = c.getColor(R.color.error_container);
        onErrorContainer = c.getColor(R.color.on_error_container);
        success = c.getColor(R.color.success);
        successContainer = c.getColor(R.color.success_container);
        inverseSurface = c.getColor(R.color.inverse_surface);
        inverseInk = c.getColor(R.color.inverse_on_surface);
        inversePrimary = c.getColor(R.color.inverse_primary);
        ripple = c.getColor(R.color.ripple);
        scrim = c.getColor(R.color.scrim);
        splitBar = c.getColor(R.color.split_bar);
        onSplitBar = c.getColor(R.color.on_split_bar);
        dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 28) {
            // The system CJK font ships regular and bold only; 600 gives Latin semibold and CJK bold,
            // so mixed titles such as "新增 WebDAV 連線" keep one visual weight.
            medium = Typeface.create(Typeface.DEFAULT, 600, false);
            bold = Typeface.create(Typeface.DEFAULT, 700, false);
        } else {
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            bold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
    }

    static int dp(float v) { return Math.round(v * density); }

    static int alpha(int color, float a) {
        return (Math.round(Color.alpha(color) * a) << 24) | (color & 0x00ffffff);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    static GradientDrawable roundTop(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    static GradientDrawable stroke(int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = round(fill, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    /** Bounded ripple over a rounded background. Pressed state shows on touch-down. */
    static Drawable press(int background, float radiusDp) {
        GradientDrawable mask = round(Color.WHITE, radiusDp);
        Drawable content = background == 0 ? null : round(background, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }

    /** Unbounded circular ripple for icon buttons. */
    static Drawable pressCircle() {
        return new RippleDrawable(ColorStateList.valueOf(ripple), null, null);
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(regular);
        t.setIncludeFontPadding(true);
        return t;
    }

    static TextView title(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(medium);
        return t;
    }

    static TextView oneLine(TextView t, TextUtils.TruncateAt where) {
        t.setSingleLine(true);
        t.setEllipsize(where);
        return t;
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return v;
    }

    /** 48dp touch target with a 24dp glyph. */
    static ImageView iconButton(Context c, int res, String description, int tint, View.OnClickListener click) {
        ImageView v = icon(c, res, tint);
        int p = dp(12);
        v.setPadding(p, p, p, p);
        v.setContentDescription(description);
        v.setBackground(pressCircle());
        v.setOnClickListener(click);
        if (Build.VERSION.SDK_INT >= 26) v.setTooltipText(description);
        return v;
    }

    enum ButtonStyle { FILLED, TONAL, TEXT, DANGER, DANGER_TEXT }

    static TextView button(Context c, String label, ButtonStyle style, View.OnClickListener click) {
        TextView b = title(c, label, 15, primary);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(style == ButtonStyle.TEXT || style == ButtonStyle.DANGER_TEXT ? 12 : 22), 0,
                dp(style == ButtonStyle.TEXT || style == ButtonStyle.DANGER_TEXT ? 12 : 22), 0);
        b.setSingleLine(true);
        // setSingleLine resets TextView's own min height, so use the View minimums.
        b.setMinimumHeight(dp(40));
        b.setMinimumWidth(dp(64));
        switch (style) {
            case FILLED: b.setTextColor(onPrimary); b.setBackground(press(primary, 20)); break;
            case TONAL: b.setTextColor(onPrimaryContainer); b.setBackground(press(primaryContainer, 20)); break;
            case DANGER: b.setTextColor(onError); b.setBackground(press(error, 20)); break;
            case DANGER_TEXT: b.setTextColor(error); b.setBackground(press(0, 20)); break;
            default: b.setBackground(press(0, 20)); break;
        }
        b.setOnClickListener(click);
        return b;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(line);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    static void clipRound(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    static ColorDrawable solid(int color) { return new ColorDrawable(color); }

    static boolean animationsOn() {
        return android.animation.ValueAnimator.areAnimatorsEnabled();
    }
}

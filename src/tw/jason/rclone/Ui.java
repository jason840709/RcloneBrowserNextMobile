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
    static int splitBar, onSplitBar, folderBg, folderFg;
    static boolean dark;
    static String accent = Settings.PURPLE;
    static Typeface regular, medium, bold;

    /** Material 3 easing: standard for small changes, emphasized for things entering, accelerate for exits. */
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final TimeInterpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    /*
     * Accent families follow the desktop Rclone Browser Next themes (purple, blue, green; light and
     * dark). Order: surface, surfaceLow, surfaceContainer, surfaceHigh, surfaceHighest, ink, muted,
     * outline, line, primary, onPrimary, primaryContainer, onPrimaryContainer, activeHeader,
     * splitBar, onSplitBar, folderBg, folderFg, inversePrimary.
     */
    private static final int[] PURPLE_LIGHT = {0xFFFDFBFE, 0xFFF7F3F9, 0xFFF2EDF5, 0xFFECE6F0, 0xFFE6E0E9, 0xFF1D1B20, 0xFF5B5661,
            0xFF7A7480, 0xFFE3DDE7, 0xFF76558F, 0xFFFFFFFF, 0xFFEFE3F8, 0xFF3F2554, 0xFFF4ECF9, 0xFF76558F, 0xFFFFFFFF, 0xFFEFE5F7, 0xFF7B5A96, 0xFFD9BCF0};
    private static final int[] PURPLE_DARK = {0xFF141218, 0xFF1A181E, 0xFF211F26, 0xFF2B2930, 0xFF36343B, 0xFFE6E0E9, 0xFFCAC4D0,
            0xFF938F99, 0xFF36323B, 0xFFC4A6DE, 0xFF2B1D38, 0xFF45325A, 0xFFF0DBFF, 0xFF211A28, 0xFF45325A, 0xFFF0DBFF, 0xFF382C45, 0xFFD5BCEB, 0xFF76558F};
    private static final int[] BLUE_LIGHT = {0xFFFBFCFE, 0xFFF3F6FA, 0xFFEDF1F6, 0xFFE6EBF1, 0xFFE0E5EC, 0xFF1A1C1F, 0xFF55606B,
            0xFF727B85, 0xFFDDE3EA, 0xFF265C99, 0xFFFFFFFF, 0xFFDCE8F6, 0xFF1C3F66, 0xFFEBF2FA, 0xFF265C99, 0xFFFFFFFF, 0xFFE1EBF6, 0xFF4F769F, 0xFFA5C4EC};
    private static final int[] BLUE_DARK = {0xFF111418, 0xFF171A1F, 0xFF1D2127, 0xFF272B31, 0xFF32363C, 0xFFE0E3E8, 0xFFC2C7CE,
            0xFF8C929A, 0xFF30353C, 0xFFA5C4EC, 0xFF1E2C40, 0xFF304661, 0xFFD5E5F8, 0xFF18202A, 0xFF304661, 0xFFD5E5F8, 0xFF263445, 0xFF9FBDE3, 0xFF265C99};
    private static final int[] GREEN_LIGHT = {0xFFFBFDFB, 0xFFF3F7F4, 0xFFEDF2EE, 0xFFE6ECE7, 0xFFE0E6E1, 0xFF1A1D1B, 0xFF56625A,
            0xFF737D76, 0xFFDDE5DF, 0xFF366249, 0xFFFFFFFF, 0xFFE0ECE4, 0xFF2A4A37, 0xFFEBF3EE, 0xFF366249, 0xFFFFFFFF, 0xFFE3EDE6, 0xFF577B64, 0xFFA8C8B1};
    private static final int[] GREEN_DARK = {0xFF111512, 0xFF171B18, 0xFF1D221E, 0xFF272C28, 0xFF323733, 0xFFE0E4E1, 0xFFC2C9C4,
            0xFF8C948E, 0xFF303631, 0xFFA8C8B1, 0xFF1F2D24, 0xFF2E4235, 0xFFD3E5D8, 0xFF18211B, 0xFF2E4235, 0xFFD3E5D8, 0xFF26352B, 0xFFA3BFAA, 0xFF366249};

    static int[] palette(String accent, boolean dark) {
        if (Settings.BLUE.equals(accent)) return dark ? BLUE_DARK : BLUE_LIGHT;
        if (Settings.GREEN.equals(accent)) return dark ? GREEN_DARK : GREEN_LIGHT;
        return dark ? PURPLE_DARK : PURPLE_LIGHT;
    }

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        accent = Settings.accent(c);
        int[] p = palette(accent, dark);
        surface = p[0]; surfaceLow = p[1]; surfaceContainer = p[2]; surfaceHigh = p[3]; surfaceHighest = p[4];
        ink = p[5]; muted = p[6]; outline = p[7]; line = p[8];
        primary = p[9]; onPrimary = p[10]; primaryContainer = p[11]; onPrimaryContainer = p[12]; activeHeader = p[13];
        splitBar = p[14]; onSplitBar = p[15]; folderBg = p[16]; folderFg = p[17]; inversePrimary = p[18];
        error = c.getColor(R.color.error);
        onError = c.getColor(R.color.on_error);
        errorContainer = c.getColor(R.color.error_container);
        onErrorContainer = c.getColor(R.color.on_error_container);
        success = c.getColor(R.color.success);
        successContainer = c.getColor(R.color.success_container);
        inverseSurface = c.getColor(R.color.inverse_surface);
        inverseInk = c.getColor(R.color.inverse_on_surface);
        ripple = c.getColor(R.color.ripple);
        scrim = c.getColor(R.color.scrim);
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

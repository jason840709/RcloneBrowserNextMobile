package tw.jason.rclone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One row of the file list: a coloured type tile (or thumbnail), the name and a short detail line. */
final class FileRow extends LinearLayout {

    // ---- File kinds ---------------------------------------------------------------------------

    enum Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOC, PDF, ARCHIVE, APK, CODE, FILE }

    static Kind kind(FileOps.Entry e) {
        if (e.dir) return Kind.FOLDER;
        String n = e.name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "heic": case "heif": case "bmp":
            case "svg": case "tif": case "tiff": case "dng": case "raw": case "avif": return Kind.IMAGE;
            case "mp4": case "mkv": case "mov": case "avi": case "webm": case "m4v": case "3gp": case "ts": case "wmv": case "flv": return Kind.VIDEO;
            case "mp3": case "flac": case "wav": case "aac": case "m4a": case "ogg": case "opus": case "wma": case "ape": return Kind.AUDIO;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "tgz": case "bz2": case "xz": case "zst": return Kind.ARCHIVE;
            case "pdf": return Kind.PDF;
            case "doc": case "docx": case "odt": case "rtf": case "txt": case "md": case "pages": case "xls": case "xlsx":
            case "csv": case "ods": case "numbers": case "ppt": case "pptx": case "key": case "odp": case "epub": return Kind.DOC;
            case "apk": case "aab": case "xapk": case "apks": return Kind.APK;
            case "java": case "kt": case "py": case "js": case "ts2": case "html": case "htm": case "css": case "json": case "xml":
            case "yml": case "yaml": case "sh": case "c": case "cpp": case "h": case "go": case "rs": case "swift": case "conf":
            case "ini": case "toml": case "log": case "sql": return Kind.CODE;
            default: return Kind.FILE;
        }
    }

    static int glyph(Kind k) {
        switch (k) {
            case FOLDER: return R.drawable.ic_folder;
            case IMAGE: return R.drawable.ic_image;
            case VIDEO: return R.drawable.ic_video;
            case AUDIO: return R.drawable.ic_audio;
            case DOC: case PDF: return R.drawable.ic_doc;
            case ARCHIVE: return R.drawable.ic_archive;
            case APK: return R.drawable.ic_apk;
            case CODE: return R.drawable.ic_code;
            default: return R.drawable.ic_file;
        }
    }

    static int[] tile(Context c, Kind k) {
        int bg, fg;
        switch (k) {
            case FOLDER: bg = R.color.tile_folder_bg; fg = R.color.tile_folder_fg; break;
            case IMAGE: bg = R.color.tile_image_bg; fg = R.color.tile_image_fg; break;
            case VIDEO: bg = R.color.tile_video_bg; fg = R.color.tile_video_fg; break;
            case AUDIO: bg = R.color.tile_audio_bg; fg = R.color.tile_audio_fg; break;
            case DOC: bg = R.color.tile_doc_bg; fg = R.color.tile_doc_fg; break;
            case PDF: bg = R.color.tile_pdf_bg; fg = R.color.tile_pdf_fg; break;
            case ARCHIVE: bg = R.color.tile_archive_bg; fg = R.color.tile_archive_fg; break;
            case APK: bg = R.color.tile_apk_bg; fg = R.color.tile_apk_fg; break;
            case CODE: bg = R.color.tile_code_bg; fg = R.color.tile_code_fg; break;
            default: bg = R.color.tile_file_bg; fg = R.color.tile_file_fg; break;
        }
        return new int[]{c.getColor(bg), c.getColor(fg)};
    }

    static String kindName(FileOps.Entry e) {
        switch (kind(e)) {
            case FOLDER: return "資料夾";
            case IMAGE: return "圖片";
            case VIDEO: return "影片";
            case AUDIO: return "音訊";
            case PDF: return "PDF 文件";
            case DOC: return "文件";
            case ARCHIVE: return "壓縮檔";
            case APK: return "Android 安裝檔";
            case CODE: return "文字檔";
            default: return "檔案";
        }
    }

    // ---- Dates --------------------------------------------------------------------------------

    static String when(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        boolean sameYear = now.get(Calendar.YEAR) == t.get(Calendar.YEAR);
        int dayDiff = now.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR);
        Date d = new Date(ms);
        if (sameYear && dayDiff == 0) return "今天 " + new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(d);
        if (sameYear && dayDiff == 1) return "昨天 " + new SimpleDateFormat("HH:mm", Locale.TAIWAN).format(d);
        if (sameYear) return new SimpleDateFormat("M月d日", Locale.TAIWAN).format(d);
        return new SimpleDateFormat("yyyy/M/d", Locale.TAIWAN).format(d);
    }

    static String fullDate(long ms) {
        return ms <= 0 ? "不明" : new SimpleDateFormat("yyyy/M/d HH:mm", Locale.TAIWAN).format(new Date(ms));
    }

    // ---- Thumbnails ---------------------------------------------------------------------------

    static final class Thumbs {
        private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "thumbs");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final java.util.Set<String> FAILED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        static boolean eligible(FileOps.Entry e) {
            if (e.dir || !FileOps.isLocal(e.path)) return false;
            Kind k = kind(e);
            return k == Kind.IMAGE || (k == Kind.VIDEO && Build.VERSION.SDK_INT >= 29);
        }

        static Bitmap cached(String path) { return CACHE.get(path); }

        static void load(final FileOps.Entry e, final int px, final ImageView into) {
            final String key = e.path;
            if (FAILED.contains(key)) return;
            into.setTag(key);
            POOL.execute(() -> {
                Bitmap b = null;
                try {
                    File f = new File(e.path);
                    if (Build.VERSION.SDK_INT >= 29) {
                        Size s = new Size(px, px);
                        b = kind(e) == Kind.VIDEO ? ThumbnailUtils.createVideoThumbnail(f, s, null) : ThumbnailUtils.createImageThumbnail(f, s, null);
                    } else {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(e.path, o);
                        int sample = 1;
                        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        b = BitmapFactory.decodeFile(e.path, o);
                    }
                } catch (Throwable ignored) {}
                final Bitmap result = b;
                if (result == null) { FAILED.add(key); return; }
                CACHE.put(key, result);
                MAIN.post(() -> {
                    if (!key.equals(into.getTag())) return;
                    into.setImageBitmap(result);
                    into.setVisibility(VISIBLE);
                    into.setAlpha(0f);
                    into.animate().alpha(1f).setDuration(160).start();
                });
            });
        }
    }

    // ---- The row view -------------------------------------------------------------------------

    final FrameLayout tile;
    final ImageView glyph, thumb, check;
    final TextView name, meta;
    String boundPath;
    boolean boundSelected;

    FileRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Ui.dp(60));
        setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(16), Ui.dp(6));

        tile = new FrameLayout(c);
        glyph = new ImageView(c);
        glyph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        tile.addView(glyph, Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setVisibility(GONE);
        tile.addView(thumb, Ui.frame(-1, -1, Gravity.CENTER));
        check = Ui.icon(c, R.drawable.ic_check, Ui.onPrimary);
        check.setBackground(Ui.round(Ui.primary, 12));
        check.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        check.setVisibility(GONE);
        tile.addView(check, Ui.frame(-1, -1, Gravity.CENTER));
        Ui.clipRound(tile, 12);
        LayoutParams tp = new LayoutParams(Ui.dp(40), Ui.dp(40));
        tp.rightMargin = Ui.dp(16);
        addView(tile, tp);

        LinearLayout texts = Ui.column(c);
        name = Ui.oneLine(Ui.text(c, "", 15.5f, Ui.ink), TextUtils.TruncateAt.MIDDLE);
        meta = Ui.oneLine(Ui.text(c, "", 12.5f, Ui.muted), TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(meta);
        addView(texts, Ui.weight(1));
    }

    void bind(FileOps.Entry e, boolean selected, boolean selecting) {
        boolean samePath = e.path.equals(boundPath);
        boolean animateCheck = samePath && selected != boundSelected && Ui.animationsOn();
        boundPath = e.path;
        boundSelected = selected;

        Kind k = kind(e);
        int[] colors = tile(getContext(), k);
        tile.setBackground(Ui.round(colors[0], 12));
        glyph.setImageResource(glyph(k));
        glyph.setImageTintList(android.content.res.ColorStateList.valueOf(colors[1]));

        if (!samePath) {
            thumb.setTag(null);
            thumb.setImageDrawable(null);
            thumb.setVisibility(GONE);
            if (Thumbs.eligible(e)) {
                Bitmap b = Thumbs.cached(e.path);
                if (b != null) { thumb.setImageBitmap(b); thumb.setVisibility(VISIBLE); thumb.setAlpha(1f); }
                else Thumbs.load(e, Ui.dp(48), thumb);
            }
        }

        name.setText(e.name);
        String detail = e.dir ? when(e.time) : FileOps.size(e.size) + (e.time > 0 ? "  ·  " + when(e.time) : "");
        meta.setText(detail);
        meta.setVisibility(detail.isEmpty() ? GONE : VISIBLE);

        setBackground(Ui.press(selected ? Ui.primaryContainer : 0, 16));
        setActivated(selected);
        setContentDescription(e.name + "，" + (e.dir ? "資料夾" : kindName(e)) + (detail.isEmpty() ? "" : "，" + detail) + (selected ? "，已選取" : ""));

        if (animateCheck) {
            check.animate().cancel();
            if (selected) {
                check.setVisibility(VISIBLE);
                check.setScaleX(.6f); check.setScaleY(.6f); check.setAlpha(0f);
                check.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).setInterpolator(Ui.ENTER).withEndAction(null).start();
            } else {
                check.animate().scaleX(.6f).scaleY(.6f).alpha(0f).setDuration(120).setInterpolator(Ui.EXIT)
                        .withEndAction(() -> { if (!boundSelected) check.setVisibility(GONE); }).start();
            }
        } else {
            check.animate().cancel();
            check.setVisibility(selected ? VISIBLE : GONE);
            check.setScaleX(1f); check.setScaleY(1f); check.setAlpha(1f);
        }
    }

    /** Rows sit inside the list with side margins so the selected highlight reads as a rounded card. */
    static View wrap(FileRow row) {
        FrameLayout f = new FrameLayout(row.getContext());
        f.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        // The list presses the wrapper; the row mirrors that state so its ripple starts on touch-down.
        row.setDuplicateParentStateEnabled(true);
        f.addView(row, new FrameLayout.LayoutParams(-1, -2));
        f.setTag(row);
        return f;
    }
}

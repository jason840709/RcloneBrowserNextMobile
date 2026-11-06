package tw.jason.rclone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Read-only access for other apps to files on a memory card or USB drive. Internal storage keeps
 * using LocalProvider, which is unchanged; this provider only serves removable volumes.
 */
public class VolumeProvider extends ContentProvider {
    static final String AUTHORITY = "tw.jason.rclone.volumes";

    @Override public boolean onCreate() { return true; }

    private File file(Uri u) throws FileNotFoundException {
        try {
            File f = new File(u.getPath()).getCanonicalFile();
            for (Storage.Volume v : Storage.volumes(getContext())) {
                if (v.kind == Storage.INTERNAL) continue;
                String root = new File(v.root).getCanonicalPath();
                if (f.getPath().startsWith(root + "/")) return f;
            }
            throw new IOException("outside removable storage");
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri u) {
        String e = android.webkit.MimeTypeMap.getFileExtensionFromUrl(u.toString());
        String t = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.toLowerCase());
        return t == null ? "application/octet-stream" : t;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
        try {
            File f = file(u);
            String[] cols = p == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : p;
            MatrixCursor c = new MatrixCursor(cols);
            Object[] v = new Object[cols.length];
            for (int i = 0; i < cols.length; i++)
                v[i] = cols[i].equals(OpenableColumns.SIZE) ? f.length() : cols[i].equals(OpenableColumns.DISPLAY_NAME) ? f.getName() : null;
            c.addRow(v);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}

package tw.jason.rclone;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.ParcelFileDescriptor;import android.provider.OpenableColumns;import java.io.*;
public class LocalProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private File file(Uri u)throws FileNotFoundException{try{File f=new File(u.getPath()).getCanonicalFile();String root=android.os.Environment.getExternalStorageDirectory().getCanonicalPath();if(!f.getPath().startsWith(root+"/"))throw new IOException("outside shared storage");return f;}catch(IOException e){throw new FileNotFoundException();}}
 public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
 public String getType(Uri u){String e=android.webkit.MimeTypeMap.getFileExtensionFromUrl(u.toString());String t=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.toLowerCase());return t==null?"application/octet-stream":t;}
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){try{File f=file(u);String[] cols=p==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:p;MatrixCursor c=new MatrixCursor(cols);Object[] v=new Object[cols.length];for(int i=0;i<cols.length;i++)v[i]=cols[i].equals(OpenableColumns.SIZE)?f.length():cols[i].equals(OpenableColumns.DISPLAY_NAME)?f.getName():null;c.addRow(v);return c;}catch(Exception e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();} public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}

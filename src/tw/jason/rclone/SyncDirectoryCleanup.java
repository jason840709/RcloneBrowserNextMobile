package tw.jason.rclone;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

 
final class SyncDirectoryCleanup {
    static final class Result {
        final ArrayList<String> removed = new ArrayList<>();
        long checked, kept;
        String problem = "";
    }

    static Result run(Context context, String source, String destination, Set<String> knownSourceDirs,
                      BooleanSupplier stopped) {
        Result result = new Result();
        try {
            boolean local = FileOps.isLocal(destination);
            TreeSet<String> directories = local ? TransferService.localDirs(new File(destination)) : remoteDirs(context, destination);
            if (directories == null) { result.problem = "無法完整讀取目的地的資料夾，這次沒有完成空資料夾清理。"; return result; }
             
            for (String path : directories) if (!safeRelative(path)) throw new IOException("unsafe directory path");
            ArrayList<String> candidates = new ArrayList<>();
            for (String path : directories) if (!knownSourceDirs.contains(path)) candidates.add(path);
            candidates.sort((a,b) -> { int n = depth(b)-depth(a); return n != 0 ? n : a.compareTo(b); });
            SourceIndex index = new SourceIndex(context, source);
            LocalRoot root = local ? new LocalRoot(context, destination) : null;
            boolean cardFilters = !Settings.cardNameFilters(destination).isEmpty();
            for (String path : candidates) {
                if (stopped.getAsBoolean()) break;
                result.checked++;
                 
                boolean excluded = false;
                if (cardFilters) for (String part : path.split("/")) if (Settings.cardRefuses(part)) excluded = true;
                if (excluded) { result.kept++; continue; }
                LocalDirectory empty = root == null ? null : root.empty(path);
                if (root != null && empty == null) { result.kept++; continue; }
                if (!index.missing(path) || stopped.getAsBoolean()) { result.kept++; continue; }
                boolean removed;
                if (root != null) removed = root.remove(path, empty);
                else removed = Rclone.run(context, 60, "rmdir", "--retries", "1", "--low-level-retries", "1",
                        "--contimeout", Settings.connectTimeout(context), "--timeout", Settings.idleTimeout(context),
                        "--", FileOps.join(destination, path)).code == 0;
                if (removed) result.removed.add(path); else result.kept++;
            }
        } catch (Exception ignored) {
            result.problem = "空資料夾清理沒有完成，未確認的資料夾已保留。請檢查連線與儲存權限後重跑同步。";
        }
        return result;
    }

    private static boolean safeRelative(String path) {
        if (path.isEmpty() || path.indexOf('\0') >= 0) return false;
        String[] parts = path.split("/", -1);
        if (parts.length > 1024) return false;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private static int depth(String path) { int n=0; for (int i=0;i<path.length();i++) if(path.charAt(i)=='/') n++; return n; }

    private static TreeSet<String> remoteDirs(Context context, String destination) throws Exception {
        Rclone.Result listed = Rclone.run(context, 900, "lsjson", "--recursive", "--dirs-only", "--no-modtime", "--no-mimetype",
                "--retries", "1", "--low-level-retries", "1", "--contimeout", Settings.connectTimeout(context),
                "--timeout", Settings.idleTimeout(context), "--", destination);
        if (listed.code != 0) return null;
        TreeSet<String> paths = new TreeSet<>();
        JSONArray entries = new JSONArray(listed.out);
        for (int i=0;i<entries.length();i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!entry.getBoolean("IsDir")) throw new IOException("unexpected listing entry");
            paths.add(entry.getString("Path"));
        }
        return paths;
    }

     
    private static final class SourceIndex {
        final Context context;
        final String source;
        final Map<String,Map<String,Boolean>> parents = new LinkedHashMap<String,Map<String,Boolean>>(16,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Map<String,Boolean>> e) { return size()>32; }
        };
        SourceIndex(Context c, String s) { context=c; source=s; }
        Map<String,Boolean> children(String parent) {
            if (parents.containsKey(parent)) return parents.get(parent);
            Map<String,Boolean> names = new HashMap<>();
            try {
                if (FileOps.isLocal(source)) {
                    File current=new File(source).getCanonicalFile();
                    if (!parent.isEmpty()) for (String part:parent.split("/")) {
                        current=new File(current,part);
                        if (!OsConstants.S_ISDIR(Os.lstat(current.toString()).st_mode)) throw new IOException("source directory link");
                    }
                }
                for (FileOps.Entry entry : FileOps.list(context, parent.isEmpty() ? source : FileOps.join(source,parent))) {
                    if (!safeRelative(entry.name) || entry.name.indexOf('/')>=0 || names.containsKey(entry.name)) throw new IOException("invalid source entry");
                    names.put(entry.name, entry.dir);
                }
            } catch (Exception ignored) { names=null; }
            parents.put(parent,names);
            return names;
        }
        boolean missing(String path) {
            String parent="";
            String[] parts=path.split("/");
            for (int i=0;i<parts.length;i++) {
                Map<String,Boolean> names=children(parent);
                if (names==null) return false;
                String part=parts[i];
                if (!names.containsKey(part)) {
                     
                    String normalized=Normalizer.normalize(part,Normalizer.Form.NFC);
                    for (String name:names.keySet()) if (Normalizer.normalize(name,Normalizer.Form.NFC).equalsIgnoreCase(normalized)) return false;
                    return true;
                }
                if (i==parts.length-1 || !names.get(part)) return false;
                parent=parent.isEmpty()?part:parent+"/"+part;
            }
            return false;
        }
    }

    private static final class LocalDirectory {
        final long device, inode;
        final Object fileKey;
        LocalDirectory(StructStat stat, Object key) { device=stat.st_dev; inode=stat.st_ino; fileKey=key; }
        boolean matches(StructStat stat) { return device==stat.st_dev && inode==stat.st_ino && OsConstants.S_ISDIR(stat.st_mode); }
    }
    private static final class LocalRoot {
        final Context context;
        final File original, root;
        final LocalDirectory identity;
        LocalRoot(Context c, String path) throws Exception {
            context=c;
            original=new File(path); root=original.getCanonicalFile();
            StructStat stat=Os.lstat(root.toString());
            if (!OsConstants.S_ISDIR(stat.st_mode)) throw new IOException("destination is not a directory");
            identity=new LocalDirectory(stat, Files.readAttributes(root.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey());
        }
        File validated(String relative) throws Exception {
            if (!original.getCanonicalFile().equals(root) || !identity.matches(Os.lstat(root.toString()))) throw new IOException("destination changed");
            File current=root;
            for (String part:relative.split("/")) {
                current=new File(current,part);
                StructStat stat=Os.lstat(current.toString());
                if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_dev!=identity.device) throw new IOException("directory link or other volume");
            }
            return current;
        }
        LocalDirectory empty(String path) {
            try {
                File directory=validated(path);
                String[] children=directory.list();
                return children!=null && children.length==0 ? new LocalDirectory(Os.lstat(directory.toString()),
                        Files.readAttributes(directory.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()) : null;
            } catch (Exception ignored) { return null; }
        }
        boolean remove(String path, LocalDirectory expected) {
            ArrayList<DirectoryStream<Path>> opened=new ArrayList<>();
            try {
                File directory=validated(path);
                if (!expected.matches(Os.lstat(directory.toString()))) return false;
                DirectoryStream<Path> stream=Files.newDirectoryStream(root.toPath());
                opened.add(stream);
                if (!(stream instanceof SecureDirectoryStream) || expected.fileKey==null || identity.fileKey==null) {
                     
                     
                    return Rclone.run(context,60,"rmdir","--local-encoding","Raw","--retries","1","--low-level-retries","1",
                            "--",directory.toString()).code==0;
                }
                SecureDirectoryStream<Path> parent=(SecureDirectoryStream<Path>)stream;
                if (!identity.fileKey.equals(parent.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey())) return false;
                String[] parts=path.split("/");
                for (int i=0;i<parts.length-1;i++) {
                    parent=parent.newDirectoryStream(Paths.get(parts[i]),LinkOption.NOFOLLOW_LINKS);
                    opened.add(parent);
                }
                Path leaf=Paths.get(parts[parts.length-1]);
                BasicFileAttributes current=parent.getFileAttributeView(leaf,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!current.isDirectory() || !expected.fileKey.equals(current.fileKey())) return false;
                 
                parent.deleteDirectory(leaf);
                return true;
            } catch (Exception ignored) { return false; }
            finally { for (int i=opened.size()-1;i>=0;i--) try { opened.get(i).close(); } catch (IOException ignored) {} }
        }
    }
}

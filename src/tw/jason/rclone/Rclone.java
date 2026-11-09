package tw.jason.rclone;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Executes the packaged Android rclone executable without an intermediate shell. */
public final class Rclone {
    private Rclone() {}
    public static final class Result {
        public final int code;
        public final String out, err;
        public Result(int code, String out, String err) { this.code=code; this.out=out; this.err=err; }
    }
    public static File config(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "rclone.conf");
        synchronized (Rclone.class) {
            if (!file.exists() && !file.createNewFile()) throw new IOException("無法建立連線設定");
            try { android.system.Os.chmod(file.getAbsolutePath(), 0600); }
            catch (android.system.ErrnoException e) { throw new IOException("無法保護連線設定", e); }
        }
        return file;
    }
    private static ProcessBuilder builder(Context c, List<String> args, boolean merged) throws IOException {
        if (args.isEmpty()) throw new IOException("缺少操作");
        File executable = new File(c.getApplicationInfo().nativeLibraryDir, "librclone.so");
        if (!executable.isFile() || !executable.canExecute()) throw new IOException("找不到傳輸引擎，請重新安裝 App");
        ArrayList<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("--config"); command.add(config(c).getAbsolutePath());
        command.add("--cache-dir"); command.add(new File(c.getCacheDir(), "rclone").getAbsolutePath());
        for (String arg : args) {
            if (arg == null || arg.indexOf('\0') >= 0) throw new IOException("操作參數無效");
            command.add(arg);
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(merged);
        builder.directory(c.getFilesDir());
        builder.environment().put("HOME", c.getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());
        return builder;
    }
    /** Service callers must consume the combined output and await process completion. */
    public static Process start(Context c, List<String> args) throws IOException {
        return builder(c, args, true).start();
    }
    /** Short commands: 90 seconds at most, as up to 0.5.0. */
    public static Result run(Context c, String... args) { return run(c, 90, args); }

    /**
     * Runs a command and waits up to {@code seconds}. rclone's own connect and idle timeouts still end it
     * sooner when the network stops answering; this limit only catches commands that keep going too long.
     */
    public static Result run(Context c, long seconds, String... args) {
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            process = builder(c, Arrays.asList(args), false).start();
            final Process owned = process;
            Future<String> stdout = readers.submit(() -> read(owned.getInputStream()));
            Future<String> stderr = readers.submit(() -> read(owned.getErrorStream()));
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // Not a network timeout: the command was still working. Keep the two apart for the user.
                return new Result(124, "", "TOO_LONG");
            }
            return new Result(process.exitValue(), stdout.get(10, TimeUnit.SECONDS), stderr.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "", "操作已取消");
        } catch (Exception e) {
            // Never print command lines or environment: config creation can contain passwords.
            return new Result(1, "", e instanceof IOException ? e.getMessage() : "無法完成操作");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            readers.shutdownNow();
        }
    }
    private static String read(InputStream in) throws IOException {
        final int limit = 16 * 1024 * 1024;
        try (InputStream input=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            boolean overflow = false;
            while ((count=input.read(buffer)) != -1) {
                if (out.size()+count <= limit) out.write(buffer, 0, count); else overflow = true;
            }
            if (overflow) throw new IOException("資料夾項目過多，請開啟較小的子資料夾");
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    public static String validateTransfer(String src, String dst) {
        try {
            String a=normalized(src), b=normalized(dst);
            if (a.equals(b)) return "來源與目的地相同";
            if (b.startsWith(a.endsWith(":") || a.endsWith("/") ? a : a+"/")) return "目的地不能位於來源資料夾內";
            if (a.startsWith(b.endsWith(":") || b.endsWith("/") ? b : b+"/")) return "來源與目的地不能互相包含";
            return null;
        } catch (Exception e) { return "檔案位置無效"; }
    }
    private static String normalized(String path) throws IOException {
        if (path==null || path.isEmpty() || path.indexOf('\0')>=0 || path.startsWith("-")) throw new IOException();
        if (path.startsWith("/")) return new File(path).getCanonicalPath();
        int colon=path.indexOf(':');
        if (colon<1) throw new IOException();
        String name=path.substring(0,colon+1);
        String tail=path.substring(colon+1).replace('\\','/');
        ArrayDeque<String> parts=new ArrayDeque<>();
        for(String part:tail.split("/")) {
            if(part.isEmpty() || part.equals(".")) continue;
            if(part.equals("..")) { if(parts.isEmpty()) throw new IOException(); parts.removeLast(); }
            else parts.addLast(part);
        }
        return name+String.join("/",parts);
    }
}

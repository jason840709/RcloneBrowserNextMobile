package tw.jason.rclone;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A process-owning queue; Activity recreation does not cancel transfers. */
public final class TransferService extends Service {
    private static final Object LOCK=new Object();
    private static final ArrayDeque<Job> QUEUE=new ArrayDeque<>();
    private static final ArrayList<JSONObject> HISTORY=new ArrayList<>();
    private static volatile String status="沒有進行中的傳輸";
    /** rclone's latest statistics for the running job (null until the first report), shown in the transfer sheet. */
    private static volatile JSONObject liveStats;
    private static Job active;
    private static java.lang.Process process;
    private static TransferService instance;
    private static boolean historyLoaded;
    private static Context appContext;
    private static final String CHANNEL="transfers", CANCEL="tw.jason.rclone.CANCEL";
    private static final int NOTICE=41;
    private boolean workerRunning;
    private volatile boolean destroyed;
    private int latestStartId;
    private Job ownedJob;
    private PowerManager.WakeLock wakeLock;
    private long lastNotice;
    /** Record kinds. "fill" is the folder copy that the sync sheet calls "only fill in missing files". */
    public static final String COPY="copy", MOVE="move", MIRROR="mirror", FILL="fill";
    /** Finished records kept; waiting and running ones are never trimmed. */
    private static final int HISTORY_LIMIT=50;
    private static final class Job {
        final String id=UUID.randomUUID().toString(),src,dst,kind;
        final boolean directory,move,sync;
        final long created=System.currentTimeMillis();
        /** Wall-clock start and end; 0 until then. */
        volatile long started, ended;
        volatile boolean cancelled;
        /** What rclone did, file by file (0.7.0). */
        final TransferReport report=new TransferReport();
        /** Settings this job runs with, fixed when it starts; -1 until then. */
        int compare=-1, transfers, checkers;
        /** Set when the destination did not keep a copied file's modification time (see timeCheck). */
        JSONObject timeNotKept;
        Job(String s,String d,boolean dir,boolean m,boolean sy,String k) {src=s;dst=d;directory=dir;move=m;sync=sy;kind=k;}
        /** Last path part; at the top of a connection ("nas:") the connection name, which used to come out empty. */
        String name() {
            String s=src.replaceAll("/+$",""); int i=Math.max(s.lastIndexOf('/'),s.lastIndexOf(':'));
            String n=s.substring(i+1);
            if(n.isEmpty() && s.endsWith(":")) n=s.substring(0,s.length()-1);
            return n.isEmpty()?src:n;
        }
        String verb() { return sync || FILL.equals(kind) ? "同步" : move ? "移動" : "複製"; }
    }
    public static void validateTransfer(String src,String dst,boolean directory) {
        String invalid=Rclone.validateTransfer(src,dst);
        if(invalid!=null) throw new IllegalArgumentException(invalid);
    }
    public static String enqueue(Context context,String src,String dst,boolean directory,boolean move,boolean sync) {
        return enqueue(context,src,dst,directory,move,sync,sync?MIRROR:move?MOVE:COPY);
    }
    /** kind only labels the record (see FILL); the rclone command still follows directory, move and sync. */
    public static String enqueue(Context context,String src,String dst,boolean directory,boolean move,boolean sync,String kind) {
        validateTransfer(src,dst,directory);
        if(sync && (!directory || move)) throw new IllegalArgumentException("同步需要選擇資料夾");
        Job job=new Job(src,dst,directory,move,sync,kind);
        synchronized(LOCK) {
            appContext=context.getApplicationContext();
            loadHistory(context);
            QUEUE.add(job); record(context,job,"等待中");
            if(active==null) status="等待傳輸 · "+job.name();
            try { context.startForegroundService(new Intent(context,TransferService.class)); }
            catch(RuntimeException e) {
                QUEUE.remove(job); record(context,job,"無法啟動"); status="無法啟動傳輸，請回到 App 再試一次";
                throw e;
            }
        }
        return job.id;
    }
    /** Cancels one job: a waiting one leaves the queue untouched otherwise; a running one stops its rclone process. */
    public static boolean cancel(String id) {
        synchronized(LOCK) {
            for(Iterator<Job> it=QUEUE.iterator();it.hasNext();) {
                Job job=it.next();
                if(!job.id.equals(id)) continue;
                it.remove(); job.cancelled=true;
                if(appContext!=null) record(appContext,job,"已取消");
                return true;
            }
            if(active!=null && active.id.equals(id)) {
                active.cancelled=true; status="正在取消 · "+active.name();
                if(process!=null) process.destroyForcibly();
                return true;
            }
            return false;
        }
    }
    /** Running and waiting jobs, running first: id, name, operation, kind, state, cancelling. */
    public static JSONArray queue() {
        JSONArray rows=new JSONArray();
        synchronized(LOCK) {
            try {
                if(active!=null) rows.put(describe(active).put("state","進行中").put("cancelling",active.cancelled));
                for(Job job:QUEUE) rows.put(describe(job).put("state","等待中"));
            } catch(JSONException ignored) {}
        }
        return rows;
    }
    private static JSONObject describe(Job job) throws JSONException {
        return new JSONObject().put("id",job.id).put("name",job.name()).put("operation",job.verb()).put("kind",job.kind)
            .put("src",job.src).put("dst",job.dst).put("directory",job.directory).put("created",job.created).put("started",job.started);
    }
    /**
     * Everything the detail page shows for one job: the record fields, "summary" (counts), "lists" (the
     * first {@link TransferReport#KEEP} paths of each group) and, while it runs, "stats" and "transferring".
     * Finished jobs read the details file; records from before 0.7.0 have none and return the record alone.
     */
    public static JSONObject details(Context c,String id) {
        synchronized(LOCK) {
            loadHistory(c);
            try {
                if(active!=null && active.id.equals(id)) {
                    JSONObject out=active.report.details();
                    JSONObject st=active.report.stats();
                    out.put("record",describe(active).put("state","進行中").put("cancelling",active.cancelled).put("compare",active.compare)
                        .put("transfers",active.transfers).put("checkers",active.checkers));
                    if(st!=null) out.put("stats",st);
                    out.put("transferring",active.report.transferring());
                    return out;
                }
                for(Job job:QUEUE) if(job.id.equals(id)) return new JSONObject().put("record",describe(job).put("state","等待中"));
                for(JSONObject row:HISTORY) if(row.optString("id").equals(id)) {
                    JSONObject out=null;
                    File f=detailsFile(c,id);
                    if(f.isFile()) try { out=new JSONObject(new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8)); } catch(Exception ignored) {}
                    if(out==null) out=new JSONObject();
                    out.put("record",new JSONObject(row.toString()));
                    if(!out.has("summary") && row.has("result")) out.put("summary",row.optJSONObject("result"));
                    return out;
                }
            } catch(JSONException ignored) {}
            return null;
        }
    }
    /** Counts of the running job, for its card in the list; null if it is not running. */
    public static JSONObject liveSummary(String id) {
        synchronized(LOCK) { return active!=null && active.id.equals(id) ? active.report.summary() : null; }
    }
    /** Deletes finished records (done, failed, cancelled, interrupted). Waiting and running ones stay. Returns the count removed. */
    public static int clearFinished(Context context) {
        synchronized(LOCK) {
            loadHistory(context);
            int before=HISTORY.size();
            for(Iterator<JSONObject> it=HISTORY.iterator();it.hasNext();) {
                JSONObject row=it.next();
                if(finished(row)) { it.remove(); detailsFile(context,row.optString("id")).delete(); }
            }
            save(context);
            return before-HISTORY.size();
        }
    }
    /** Deletes one finished record; waiting and running records cannot be removed this way. */
    public static boolean removeRecord(Context context,String id) {
        synchronized(LOCK) {
            loadHistory(context);
            for(Iterator<JSONObject> it=HISTORY.iterator();it.hasNext();) {
                JSONObject row=it.next();
                if(row.optString("id").equals(id) && finished(row)) { it.remove(); detailsFile(context,id).delete(); save(context); return true; }
            }
            return false;
        }
    }
    private static boolean finished(JSONObject row) {
        String state=row.optString("state");
        return !state.equals("等待中") && !state.equals("進行中");
    }
    public static void cancel() {
        synchronized(LOCK) {
            for(Job job:QUEUE) { job.cancelled=true; if(appContext!=null) record(appContext,job,"已取消"); }
            QUEUE.clear();
            if(active!=null) active.cancelled=true;
            if(process!=null) process.destroyForcibly();
            status="已取消";
        }
    }
    /** Latest statistics of the running job: bytes, totalBytes, transfers, totalTransfers, checks, listed, deletes, errors, elapsedTime, eta. */
    public static JSONObject liveStats() { return liveStats; }
    public static boolean busy() { synchronized(LOCK) { return active!=null || !QUEUE.isEmpty(); } }
    public static String status() { synchronized(LOCK) { return status+(QUEUE.isEmpty()?"":" · 等待 "+QUEUE.size()+" 項"); } }
    public static JSONArray history(Context context) {
        synchronized(LOCK) { loadHistory(context); return new JSONArray(HISTORY); }
    }
    @Override public void onCreate() {
        super.onCreate();
        synchronized(LOCK) { instance=this; loadHistory(this); }
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"檔案傳輸",NotificationManager.IMPORTANCE_LOW));
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"rclone:transfer");
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStartId=startId;
        if(Build.VERSION.SDK_INT>=29) startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTICE,notification());
        if(intent!=null && CANCEL.equals(intent.getAction())) cancel();
        synchronized(LOCK) {
            if(!workerRunning) {
                workerRunning=true;
                new Thread(this::work,"rclone-transfers").start();
            }
        }
        return START_NOT_STICKY;
    }
    private void work() {
        wakeLock.acquire(6*60*60*1000L);
        try {
            while(true) {
                Job job;
                synchronized(LOCK) {
                    if(destroyed) { workerRunning=false; break; }
                    job=QUEUE.poll();
                    if(job==null) { workerRunning=false; break; }
                    active=job; ownedJob=job; liveStats=null;
                    job.started=System.currentTimeMillis();
                    job.compare=Settings.compare(this); job.transfers=Settings.transfers(this); job.checkers=Settings.checkers(this);
                    status=job.verb()+"中 · "+job.name();
                    record(this,job,"進行中");
                }
                execute(job);
                synchronized(LOCK) { if(active==job) { active=null; process=null; liveStats=null; } ownedJob=null; }
            }
        } finally {
            if(wakeLock.isHeld()) wakeLock.release();
            // Serialize final stop with enqueue/start to avoid stopping a newly queued job.
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized(LOCK) {
                    if(instance==this && !workerRunning && QUEUE.isEmpty() && active==null) {
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId);
                    }
                }
            });
        }
    }
    private void execute(Job job) {
        java.lang.Process owned=null;
        long transferred=-1;
        try {
            // 0.7.4: a single file whose name a memory card cannot keep is not copied (the card would shorten the name).
            List<String> cardFilters=Settings.cardNameFilters(job.dst);
            if(!job.directory && !cardFilters.isEmpty() && Settings.cardRefuses(nameOf(job.dst))) {
                job.report.skipped(nameOf(job.src),Settings.CARD_REFUSES);
                synchronized(LOCK) {
                    job.ended=System.currentTimeMillis();
                    saveDetails(this,job);
                    status="記憶卡存不了這個名稱，沒有複製 · "+job.name();
                    record(this,job,"失敗（記憶卡存不了這個名稱）");
                }
                return;
            }
            ArrayList<String> args=new ArrayList<>();
            args.add(job.sync?"sync":job.move?(job.directory?"move":"moveto"):(job.directory?"copy":"copyto"));
            if(job.directory) args.add("--create-empty-src-dirs");
            if(job.directory && job.move) args.add("--delete-empty-src-dirs");
            if(!job.sync) args.add("--ignore-existing");
            else {
                args.add("--delete-after");
                // 0.7.0: how "make identical" decides that a file present on both sides needs copying again.
                args.addAll(compareArgs(job));
            }
            // Values come from the user's settings; the defaults equal the fixed values used up to 0.3.0
            // (4 transfers, 8 checkers, no bandwidth limit, 15 s connect and 60 s idle timeouts).
            // 0.7.0: INFO level ("-v") reports each file copied, replaced, moved or deleted, and "--combined -"
            // prints one line per file with the comparison result; TransferReport reads both.
            Collections.addAll(args,"--transfers",String.valueOf(job.transfers),"--checkers",String.valueOf(job.checkers),"--stats","1s","--stats-one-line","--use-json-log","--stats-log-level","NOTICE","-v","--combined","-","--retries","1","--low-level-retries","3","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this));
            int bandwidth=Settings.bandwidthMB(this);
            if(bandwidth>0) Collections.addAll(args,"--bwlimit",bandwidth+"M");
            if(job.directory && !cardFilters.isEmpty()) { args.addAll(cardFilters); job.report.cardNamesLeftOut(); }
            Collections.addAll(args,"--",job.src,job.dst);
            synchronized(LOCK) {
                if(job.cancelled || destroyed) {record(this,job,"已取消"); return;}
                owned=Rclone.start(this,args); process=owned;
            }
            updateNotice();
            long lastSaved=SystemClock.elapsedRealtime();
            // 0.7.2: for "make identical", every destination-only path goes to a list file, and the folders that
            // exist in the source are noted, so the deletions rclone skips after single-file errors can still be done.
            File onlyDstList=job.sync ? new File(getCacheDir(),"only-dst-"+job.id.replaceAll("[^A-Za-z0-9-]","")+".txt") : null;
            HashSet<String> sourceDirs=new HashSet<>();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.UTF_8));
                Writer listOut=onlyDstList==null ? null : new BufferedWriter(new OutputStreamWriter(new FileOutputStream(onlyDstList),StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(job.cancelled) { owned.destroyForcibly(); continue; }
                    if(listOut!=null && line.length()>2 && line.charAt(1)==' ') {
                        char k=line.charAt(0);
                        if(k=='-') { listOut.write(line,2,line.length()-2); listOut.write('\n'); }
                        else if(k=='=' || k=='+' || k=='*' || k=='!') for(String d=parentOf(line.substring(2));!d.isEmpty() && sourceDirs.add(d);d=parentOf(d)) {}
                    }
                    // Engine error text is only shown cleaned (see TransferReport); the notification never has it.
                    JSONObject before=job.report.stats();
                    job.report.read(line);
                    JSONObject stats=job.report.stats();
                    if(stats!=null && stats!=before) {
                        transferred=stats.optLong("transfers",transferred);
                        long bytes=stats.optLong("bytes"), total=stats.optLong("totalBytes");
                        double speed=stats.optDouble("speed",0);
                        liveStats=stats;
                        // Before anything needs copying (a large sync first lists and compares), say how far the comparison is.
                        if(total>0 || bytes>0) status=job.verb()+"中 · "+format(bytes)+(total>0?" / "+format(total):"")+" · "+format((long)speed)+"/秒";
                        else status=job.verb()+"中 · 已比對 "+String.format(Locale.US,"%,d",stats.optLong("checks"))+" 項";
                    }
                    long now=SystemClock.elapsedRealtime();
                    // Keep the counts on disk every 10 s, so a job the system stops still shows what it did.
                    if(now-lastSaved>10000) { lastSaved=now; synchronized(LOCK) { putResult(job); save(this); } }
                    updateNotice();
                }
            }
            int exit=owned.waitFor();
            if(exit!=0 && !job.cancelled && job.sync && job.directory && job.report.canCleanAnyway()) cleanUpAnyway(job,onlyDstList,sourceDirs);
            if(onlyDstList!=null) onlyDstList.delete();
            if(job.directory && !cardFilters.isEmpty() && !job.cancelled) listCardRefused(job);
            if(exit==0 && !job.cancelled) job.timeNotKept=timeCheck(job);
            synchronized(LOCK) {
                job.ended=System.currentTimeMillis();
                saveDetails(this,job);
                if(job.cancelled) status="已取消 · "+job.name();
                else if(exit==0) status=(transferred==0 && !job.sync?"處理完成，沒有新增檔案":job.verb()+"完成")+" · "+job.name()+(transferred>=0?" · 傳輸 "+transferred+" 個檔案":"");
                else status="傳輸失敗（"+exit+"）· "+job.name()+"；請檢查連線與目的地權限";
                record(this,job,job.cancelled?"已取消":exit==0?(transferred==0 && !job.sync?"完成，沒有新增檔案":"完成"):"失敗（"+exit+"）");
            }
        } catch(Exception e) {
            synchronized(LOCK) {
                job.ended=System.currentTimeMillis();
                saveDetails(this,job);
                status=job.cancelled?"已取消":"傳輸失敗，請檢查連線與儲存空間";
                record(this,job,job.cancelled?"已取消":"失敗");
            }
        } finally { if(owned!=null && owned.isAlive()) owned.destroyForcibly(); }
    }
    static String parentOf(String path) { int i=path.lastIndexOf('/'); return i<0 ? "" : path.substring(0,i); }

    static String nameOf(String path) {
        String p=path.endsWith("/") ? path.substring(0,path.length()-1) : path;
        int i=Math.max(p.lastIndexOf('/'),p.lastIndexOf(':'));
        return p.substring(i+1);
    }

    /**
     * 0.7.2, folders added in 0.7.4. rclone deletes nothing when any error happened, because a folder it could not
     * read would look empty and its files would be deleted by mistake. When every error was about a single file
     * (for example a name the memory card refuses), both sides were listed completely, so the clean-up rclone
     * skipped is done here:
     * 1. Destination-only files from that complete comparison are deleted with "rclone delete --files-from-raw".
     * 2. Folders only in the destination are removed, deepest first, if empty. rclone itself removes these after a
     *    run without errors, empty ones included; up to 0.7.3 only folders emptied by step 1 were removed, so
     *    folders that were already empty stayed.
     *    A destination folder is "only in the destination" when no source file is in it and a listing of its
     *    parent in the source does not show it either (the source can have empty folders).
     * Nothing is renamed.
     */
    private void cleanUpAnyway(Job job,File list,Set<String> sourceDirs) {
        java.lang.Process p=null;
        try {
            if(list!=null && list.isFile() && list.length()>0) {
                status="正在刪除目的地多出的檔案 · "+job.name();
                updateNotice();
                ArrayList<String> args=new ArrayList<>();
                Collections.addAll(args,"delete","--files-from-raw",list.getAbsolutePath(),"--use-json-log","-v","--stats","0","--retries","1","--low-level-retries","3","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this),"--",job.dst);
                synchronized(LOCK) { if(job.cancelled) return; p=Rclone.start(this,args); process=p; }
                try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while((line=r.readLine())!=null) { if(job.cancelled) p.destroyForcibly(); else job.report.read(line); }
                }
                p.waitFor();
            }
            if(job.cancelled) return;
            status="正在清理目的地多出的資料夾 · "+job.name();
            updateNotice();
            boolean local=FileOps.isLocal(job.dst);
            TreeSet<String> dstDirs=local ? localDirs(new File(job.dst)) : remoteDirs(job.dst);
            ArrayList<String> removed=new ArrayList<>();
            if(dstDirs==null) { job.report.cleanedAnyway(removed,0,0); return; }
            TreeSet<String> extra=new TreeSet<>();
            for(String d:dstDirs) if(!sourceDirs.contains(d)) extra.add(d);
            // The top-most of them, by parent: one listing of each parent in the source.
            TreeMap<String,ArrayList<String>> byParent=new TreeMap<>();
            for(String d:extra) if(!extra.contains(parentOf(d))) { String pa=parentOf(d); if(!byParent.containsKey(pa)) byParent.put(pa,new ArrayList<>()); byParent.get(pa).add(d); }
            HashSet<String> inSource=new HashSet<>();
            int calls=0;
            for(Map.Entry<String,ArrayList<String>> e:byParent.entrySet()) {
                if(job.cancelled) return;
                // Unknown (listing failed, or too many parents to ask): treated as present, so nothing there is removed.
                Set<String> names=calls++<200 ? sourceSubdirs(job.src,e.getKey()) : null;
                for(String d:e.getValue()) if(names==null || names.contains(d.substring(d.lastIndexOf('/')+1))) inSource.add(d);
            }
            TreeSet<String> deepestFirst=new TreeSet<>((a,b)->{ int da=depth(a),db=depth(b); return da!=db ? db-da : a.compareTo(b); });
            deepestFirst.addAll(extra);
            long kept=0;
            int rmdirs=0;
            for(String d:deepestFirst) {
                if(job.cancelled) break;
                boolean protectedDir=false;
                for(String a=d;!a.isEmpty();a=parentOf(a)) if(inSource.contains(a)) { protectedDir=true; break; }
                boolean gone=!protectedDir && (local ? new File(job.dst,d).delete() : rmdirs++<300 && Rclone.run(this,60,"rmdir","--",join(job.dst,d)).code==0);
                if(gone) removed.add(d); else kept++;
            }
            job.report.cleanedAnyway(removed,extra.size(),kept);
        } catch(Exception ignored) {
        } finally { if(p!=null && p.isAlive()) p.destroyForcibly(); }
    }
    private static int depth(String path) { int n=0; for(int i=0;i<path.length();i++) if(path.charAt(i)=='/') n++; return n; }

    /** Every folder under a local folder, as paths relative to it; symbolic links are not followed. */
    static TreeSet<String> localDirs(File root) {
        final TreeSet<String> out=new TreeSet<>();
        final java.nio.file.Path base=root.toPath();
        try {
            java.nio.file.Files.walkFileTree(base,new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path d,java.nio.file.attribute.BasicFileAttributes a) {
                    if(!d.equals(base)) out.add(base.relativize(d).toString());
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path f,IOException e) { return java.nio.file.FileVisitResult.CONTINUE; }
            });
        } catch(IOException e) { return null; }
        return out;
    }
    /** Every folder under a remote folder, from rclone; null if the listing was not complete. */
    private TreeSet<String> remoteDirs(String dst) {
        Rclone.Result r=Rclone.run(this,900,"lsf","-R","--dirs-only","--dir-slash=false","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this),"--",dst);
        if(r.code!=0) return null;
        TreeSet<String> out=new TreeSet<>();
        for(String line:r.out.split("\n")) if(!line.isEmpty()) out.add(line);
        return out;
    }
    /** Names of the folders directly in {@code parent} of the source; null if it could not be listed. */
    private Set<String> sourceSubdirs(String src,String parent) {
        Rclone.Result r=Rclone.run(this,120,"lsf","--max-depth","1","--dirs-only","--dir-slash=false","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this),"--",parent.isEmpty() ? src : join(src,parent));
        if(r.code!=0) return null;
        HashSet<String> out=new HashSet<>();
        for(String line:r.out.split("\n")) if(!line.isEmpty()) out.add(line);
        return out;
    }

    /**
     * 0.7.4. The names this transfer left out because a memory card cannot keep them (Settings.cardNameFilters):
     * rclone does not report filtered names, so the top level of the source is listed once (one folder listing,
     * cheap even on a NAS) and the names there that end in a period or space are listed. Deeper ones are left out
     * the same way; the detail page says they are not listed one by one.
     */
    private void listCardRefused(Job job) {
        Rclone.Result r=Rclone.run(this,120,"lsf","--max-depth","1","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this),"--",job.src);
        if(r.code!=0) return;
        for(String line:r.out.split("\n")) {
            String name=line.endsWith("/") ? line.substring(0,line.length()-1) : line;
            if(!name.isEmpty() && Settings.cardRefuses(name)) job.report.skipped(line,Settings.CARD_REFUSES);
        }
    }

    /** Comparison flags and time tolerance for "make identical" with the method fixed when the job started. */
    private List<String> compareArgs(Job job) {
        List<String> a=Settings.compareFlags(job.compare);
        String window=Settings.modifyWindow(job.src,job.dst);
        if(window!=null) { a.add("--modify-window"); a.add(window); }
        return a;
    }
    /**
     * After a folder sync that copied files: would the next "make identical" sync copy the first of them
     * again? A one-file dry run with the same comparison answers that. Yes means the destination did not
     * keep the modification time (or keeps it less precisely than the source), and every unchanged file
     * would be copied again each time; the detail page then suggests comparing by size.
     * Returns {path, src, dst} with both times, or null when the time was kept or the check could not run.
     */
    private JSONObject timeCheck(Job job) {
        if(!job.directory || !(job.sync || FILL.equals(job.kind))) return null;
        String path=job.report.firstCopied();
        return path==null ? null : timeNotKept(this,job.src,job.dst,path,job.compare);
    }
    /** Public for the tests: the check itself, for one file under two folders. */
    public static JSONObject timeNotKept(Context c,String src,String dst,String path,int compare) {
        String s=join(src,path), d=join(dst,path);
        ArrayList<String> args=new ArrayList<>();
        Collections.addAll(args,"copyto","--dry-run","--use-json-log","-v","--stats","0","--retries","1","--contimeout",Settings.connectTimeout(c),"--timeout",Settings.idleTimeout(c));
        args.addAll(Settings.compareFlags(compare));
        String window=Settings.modifyWindow(src,dst);
        if(window!=null) { args.add("--modify-window"); args.add(window); }
        Collections.addAll(args,"--",s,d);
        Rclone.Result r=Rclone.run(c,60,args.toArray(new String[0]));
        if(r.code!=0 || !r.err.contains("\"skipped\":\"copy\"")) return null;
        try {
            return new JSONObject().put("path",path).put("src",modTime(c,s)).put("dst",modTime(c,d)).put("compare",compare);
        } catch(JSONException e) { return null; }
    }
    private static String modTime(Context c,String path) {
        Rclone.Result r=Rclone.run(c,30,"lsjson","--stat","--",path);
        try { return r.code==0 ? new JSONObject(r.out).optString("ModTime") : ""; } catch(JSONException e) { return ""; }
    }
    static String join(String base,String rel) {
        return base.endsWith("/") || base.endsWith(":") ? base+rel : base+"/"+rel;
    }
    private static File detailsFile(Context c,String id) { return new File(new File(c.getFilesDir(),"transfer-details"),id.replaceAll("[^A-Za-z0-9-]","")+".json"); }
    /** File lists go to their own file, so the history stays small; the record keeps the counts. */
    private static void saveDetails(Context c,Job job) {
        File f=detailsFile(c,job.id);
        f.getParentFile().mkdirs();
        AtomicFile file=new AtomicFile(f); FileOutputStream out=null;
        try {
            JSONObject d=job.report.details();
            out=file.startWrite(); out.write(d.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);
        } catch(IOException e) { if(out!=null) file.failWrite(out); }
    }
    /** Counts, settings and times of a job into its history record (caller holds LOCK). */
    private static void putResult(Job job) {
        for(JSONObject row:HISTORY) if(row.optString("id").equals(job.id)) {
            try {
                if(job.started>0) row.put("started",job.started).put("result",job.report.summary()).put("transfers",job.transfers).put("checkers",job.checkers);
                if(job.sync) row.put("compare",job.compare);
                if(job.ended>0) row.put("ended",job.ended);
                if(job.timeNotKept!=null) row.put("timeNotKept",job.timeNotKept);
            } catch(JSONException ignored) {}
            return;
        }
    }
    public static String format(long bytes) {
        if(bytes<1024) return bytes+" B";
        if(bytes<1024*1024) return String.format(Locale.US,"%.1f KB",bytes/1024.0);
        if(bytes<1024L*1024*1024) return String.format(Locale.US,"%.1f MB",bytes/(1024.0*1024));
        return String.format(Locale.US,"%.2f GB",bytes/(1024.0*1024*1024));
    }
    private Notification notification() {
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,TransferService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Rclone Browser Next").setContentText(status()).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(new Notification.Action.Builder(null,"取消全部",cancel).build()).build();
    }
    private void updateNotice() {
        long now=SystemClock.elapsedRealtime();
        if(now-lastNotice<1000) return;
        lastNotice=now; getSystemService(NotificationManager.class).notify(NOTICE,notification());
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        synchronized(LOCK) {
            destroyed=true;
            if(instance==this) {
                // A queued start may already be scheduled. Never discard its jobs during idle shutdown.
                if(ownedJob!=null && active==ownedJob) {
                    active.cancelled=true; record(this,active,"已中斷");
                    if(process!=null) process.destroyForcibly();
                }
                instance=null;
            }
        }
        if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    private static AtomicFile historyFile(Context c) { return new AtomicFile(new File(c.getFilesDir(),"transfer-history.json")); }
    private static void loadHistory(Context c) {
        if(historyLoaded) return; historyLoaded=true;
        try {
            JSONArray rows=new JSONArray(new String(historyFile(c).readFully(),StandardCharsets.UTF_8));
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                String state=row.optString("state");
                if(state.equals("等待中")||state.equals("進行中")) row.put("state","已中斷");
                HISTORY.add(row);
            }
        } catch(Exception ignored) {}
    }
    private static void record(Context c,Job job,String state) {
        try {
            JSONObject row=null;
            for(JSONObject item:HISTORY) if(item.optString("id").equals(job.id)) {row=item;break;}
            if(row==null) {
                // Source and destination are kept so a record can be run again or saved as a reusable job.
                // They are paths only; engine error text, which may hold tokens, is still never stored.
                row=new JSONObject();row.put("id",job.id);row.put("name",job.name());row.put("operation",job.verb());
                row.put("kind",job.kind);row.put("src",job.src);row.put("dst",job.dst);row.put("directory",job.directory);
                row.put("created",job.created);
                HISTORY.add(0,row);
            }
            row.put("state",state);row.put("time",System.currentTimeMillis());
            putResult(job);
            for(int i=HISTORY.size()-1;i>=0 && HISTORY.size()>HISTORY_LIMIT;i--) if(finished(HISTORY.get(i))) detailsFile(c,HISTORY.remove(i).optString("id")).delete();
            save(c);
        } catch(JSONException ignored) {}
    }
    private static void save(Context c) {
        AtomicFile file=historyFile(c); FileOutputStream out=null;
        try {out=file.startWrite();out.write(new JSONArray(HISTORY).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(IOException e) {if(out!=null)file.failWrite(out);}
    }
}

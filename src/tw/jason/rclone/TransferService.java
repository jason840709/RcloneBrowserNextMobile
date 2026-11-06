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
    private static final class Job {
        final String id=UUID.randomUUID().toString(),src,dst;
        final boolean directory,move,sync;
        volatile boolean cancelled;
        Job(String s,String d,boolean dir,boolean m,boolean sy) {src=s;dst=d;directory=dir;move=m;sync=sy;}
        String name() { String s=src.replaceAll("/+$",""); int i=Math.max(s.lastIndexOf('/'),s.lastIndexOf(':')); return s.substring(i+1); }
        String verb() { return sync ? "同步" : move ? "移動" : "複製"; }
    }
    public static void validateTransfer(String src,String dst,boolean directory) {
        String invalid=Rclone.validateTransfer(src,dst);
        if(invalid!=null) throw new IllegalArgumentException(invalid);
    }
    public static void enqueue(Context context,String src,String dst,boolean directory,boolean move,boolean sync) {
        validateTransfer(src,dst,directory);
        if(sync && (!directory || move)) throw new IllegalArgumentException("同步需要選擇資料夾");
        Job job=new Job(src,dst,directory,move,sync);
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
                    active=job; ownedJob=job;
                    status=job.verb()+"中 · "+job.name();
                    record(this,job,"進行中");
                }
                execute(job);
                synchronized(LOCK) { if(active==job) { active=null; process=null; } ownedJob=null; }
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
            ArrayList<String> args=new ArrayList<>();
            args.add(job.sync?"sync":job.move?(job.directory?"move":"moveto"):(job.directory?"copy":"copyto"));
            if(job.directory) args.add("--create-empty-src-dirs");
            if(job.directory && job.move) args.add("--delete-empty-src-dirs");
            if(!job.sync) args.add("--ignore-existing");
            else args.add("--delete-after");
            // Values come from the user's settings; the defaults equal the fixed values used up to 0.3.0
            // (4 transfers, 8 checkers, no bandwidth limit, 15 s connect and 60 s idle timeouts).
            Collections.addAll(args,"--transfers",String.valueOf(Settings.transfers(this)),"--checkers",String.valueOf(Settings.checkers(this)),"--stats","1s","--stats-one-line","--use-json-log","--stats-log-level","NOTICE","--log-level","NOTICE","--retries","2","--low-level-retries","3","--contimeout",Settings.connectTimeout(this),"--timeout",Settings.idleTimeout(this));
            int bandwidth=Settings.bandwidthMB(this);
            if(bandwidth>0) Collections.addAll(args,"--bwlimit",bandwidth+"M");
            Collections.addAll(args,"--",job.src,job.dst);
            synchronized(LOCK) {
                if(job.cancelled || destroyed) {record(this,job,"已取消"); return;}
                owned=Rclone.start(this,args); process=owned;
            }
            updateNotice();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(job.cancelled) { owned.destroyForcibly(); continue; }
                    try {
                        JSONObject log=new JSONObject(line);
                        JSONObject stats=log.optJSONObject("stats");
                        if(stats!=null) {
                            transferred=stats.optLong("transfers",transferred);
                            long bytes=stats.optLong("bytes"), total=stats.optLong("totalBytes");
                            double speed=stats.optDouble("speed",0);
                            status=job.verb()+"中 · "+format(bytes)+(total>0?" / "+format(total):"")+" · "+format((long)speed)+"/秒";
                        }
                        // Error messages may contain remote tokens; keep them out of UI and persisted history.
                    } catch(JSONException ignored) {}
                    updateNotice();
                }
            }
            int exit=owned.waitFor();
            synchronized(LOCK) {
                if(job.cancelled) status="已取消 · "+job.name();
                else if(exit==0) status=(transferred==0 && !job.sync?"處理完成，沒有新增檔案":job.verb()+"完成")+" · "+job.name()+(transferred>=0?" · 傳輸 "+transferred+" 個檔案":"");
                else status="傳輸失敗（"+exit+"）· "+job.name()+"；請檢查連線與目的地權限";
                record(this,job,job.cancelled?"已取消":exit==0?(transferred==0 && !job.sync?"完成，沒有新增檔案":"完成"):"失敗（"+exit+"）");
            }
        } catch(Exception e) {
            synchronized(LOCK) {
                status=job.cancelled?"已取消":"傳輸失敗，請檢查連線與儲存空間";
                record(this,job,job.cancelled?"已取消":"失敗");
            }
        } finally { if(owned!=null && owned.isAlive()) owned.destroyForcibly(); }
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
            if(row==null) {row=new JSONObject();row.put("id",job.id);row.put("name",job.name());row.put("operation",job.verb());HISTORY.add(0,row);}
            row.put("state",state);row.put("time",System.currentTimeMillis());
            while(HISTORY.size()>30) HISTORY.remove(HISTORY.size()-1);
            AtomicFile file=historyFile(c); FileOutputStream out=null;
            try {out=file.startWrite();out.write(new JSONArray(HISTORY).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
            catch(IOException e) {if(out!=null)file.failWrite(out);}
        } catch(JSONException ignored) {}
    }
}

package tw.jason.rclone;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Streams one transfer's bounded diagnostic report to Download/RcloneNext報告, for
 * analysis: what ran, with which settings, every count, why deletion was or was not done, each kind of error with
 * a sample of rclone's own message, the files of each group, the connection types and the memory card's file
 * system. Never written: passwords or the contents of the connection settings; error text goes through
 * {@link TransferReport#clean}. The second half is the same data as JSON for reading by a program.
 */
public final class ReportExport {
    private ReportExport() {}

    static final String FOLDER = "RcloneNext報告";

    /** Builds and saves the report; returns the file. Call from a background thread (it runs rclone briefly). */
    public static File export(Context c, String id) throws Exception {
        JSONObject d = TransferService.details(c, id);
        if (d == null) throw new IllegalStateException("找不到這筆紀錄");
        d = (JSONObject) sanitized(d);
        JSONObject r = d.optJSONObject("record");
        JSONObject sum = d.optJSONObject("summary");
        File dir = new File(FileOps.DOWNLOADS, FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("無法在 Download 建立「" + FOLDER + "」資料夾");
        // A new random suffix prevents a second export in the same second from replacing the first.
        File f = new File(dir, "傳輸分析報告_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".txt");
        if (!f.createNewFile()) throw new IllegalStateException("無法建立分析報告");
        boolean complete = false;
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
        ReportText t = new ReportText(out);
        String now = new SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.TAIWAN).format(new Date());

        t.append("Rclone Next 傳輸分析報告\n").append("產生時間：").append(now).append("\n\n");
        t.append("== 環境 ==\n");
        t.append("目前 App 版本：").append(appVersion(c)).append('\n');
        t.append("裝置：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("，Android ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        Rclone.Result v = FileOps.version(c);
        t.append("傳輸引擎：").append(v.code == 0 ? v.out.split("\n")[0].trim() : "（讀不到）").append('\n');
        t.append("連線類型：");
        try {
            Map<String, String> remotes = FileOps.remotes(c);
            if (remotes.isEmpty()) t.append("（沒有連線）");
            for (Map.Entry<String, String> e : remotes.entrySet()) t.append(e.getKey()).append(' ').append(e.getValue()).append("；");
        } catch (Exception e) { t.append("（讀不到）"); }
        t.append('\n');

        t.append("匯出格式：2；單份上限 100 MB（100,000,000 位元組）。\n");
        t.append("依既有傳輸紀錄匯出，不重新掃描檔案。正常檔案僅列統計，優先保留錯誤與未刪除路徑。\n");
        t.append("舊紀錄未保存的明細無法補回；各清單皆標有保存與未列筆數。\n");
        t.append("\n== 這次傳輸 ==\n");
        if (r != null) {
            t.append("名稱：").append(r.optString("name")).append('\n');
            t.append("方式：").append(TransferPages.method(r)).append("（").append(r.optString("kind")).append("）\n");
            t.append("狀態：").append(r.optString("state")).append('\n');
            t.append("從：").append(r.optString("src")).append('\n');
            t.append("到：").append(r.optString("dst")).append('\n');
            t.append("開始：").append(TransferPages.clock(r.optLong("started"))).append("　結束：").append(TransferPages.clock(r.optLong("ended", r.optLong("time")))).append('\n');
            if (r.has("compare")) t.append("比對方式：").append(Settings.compareName(r.optInt("compare"))).append('\n');
            t.append("同時傳輸 ").append(r.optInt("transfers")).append(" 個、同時比對 ").append(r.optInt("checkers")).append(" 個\n");
            for (String p : new String[]{r.optString("src"), r.optString("dst")}) {
                String mount = mountLine(p);
                if (mount != null) t.append("記憶卡掛載（").append(p).append("）：").append(mount).append('\n');
            }
        }

        t.append("\n== 結果 ==\n");
        if (sum == null) t.append("（此紀錄無統計資料）\n");
        else {
            String[][] keys = {{"added", "新增"}, {"replaced", "更新（覆蓋）"}, {"moved", "移動"}, {"same", "不用傳／同名略過"}, {"onlyDst", "比對時只在目的地（不代表目前仍未刪除）"},
                    {"deleted", "已刪除"}, {"removedDirs", "刪除的資料夾"}, {"extraDirs", "清理時檢查的資料夾"}, {"keptDirs", "檢查後保留的資料夾"},
                    {"timeOnly", "只更新時間"}, {"skipped", "略過的名稱"}, {"fileErrors", "出錯的檔案"}, {"errors", "rclone 錯誤次數"},
                    {"listed", "兩邊合計讀取"}, {"checks", "已比對"}, {"transfers", "已傳檔案"}, {"bytes", "已傳位元組"}};
            for (String[] k : keys) t.append(k[1]).append("：").append(MainActivity.count(sum.optLong(k[0]))).append('\n');
            t.append("耗時：").append(MainActivity.duration(TransferPages.usedMs(r == null ? new JSONObject() : r, sum))).append('\n');
        }

        t.append("\n== 刪除 ==\n");
        if (sum == null) t.append("（不明）\n");
        else if (!TransferService.MIRROR.equals(r == null ? "" : r.optString("kind"))) t.append("此模式不刪除目的地檔案。\n");
        else {
            if (sum.optBoolean("deletedAnyway")) t.append("rclone 因出錯跳過刪除；確認錯誤只影響個別檔案傳輸或檔案、資料夾的修改時間，且兩邊清單完整後，App 依比對結果另外刪除 ").append(MainActivity.count(sum.optLong("deleted")))
                    .append(" 個多餘檔案，並刪除來源沒有的資料夾 ").append(MainActivity.count(sum.optLong("removedDirs"))).append(" 個（檢查 ").append(MainActivity.count(sum.optLong("extraDirs")))
                    .append(" 個，保留 ").append(MainActivity.count(sum.optLong("keptDirs"))).append(" 個：來源有同名項目、非空或無法確認）。\n");
            else if (sum.optBoolean("deletesSkipped")) t.append("未刪除目的地多出的 ").append(MainActivity.count(sum.optLong("onlyDst"))).append(" 個檔案。\n");
            else t.append("照常刪除：").append(MainActivity.count(sum.optLong("deleted"))).append(" 個。\n");
            if (sum.optBoolean("directoryCleanup") && !sum.optBoolean("deletedAnyway"))
                t.append("另已清理空資料夾：刪除 ").append(MainActivity.count(sum.optLong("removedDirs"))).append(" 個；來源無法確認、有同名項目或資料夾非空者保留。空資料夾清理不表示可依不完整清單刪除檔案。\n");
            if (!sum.optString("directoryCleanupProblem").isEmpty()) t.append(sum.optString("directoryCleanupProblem")).append('\n');
            JSONObject why = sum.optJSONObject("unsafeReason");
            if (why != null) t.append("擋住刪除的第一個錯誤：").append(why.optString("objectType")).append(" | ").append(why.optString("object")).append(" | ").append(why.optString("msg")).append('\n');
            t.append("刪除規則：僅個別檔案寫入或修改時間錯誤（Failed to copy/move、Failed to set modification time、partial file rename failed），以及資料夾的 Failed to update directory timestamp，會放行檔案刪除；其他錯誤不放行。")
             .append("空資料夾由深到淺確認：只有來源確認不存在且目的地確實為空才刪除；來源分支讀不到時保留。\n");
        }
        if (sum != null && sum.optBoolean("cardNames")) t.append("記憶卡限制：結尾為句點或空格的名稱不複製也不改名（僅列出最上層項目）。\n");
        if (r != null && r.has("timeNotKept")) t.append("目的地沒有保留修改時間：").append(r.optJSONObject("timeNotKept").toString()).append('\n');
        JSONArray general = sum == null ? null : sum.optJSONArray("general");
        if (general != null) for (int i = 0; i < general.length(); i++) t.append("出錯原因：").append(general.optString(i)).append('\n');
        JSONArray notes = sum == null ? null : sum.optJSONArray("notes");
        if (notes != null) for (int i = 0; i < notes.length(); i++) t.append("說明：").append(notes.optString(i)).append('\n');

        JSONObject audit = sum == null ? null : sum.optJSONObject("directoryAudit");
        t.append("\n== 資料夾核對與保留原因 ==\n");
        if (audit == null) t.append("此舊紀錄未保存資料夾保留原因。\n");
        else {
            t.append("僅統計同步根目錄下的第一層資料夾。\n");
            t.append("來源：").append(audit.optLong("sourceTopDirs", -1)).append("；目標清理前：")
                    .append(audit.optLong("targetTopDirsBefore", -1)).append("；目標清理後：").append(audit.optLong("targetTopDirsAfter", -1)).append("。-1 表示未能完整讀取。\n");
            JSONArray kept = audit.optJSONArray("keptDetails");
            if (kept != null) for (int i = 0; i < kept.length(); i++) {
                JSONArray row = kept.optJSONArray(i);
                if (row != null) t.append(row.optString(0)).append("｜").append(row.optString(1)).append('\n');
            }
            t.append("未逐項列出的保留資料夾：").append(audit.optLong("keptNotListed")).append("。\n");
        }

        t.append("\n== 錯誤種類（依原因分組，次數包含重試與結束訊息）==\n");
        JSONArray kinds = d.optJSONArray("errorKinds");
        if (kinds == null || kinds.length() == 0) t.append("（無錯誤，或為 0.7.3 以前的舊紀錄）\n");
        else for (int i = 0; i < kinds.length(); i++) {
            JSONObject k = kinds.optJSONObject(i);
            t.append(MainActivity.count(k.optLong("count"))).append(" 次｜").append(k.optString("kind")).append("\n　例：").append(k.optString("sample")).append('\n');
        }

        // 0.7.4: every folder with failed files, not only the first 500 files.
        JSONArray folders = d.optJSONArray("errorFolders");
        if (folders != null && folders.length() > 0) {
            t.append("\n== 出錯的資料夾（已保存明細，每個資料夾一行，最多 300 個）==\n");
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                String name = folder.optString("folder");
                t.append(MainActivity.count(folder.optLong("count"))).append(" 個｜").append(name.isEmpty() ? "（最上層）" : name).append("\n　").append(folder.optString("reason")).append('\n');
            }
        }

        JSONObject lists = d.optJSONObject("lists");
        if (lists != null) {
            String[][] groups = {{"errors", "出錯", "fileErrors"}, {"skipped", "略過的名稱", "skipped"}, {"onlyDst", "比對時只在目的地，刪除結果見上方", "onlyDst"}, {"dirs", "刪除的資料夾", "removedDirs"}};
            for (String[] g : groups) {
                JSONArray a = lists.optJSONArray(g[0]);
                int kept = a == null ? 0 : a.length();
                long total = sum == null ? kept : sum.optLong(g[2], kept);
                t.append("\n== 檔案：").append(g[1]).append("（統計 ").append(total).append(" 筆，保存 ").append(kept)
                        .append(" 筆，未列 ").append(Math.max(0, total - kept)).append(" 筆）==\n");
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONArray e = a.optJSONArray(i);
                    if (e == null) continue;
                    t.append(e.optString(0));
                    if (g[0].equals("errors")) t.append("\n　").append(e.optString(1)).append("\n　原文：").append(e.optString(2));
                    else if (g[0].equals("skipped")) t.append("\n　").append(e.optString(1));
                    t.append('\n');
                }
            }
        }

        t.append("\n== 原始資料（JSON）==\n");
        JSONObject raw = new JSONObject();
        raw.put("record", r).put("summary", sum).put("errorKinds", kinds).put("errorFolders", folders);
        raw.put("formatVersion", 2).put("maxBytes", ReportText.MAX_BYTES);
        t.block(raw.toString(1) + "\n");
        t.finish();
        out.close();
        complete = true;
        } finally {
            if (!complete) f.delete();
        }
        if (f.length() > ReportText.MAX_BYTES) { f.delete(); throw new IllegalStateException("報告超過容量上限"); }
        return f;
    }

    private static Object sanitized(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject out = new JSONObject(), object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.matches("(?i)password|pass|token|access_token|secret|authorization|config")) continue;
                out.put(key, sanitized(object.opt(key)));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray out = new JSONArray(), array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) out.put(sanitized(array.opt(i)));
            return out;
        }
        return value instanceof String ? ReportPrivacy.redact((String) value) : value;
    }

    private static String appVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** The /proc/self/mounts lines for a memory card or USB path (file system type and options), or null. */
    static String mountLine(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/|$)").matcher(path == null ? "" : path);
        if (!m.find()) return null;
        StringBuilder b = new StringBuilder();
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/mounts")), StandardCharsets.UTF_8).split("\n"))
                if (line.contains("/" + m.group(1) + " ")) b.append(b.length() > 0 ? "；" : "").append(line.trim());
        } catch (Exception e) { return "（讀不到）"; }
        return b.length() == 0 ? "（掛載表裡找不到）" : b.toString();
    }
}

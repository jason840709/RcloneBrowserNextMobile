package tw.jason.rclone;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

 
public final class ReportText {
    public static final long MAX_BYTES = 100_000_000L;
    private static final int FOOTER_BYTES = 1024, LINE_CHARS = 16384;
    private final Writer out;
    private final long limit;
    private final StringBuilder pending = new StringBuilder();
    private long bytes, omittedLines, omittedBlocks, shortenedLines;
    private boolean shortened, finished;

    public ReportText(Writer out) { this(out, MAX_BYTES); }
    public ReportText(Writer out, long limit) {
        if (limit < 2048 || limit > MAX_BYTES) throw new IllegalArgumentException("Invalid report budget");
        this.out = out;
        this.limit = limit;
    }

    public ReportText append(Object value) throws IOException {
        if (finished) throw new IllegalStateException("Report already finished");
        String s = String.valueOf(value);
        int from = 0;
        while (from < s.length()) {
            int end = s.indexOf('\n', from);
            boolean newline = end >= 0;
            if (!newline) end = s.length();
            int take = Math.min(end - from, Math.max(0, LINE_CHARS - pending.length()));
            pending.append(s, from, from + take);
            if (take < end - from) shortened = true;
            if (newline) flushLine();
            from = end + 1;
        }
        return this;
    }

    private void flushLine() throws IOException {
        if (shortened) {
            if (pending.length() > 0 && Character.isHighSurrogate(pending.charAt(pending.length() - 1)))
                pending.setLength(pending.length() - 1);
            pending.append("…（這一行過長，後段省略）");
            shortenedLines++;
        }
        String line = pending.toString() + "\n";
        if (!writeWithinBudget(line)) omittedLines++;
        pending.setLength(0);
        shortened = false;
    }

     
    public void block(String text) throws IOException {
        if (finished) throw new IllegalStateException("Report already finished");
        if (pending.length() > 0) flushLine();
        if (!writeWithinBudget(text)) omittedBlocks++;
    }

    private boolean writeWithinBudget(String text) throws IOException {
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > limit - FOOTER_BYTES) return false;
        out.write(text);
        bytes += size;
        return true;
    }

    public void finish() throws IOException {
        if (finished) return;
        if (pending.length() > 0) flushLine();
        String footer = "\n== 報告容量 ==\n單份上限：" + limit + " 位元組。\n"
                + "因容量限制省略 " + omittedLines + " 行、" + omittedBlocks + " 段資料；過長而縮短 " + shortenedLines + " 行。\n"
                + "未逐項列出的檔案仍包含在統計數字內；清單沒有某個路徑，不代表該路徑沒有錯誤。\n";
        int size = footer.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > limit) throw new IOException("Report footer exceeds budget");
        out.write(footer);
        bytes += size;
        out.flush();
        finished = true;
    }

    public long bytesWritten() { return bytes; }
}

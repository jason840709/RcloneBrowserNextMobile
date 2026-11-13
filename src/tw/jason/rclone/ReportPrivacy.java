package tw.jason.rclone;

 
public final class ReportPrivacy {
    private ReportPrivacy() {}
    public static String redact(String text) {
        return text.replaceAll("(?i)(\\b[a-z][a-z0-9+.-]*://)[^\\s/@]+@", "$1[隱藏登入資料]@")
                .replaceAll("(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s?\"']*)\\?[^\\s\"']*", "$1?[隱藏網址參數]")
                .replaceAll("(?i)\\b(authorization\\s*[:=]\\s*)(?:(?:Bearer|Basic)\\s+)?[^\\s,\"';]+", "$1[隱藏]")
                .replaceAll("(?i)\\b((?:password|pass|token|access_token|api_key|secret)\\s*[:=]\\s*)[^\\s,\"';&]+", "$1[隱藏]");
    }
}

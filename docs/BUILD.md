# 建置說明

本專案使用純 Java 與 Android 原生 SDK 編寫，不依賴 Gradle 或 AndroidX。建置腳本支援 macOS 與 Linux，需準備 JDK、Android SDK（API 36 平台、build-tools 34.0.0 與 36.0.0），以及 `curl`、`gzip`、`zip`、`shasum`。

## 取得引擎與編譯

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./update-engine.sh 1.75.2
./build.sh
```

`update-engine.sh` 會從 rclone 官方下載 Android ARM64 執行檔，驗證架構與版本後存為 `lib/arm64-v8a/librclone.so`（來源見 `assets/THIRD-PARTY.txt`）。建置時會核對 `engine.sha256` 雜湊值。

編譯完成後會輸出 `dist/RcloneNext-0.8.0-arm64.apk`。若檔案已存在會中止，加上 `ALLOW_REBUILD=1` 才會覆寫。

## 簽章

預設會在 `build/development.keystore` 產生本機測試金鑰（預設密碼 `android`），亦可用 `SIGNING_KEYSTORE` 指定既有金鑰。自行編譯的 APK 簽章與官方發布版不同，無法直接覆蓋安裝發布版。

# 建置說明

原始碼使用 Java 與 Android 系統介面，沒有 Gradle 或 AndroidX 相依套件。建置腳本目前以 macOS 為基準，需要 JDK、Android SDK 的 API 36 平台、build-tools 34.0.0 與 36.0.0，以及 curl、gzip、zip 和 shasum。

## 取得引擎與編譯

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./update-engine.sh 1.75.2
./build.sh
```

更新腳本從 rclone 官方下載 Android ARM64 執行檔，檢查架構與內嵌版本，再放入 `lib/arm64-v8a/librclone.so`。引擎來源見 `assets/THIRD-PARTY.txt`。`engine.sha256` 記錄檔案指紋，建置時會核對。

輸出為 `dist/RcloneNext-0.4.0-arm64.apk`。若同名輸出已存在，腳本會停止；設定 `ALLOW_REBUILD=1` 才會覆寫。

## 簽章

預設會在 `build/development.keystore` 建立本機測試金鑰。自行建置的簽章與發布版不同，不能直接覆蓋安裝發布版。可用 `SIGNING_KEYSTORE` 指定既有測試金鑰；腳本的預設測試密碼為 Android 慣用的 `android`。私密簽章檔不隨原始碼提供。

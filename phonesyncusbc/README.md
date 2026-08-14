# Phone Sync USB-C

Android initiator for consent-based phone synchronization.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Choose A Backup Folder

In **Collected source data -> target media**, choose **Choose backup destination**.

- For device storage, an SD card, or a USB drive, select a folder in Android's picker and tap **Use this folder**. Phone Sync retains that folder grant for later backups.
- For OneDrive, Google Drive, Dropbox, or another cloud app, first select the provider as the destination, then tap **Start backup**. Choose the cloud folder and confirm the upload in the provider app. After returning to Phone Sync, tap **Complete backup** once the provider reports success, or **Retry cloud upload** if it was canceled.

Local backups display their completed item and byte totals automatically. Cloud completion is user-confirmed because Android upload activities do not return a consistent provider verification result.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK every time and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The task fails when no authorized device is connected.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
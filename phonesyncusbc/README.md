# Phone Sync USB-C

Android initiator for consent-based phone synchronization.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Choose A Backup Folder

In **Collected source data -> target media**, choose **Choose backup destination**.

- For device storage, an SD card, or a USB drive, select a folder in Android's picker and tap **Use this folder**. Phone Sync retains that folder grant for later backups.
- For OneDrive, Google Drive, Dropbox, or another cloud app, first select the provider as the destination, then tap **Start backup**. Choose the cloud folder and confirm the upload in the provider app. After returning to Phone Sync, tap **Complete backup** once the provider reports success, or **Retry cloud upload** if it was canceled.

Local backups display their completed item and byte totals automatically. Cloud completion is user-confirmed because Android upload activities do not return a consistent provider verification result.

## Personal Data

- **This Android device:** Phone Sync can export SMS/MMS (including MMS attachments), call history, contacts, calendar events, and captured notifications. Android requires the owner to approve SMS, call-log, contacts, calendar, and notification-listener access on system-controlled screens; an app cannot auto-approve its own permissions.
- **Connected iPhone:** USB PTP exposes photos and videos, not private message, call, mail, chat, or notification databases. On supported Samsung devices, use the in-app **Open Smart Switch for iPhone transfer** action for SMS, call history, contacts, and calendar, then collect the migrated Android data into Phone Sync's audited backup set.
- **Email and chat:** Android and iOS do not expose other apps' private mail or chat databases through a general permission. Use the source app or provider's supported export and import that export into Phone Sync. This route does not require a cloud client ID.
- **Notifications:** Notification access captures notifications active when access is approved and future notifications. Android does not provide deleted notification history from before approval.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK every time and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The task fails when no authorized device is connected.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
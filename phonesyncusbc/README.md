# Phone Sync USB-C

Android initiator for consent-based phone synchronization.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Mobile-Only Backup

Phone Sync does not use cloud apps, OAuth, client IDs, desktop software, or external migration apps.

- **Default:** Tap **Start backup** to write a timestamped copy under Android Downloads / Phone Sync Backups.
- **Optional:** Choose a folder on this phone, an SD card, or an attached USB drive. Non-local document providers are rejected.

Backups display their completed item and byte totals automatically.

## Personal Data

- **This Android device:** Phone Sync can export SMS/MMS (including MMS attachments), call history, contacts, calendar events, and captured notifications. Android requires the owner to approve SMS, call-log, contacts, calendar, and notification-listener access on system-controlled screens; an app cannot auto-approve its own permissions.
- **Connected iPhone:** USB PTP exposes photos and videos, not private message, call, mail, chat, or notification databases. Phone Sync imports user-created source export files when available.
- **Email and chat:** Android and iOS do not expose other apps' private mail or chat databases through a general permission. Use the source app's supported export and import that local file into Phone Sync.
- **Notifications:** Notification access captures notifications active when access is approved and future notifications. Android does not provide deleted notification history from before approval.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK every time and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The task fails when no authorized device is connected.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
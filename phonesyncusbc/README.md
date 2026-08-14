# Phone Sync USB-C

Android USB host for consent-based phone synchronization and backup.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Workflow

1. Connect an Android phone, iPhone, or MTP/PTP device with a data-capable USB cable.
2. Approve Android's USB prompt, then authorize all available source categories.
3. Tap **Pull all available data**. Phone Sync copies every exposed media/document file and recognizes USB-visible SMS/MMS, calls, contacts, calendar, email, chat, and notification exports.
4. Tap **Start backup** for Android Downloads, choose a folder through Android's picker, or tap **Upload to OneDrive, Google Drive, or another app**.

Installed storage apps handle their own authentication through Android. Phone Sync does not require provider client IDs, OAuth configuration, or embedded credentials.

## Source Coverage

- **MTP Android/Windows sources:** media, documents, and export files exposed in shared storage.
- **PTP iPhone sources:** photos and videos exposed by iOS.
- **Android protected data:** expand **Prepare this Android phone** on the source first. It exports SMS/MMS with attachments, calls, contacts, calendar, and captured notifications into shared storage; another Phone Sync device can then pull those exports over USB.
- **Email and chat:** use the source app's supported export, then choose **Add email export files** or **Add chat export files** in the collapsed preparation panel. Phone Sync copies those files into USB-visible shared storage.
- **iPhone protected data:** generic USB PTP does not expose iPhone SMS, calls, mail, chat, or notification databases. Phone Sync imports user-created export files when available.

Phone Sync never reports protected data as pulled unless the source actually exposes a readable export.

## Destinations

- Android Downloads / Phone Sync Backups
- Any writable folder exposed by Android's system picker, including local, SD/USB, and supporting document providers
- OneDrive, Google Drive, or another installed app through Android's upload chooser

Local/picker backups display completed item and byte totals. For multi-item provider uploads, Phone Sync creates one ZIP64 package with a SHA-256 manifest before opening the provider. This avoids OneDrive's broken bulk-file checkmark screen. Provider upload completion is controlled by the selected provider app.

## Permissions

USB, SMS, call-log, contacts, calendar, and notification-listener access remain controlled by Android's owner-visible permission screens. Apps cannot silently approve these permissions themselves.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK every time and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The task fails when no authorized device is connected.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
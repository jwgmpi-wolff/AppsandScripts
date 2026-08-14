# Phone Sync USB-C

Android USB host for consent-based phone synchronization and backup.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Workflow

1. Open **USB Source**, connect an Android phone, iPhone, or MTP/PTP device with a data-capable USB cable, and approve Android's USB prompt.
2. Authorize the available source categories, then tap **Request all USB-visible files**. Phone Sync copies every exposed media/document file and recognizes USB-visible SMS/MMS, calls, contacts, calendar, email, chat, and notification exports.
3. After collection, use **Push this USB source data** on the same tab to select that source's items and send them to Android Downloads, another local folder, OneDrive, Google Drive, or another installed provider.
4. Open **This Device** to prepare protected Android data or browse data already collected on this phone.
5. Open **Backup** for consolidated progress and destination activity across collected data.

Installed storage apps handle their own authentication through Android. Phone Sync does not require provider client IDs, OAuth configuration, or embedded credentials.

## Source Coverage

- **MTP Android/Windows sources:** media, documents, and export files exposed in shared storage.
- **Legacy MTP sources:** when a phone such as a Lumia rejects 64-bit chunked reads, Phone Sync automatically retries with MTP's standard full-object request.
- **PTP iPhone sources:** photos and videos exposed by iOS.
- **Android protected data:** install/open Phone Sync on the source Android, expand **This Device > Prepare this Android phone**, and create the exports first. Connect that source using **File transfer / Android Auto** USB mode; another Phone Sync device can then pull the shared-storage exports.
- **Email and chat:** use the source app's supported export, then choose **Add email export files** or **Add chat export files** in the collapsed preparation panel. Phone Sync copies those files into USB-visible shared storage.
- **iPhone protected data:** generic USB PTP does not expose iPhone SMS, calls, mail, chat, or notification databases. Phone Sync imports user-created export files when available.

Phone Sync never reports protected data as pulled unless the source actually exposes a readable export.

## SMS, Call, or Email Exports Missing

If **USB export readiness** reports that SMS, calls, or email were not exposed:

1. On the source Android phone, open **Phone Sync > This Device > Show preparation tools**.
2. Tap **Collect SMS and MMS** and **Collect call history**.
3. Export email from the source mail app, then tap **Add email export files** in Phone Sync.
4. Reconnect the source to the collecting device, keep it unlocked, and select **File transfer / Android Auto** rather than **Photo transfer** or charging-only mode.
5. On the collecting device, tap **Request all USB-visible files** again.

iPhone PTP exposes photos and videos, not private SMS, call, or mail databases. Those records require a supported source-app or backup export before Phone Sync can import them.

Windows Phone exposes shared media through MTP but does not publish its private SMS, call-history, or email stores as MTP objects. Phone Sync requests every object the phone advertises, including a legacy full-file compatibility request, but no collecting-device app can force Windows Phone to expose stores its OS withholds. Use Microsoft-account SMS backup when available and export email from its server/provider; Windows Phone has no standard USB call-history export.

## Destinations

- Android Downloads / Phone Sync Backups
- Any writable folder exposed by Android's system picker, including local, SD/USB, and supporting document providers
- OneDrive, Google Drive, or another installed app through Android's upload chooser

The **Backup Activity** tab keeps overall item progress, current-file byte progress, processed bytes, and the latest result together. USB pull progress stays in **USB Source**, while Android personal-data collection progress stays in **This Device**. For multi-item provider uploads, Phone Sync creates one ZIP64 package with a SHA-256 manifest before opening the provider. This avoids OneDrive's broken bulk-file checkmark screen. Provider upload completion is controlled by the selected provider app.

When a USB source is connected, its destination panel is scoped to that source's completed transfers. It does not mix in exports prepared on the collecting Android device or files collected from another USB peer.

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
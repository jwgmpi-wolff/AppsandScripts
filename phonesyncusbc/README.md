# Phone Sync USB-C

Android USB host for consent-based, external-device-only phone collection and backup.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

## Trusted Repository APK

The repository pins the published APK's SHA-256, signing-certificate SHA-256, application ID, and version in [the APK trust manifest](releases/PhoneSyncUSB-C-debug.apk.trust.json). Verify a downloaded APK from PowerShell before installing it:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\scripts\verify_trusted_apk.ps1 `
	-ApkPath .\releases\PhoneSyncUSB-C-debug.apk `
	-TrustManifestPath .\releases\PhoneSyncUSB-C-debug.apk.trust.json
```

`pushDebugToDevice` performs the same verification before installation, pulls the installed APK back from every device, and verifies it again. Any checksum, signer, package ID, or version mismatch stops the install workflow. Signing keystores and passwords remain outside Git through `.gitignore`.

Android does not treat GitHub as an app store, so manual downloads still require the user to approve **Install unknown apps** for the browser or file manager. The repository cannot bypass that Android security prompt.

## Workflow

1. Open **USB Source**, connect an Android phone, iPhone, or MTP/PTP device with a data-capable USB cable, and approve Android's USB prompt.
2. Authorize the available source categories, then tap **Collect all USB-visible source data**. Phone Sync requests every object advertised by the tethered device and recognizes USB-visible SMS/MMS, calls, contacts, calendar, email, chat, notification, voicemail, and password-vault exports.
3. After collection, use **Push this USB source data** on the same tab: choose data, choose one destination, then use the single destination-specific action such as **Push to OneDrive**.
4. Open **Backup** for consolidated progress and destination activity for the active or most recently collected external source.

Installed storage apps handle their own authentication through Android. Phone Sync does not require provider client IDs, OAuth configuration, or embedded credentials.

## Source Coverage

- **MTP Android/Windows sources:** media, documents, and export files exposed in shared storage.
- **Legacy MTP sources:** when a phone such as a Lumia rejects 64-bit chunked reads, Phone Sync automatically retries with MTP's standard full-object request.
- **PTP iPhone sources:** locally available photos and videos exposed by iOS. Phone Sync tries 64-bit chunked, standard chunked, and full-file PTP reads in order.
- **Android protected data:** use each app's supported export on the external phone and save the export in shared storage before connecting with **File transfer / Android Auto** mode.
- **Email and chat:** exports must already exist in USB-visible shared storage on the external device.
- **Voicemail:** export voicemail audio or visual-voicemail data on the external device into USB-visible storage before collection.
- **Password vaults:** export on the external device, preferably to an encrypted format such as KeePass `.kdbx`. Phone Sync copies the USB-visible file unchanged and never previews or parses credentials.
- **iPhone protected data:** generic USB PTP does not expose iPhone SMS, calls, voicemail, mail, chat, passwords, or notification databases. Only objects iOS advertises over PTP can be collected.

Phone Sync never reports protected data as pulled unless the source actually exposes a readable export.

## iPhone Photos Missing

After each iPhone scan, **iPhone photo coverage** reports the exact PTP-visible count and how many files were new, already collected, unauthorized, or failed. Photos stored only in iCloud are not visible to generic USB PTP and cannot be counted or downloaded by Phone Sync.

1. On the iPhone, open iCloud Photos settings and choose **Download and Keep Originals**.
2. Leave the iPhone charging and on Wi-Fi until original downloads finish and sufficient local storage is available.
3. Under Photos transfer settings, choose **Keep Originals** for transfer to Mac or PC.
4. Keep the iPhone unlocked and trusted while connected, then tap **Collect all USB-visible source data** again.

## SMS, Call, or Email Exports Missing

If **USB export readiness** reports that SMS, calls, or email were not exposed, create supported exports using apps on the external device, save them in that device's shared storage, reconnect it unlocked in **File transfer / Android Auto** mode, and collect again.

iPhone PTP exposes photos and videos, not private SMS, call, or mail databases. Those records require a supported source-app or backup export before Phone Sync can import them.

Windows Phone exposes shared media through MTP but does not publish its private SMS, call-history, or email stores as MTP objects. Phone Sync requests every object the phone advertises, including a legacy full-file compatibility request, but no collecting-device app can force Windows Phone to expose stores its OS withholds. Use Microsoft-account SMS backup when available and export email from its server/provider; Windows Phone has no standard USB call-history export.

## Destinations

- Android Downloads / Phone Sync Backups
- Any writable folder exposed by Android's system picker, including local, SD/USB, and supporting document providers
- OneDrive, Google Drive, or another installed app through Android's upload chooser

The **Backup** tab keeps overall item progress, current-file byte progress, processed bytes, and the latest result together. For multi-item provider uploads, Phone Sync creates one ZIP64 package with a SHA-256 manifest before opening the provider. Provider upload completion is controlled by the selected provider app.

When a USB source is connected, its destination panel is scoped to that source's completed transfers. It does not mix in exports prepared on the collecting Android device or files collected from another USB peer.

The consolidated **Backup** tab follows the connected external source, or the most recently collected external source when disconnected. Legacy collector-side rows from older versions remain quarantined and are excluded from counts, selection, manifests, and provider uploads.

The selected destination persists across app restarts. Phone Downloads and writable folder targets receive files directly. OneDrive, Google Drive, and other app targets prepare one package and open that provider from the primary push button; choose the provider folder and confirm Upload there.

## Permissions

Phone Sync requests Android USB-host permission only. It does not request SMS, call-log, contacts, calendar, or notification-listener access on the collecting device. It cannot bypass a source device lock, brute-force credentials, defeat encryption, or collect private stores the source OS does not advertise over MTP/PTP.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK, verifies it against the repository trust manifest, and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The installed package is pulled back and verified before the task succeeds. The task fails when no authorized device is connected or any trust check differs.

For an intentional new release, update the APK and trust manifest together only after validating the build and confirming the expected signer certificate. Never commit a signing keystore or password.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
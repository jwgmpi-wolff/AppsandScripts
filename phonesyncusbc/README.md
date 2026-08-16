# Phone Sync USB-C

Android USB host for owner-authorized, external-device-only data recovery and read-only logical acquisition.

## Download

[Download the current debug APK](releases/PhoneSyncUSB-C-debug.apk).

Standalone Android tablet reader 1.0.0:

- [Phone Sync Data Reader APK](releases/PhoneSyncDataReader-1.0.0-android-tablet.apk)
- [Tablet reader trust manifest](releases/PhoneSyncDataReader-1.0.0-android-tablet.apk.trust.json)

Windows archive reader 2.4.0:

- [Windows ARM64](releases/PhoneSyncDataReader-2.4.0-win-arm64.zip)
- [Windows x64](releases/PhoneSyncDataReader-2.4.0-win-x64.zip)

Install the matching Windows package with `./scripts/install_windows_reader.ps1 -Launch`. It creates **Phone Sync Data Reader** shortcuts on the Desktop and Start menu.

## Trusted Repository APK

The repository pins the published APK's SHA-256, signing-certificate SHA-256, application ID, and version in [the APK trust manifest](releases/PhoneSyncUSB-C-debug.apk.trust.json). Verify a downloaded APK from PowerShell before installing it:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\scripts\verify_trusted_apk.ps1 `
	-ApkPath .\releases\PhoneSyncUSB-C-debug.apk `
	-TrustManifestPath .\releases\PhoneSyncUSB-C-debug.apk.trust.json
```

`pushDebugToDevice` performs the same verification before installation, pulls the installed APK back from every device, and verifies it again. Any checksum, signer, package ID, or version mismatch stops the install workflow. Signing keystores and passwords remain outside Git through `.gitignore`.

The tablet reader installs separately as `com.jerrywolff.phonesynctabletreader`. Verify it with the same script by passing the tablet APK and its adjacent trust manifest.

Android does not treat GitHub as an app store, so manual downloads still require the user to approve **Install unknown apps** for the browser or file manager. The repository cannot bypass that Android security prompt.

## Workflow

1. Open **USB Source**, connect an owned Android phone, iPhone, or MTP/PTP device with a data-capable USB cable, and approve Android's USB prompt.
2. Select the source profile: **Windows PC**, **Android**, **iPhone/iPad**, or **Camera/IoT**.
3. Authorize the available categories, then tap **Recover all USB-visible data**. Phone Sync requests every advertised object and recognizes media, documents, application data, configuration, logs, system information, messages, and password artifacts.
4. After acquisition, use **Preserve this recovery set** to copy verified artifacts to local or provider storage.
5. Open **Recovered files**, then **Parsed data reader**, to build or refresh the local searchable index.
6. Open **Backup** for consolidated preservation progress and destination activity for the active or most recently recovered external source.

Installed storage apps handle their own authentication through Android. Phone Sync does not require provider client IDs, OAuth configuration, or embedded credentials.

## Recovery Purpose and Limits

The generated inventory identifies these purposes: data recovery, read-only logical forensic acquisition of an owned device, file recovery, artifact extraction, backup-restoration input recovery, and USB-visible storage analysis.

This is a logical MTP/PTP acquisition, not a physical forensic image. Phone Sync does not write to the source, image raw storage, recover deleted blocks, carve unallocated space, decrypt protected data, guess passwords, or bypass device, account, password, or encryption controls. Windows PC, camera, and IoT files are recoverable only when the source exposes them through authorized MTP/PTP or a readable export presented by such a device.

Every newly recovered copy is reopened, checked against the source-advertised size when available, and hashed with SHA-256 before publication. Each session writes `PhoneSync-Recovery-Inventory-*.json` under **Downloads / Phone Sync / Recovery Inventories** with source paths, timestamps, sizes, MTP metadata, outcomes, destinations, hashes, errors, profile, and acquisition limits.

## Device Profiles

- **Windows PC:** USB-visible user-profile exports, Desktop/Documents/Downloads, browser data, PST/OST files, OneDrive cache exports, event logs, registry hives, application databases, configuration, and credential stores.
- **Android:** internal/SD-card media and documents plus USB-visible SMS/MMS, contacts, calls, app databases, chat backups, logs, configuration, system information, and password exports.
- **iPhone/iPad:** PTP media plus encrypted backups and message, note, contact, app-document, configuration, iCloud-synchronized, and password artifacts that iOS or its apps explicitly expose.
- **Camera/IoT:** exposed SD-card contents, footage, configuration, logs, settings backups, application databases, system reports, and credential backups.

Password-manager vaults, browser credential stores, keychain or credential backups, password exports, and explicitly exported passkey backup archives are recovered when USB-visible. They are classified as sensitive, copied intact, never previewed or parsed, and recorded as `COPIED_OPAQUE_NO_DECRYPTION` in the inventory.

Passkey private keys held by Google Password Manager, Microsoft Authenticator, Samsung Pass, Apple Passwords/iCloud Keychain, Windows Hello, or a hardware security module are intentionally non-exportable through MTP/PTP. Phone Sync records `PROVIDER_MANAGED_PRIVATE_KEYS_NOT_EXTRACTED` and never reports those protected keys as recovered. Restore usable passkeys through the original provider's signed-in sync/recovery flow; use each site's recovery process when provider access is unavailable.

## Source Coverage

- **MTP Android/Windows sources:** media, documents, and export files exposed in shared storage.
- **Legacy MTP sources:** when a phone such as a Lumia rejects 64-bit chunked reads, Phone Sync automatically retries with MTP's standard full-object request.
- **PTP iPhone sources:** locally available photos and videos exposed by iOS. Phone Sync tries 64-bit chunked, standard chunked, and full-file PTP reads in order.
- **Android protected data:** use each app's supported export on the external phone and save the export in shared storage before connecting with **File transfer / Android Auto** mode.
- **Email and chat:** exports must already exist in USB-visible shared storage on the external device.
- **Owner export actions:** after a scan, **Start owner export actions** provides source-device steps for WhatsApp, Teams, Zoom, Webex, Signal, Telegram, Slack, Google Chat, Discord, Messenger, SMS, calls, mail, and other apps. Complete the owner-controlled export, then choose **Owner exports complete - rescan**.
- **Voicemail:** export voicemail audio or visual-voicemail data on the external device into USB-visible storage before recovery.
- **Password vaults:** export on the external device, preferably to an encrypted format such as KeePass `.kdbx`. Phone Sync copies the USB-visible file unchanged and never previews or parses credentials.
- **Passkeys:** keep the source device unlocked and signed in to its credential provider. Use Microsoft Authenticator or Samsung Pass restore/sync on this device, or the equivalent provider on another device. A copied metadata/export file is not a usable private passkey by itself.
- **iPhone protected data:** generic USB PTP does not expose iPhone SMS, calls, voicemail, mail, chat, passwords, or notification databases. Only objects iOS advertises over PTP can be recovered.

Phone Sync never reports protected data as recovered unless the source actually exposes a readable object or export.

## Parsed Data Readers

The Android **Data Reader** tab builds a local SQLite index from the verified recovery set for the selected external source. It streams JSON and JSONL records, flattens nested fields, derives source and collection labels from folders and ZIP entry paths, and provides scrollable Images, Messages, SMS, and Voicemails focus filters. Results can be selected individually or by page, narrowed to selected-only, searched, and opened for message detail, full-size image viewing, or voicemail playback.

On Android tablets and unfolded foldables at least 600dp wide, **Auto** uses a Windows-style three-pane layout: Query filters, selectable Records, and record Detail remain visible together with independent scrolling. The Data Reader also provides explicit **Mobile** and **Tablet** layout choices; narrow phone screens stay in the mobile layout.

The separate [Phone Sync Data Reader APK](releases/PhoneSyncDataReader-1.0.0-android-tablet.apk) imports a Phone Sync backup ZIP through Android's document picker. It validates the external peer, quarantines mixed or collector-origin entries, streams and verifies each accepted item against manifest size and SHA-256, persists the verified source in its own app storage, and opens the same three-pane reader without requiring the collector app.

SMS ZIP archives are read entry by entry. JSON content is flattened into logical records; every other non-sensitive item, including media, XML, databases, voicemail, and opaque attachments, is fully streamed, SHA-256 hashed, and represented by a searchable archive-entry record. Credential entries nested in an SMS ZIP remain excluded from parsing. Rebuilding one source is atomic, and canonical hashes prevent duplicate records across repeated pulls and overlapping exports.

Every backup target, direct provider handoff, and compatibility ZIP is bound to one selected external USB peer. In mixed selections, valid selected-peer items continue while blank-peer, other-peer, legacy collector, `This Android`, and `Selected folder` rows are quarantined with reason-coded remediation. ZIP manifests retain the peer ID and source fingerprint so readers can verify accepted items independently.

The native Windows reader under [windows/PhoneSyncDataReader](windows/PhoneSyncDataReader) selects a synchronized or local archive folder directly. It builds an atomic SQLite index under `%LOCALAPPDATA%\PhoneSync\DataReader\Indexes`, offers the same focus and selected-only workflow, searches flattened records and fields, previews loose or ZIP-contained images, and opens voicemail audio without modifying the archive. Exact files deduplicate by source-scoped SHA-256, and logical records deduplicate by canonical field hash within their labeled collection.

## iPhone Photos Missing

After each iPhone scan, **iPhone photo coverage** reports the exact PTP-visible count and how many files were new, already recovered, unauthorized, or failed. Photos stored only in iCloud are not visible to generic USB PTP and cannot be counted or downloaded by Phone Sync.

1. On the iPhone, open iCloud Photos settings and choose **Download and Keep Originals**.
2. Leave the iPhone charging and on Wi-Fi until original downloads finish and sufficient local storage is available.
3. Under Photos transfer settings, choose **Keep Originals** for transfer to Mac or PC.
4. Keep the iPhone unlocked and trusted while connected, then tap **Recover all USB-visible data** again.

## SMS, Call, or Email Exports Missing

If **USB export readiness** reports that SMS, calls, or email were not exposed, create supported exports using apps on the external device, save them in that device's shared storage, reconnect it unlocked in **File transfer / Android Auto** mode, and recover again.

iPhone PTP exposes photos and videos, not private SMS, call, or mail databases. Those records require a supported source-app or backup export before Phone Sync can recover them.

Windows Phone exposes shared media through MTP but does not publish its private SMS, call-history, or email stores as MTP objects. Phone Sync requests every object the phone advertises, including a legacy full-file compatibility request, but no acquisition-host app can force Windows Phone to expose stores its OS withholds. Use Microsoft-account SMS backup when available and export email from its server/provider; Windows Phone has no standard USB call-history export.

## Destinations

- Android Downloads / Phone Sync Backups
- Any writable folder exposed by Android's system picker, including local, SD/USB, and supporting document providers
- OneDrive, Google Drive, or another installed app through Android's upload chooser

The **Backup** tab keeps overall item progress, current-file byte progress, processed bytes, and the latest result together. OneDrive, Google Drive, and other compatible apps receive the original verified files through a direct multi-file handoff, avoiding a second local copy. If an app rejects multi-file sharing, Phone Sync falls back to one ZIP64 package with a SHA-256 manifest under **Downloads / Phone Sync Uploads**. Provider upload completion is controlled by the selected provider app.

When a USB source is connected, its destination panel is scoped to that source's verified recovery results. It does not mix in files from the Android acquisition host or another USB peer.

The consolidated **Backup** tab follows the connected external source, or the most recently recovered external source when disconnected. Legacy host-side rows from older versions remain quarantined and are excluded from counts, selection, manifests, and provider uploads.

The selected destination persists across app restarts. Phone Downloads and writable folder targets receive files directly. OneDrive, Google Drive, and other app targets open from the primary push button with the verified files; choose the provider folder and confirm Upload there. Version 2.1.1 removes obsolete Phone Sync-generated staging ZIPs once during upgrade; later startups remove only interrupted hidden staging archives.

## Permissions

Phone Sync requests Android USB-host permission only. It does not request SMS, call-log, contacts, calendar, or notification-listener access on the acquisition host. It cannot bypass a source device lock, brute-force credentials, defeat encryption, or recover private stores the source OS does not advertise over MTP/PTP.

## Build and Push

From PowerShell in this directory:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat pushDebugToDevice
```

The task assembles the debug APK, verifies it against the repository trust manifest, and installs it on every authorized ADB device. Installation uses `adb install -r`, so application data, trusted-device state, and audit history are preserved. The installed package is pulled back and verified before the task succeeds. The task fails when no authorized device is connected or any trust check differs.

For an intentional new release, update the APK and trust manifest together only after validating the build and confirming the expected signer certificate. Never commit a signing keystore or password.

The APK is also produced at `app/build/outputs/apk/debug/app-debug.apk`.
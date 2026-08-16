# Phone Sync Data Reader for Windows

Native Windows reader for an archived Phone Sync recovery folder.

## Download

- [Windows ARM64](../../releases/PhoneSyncDataReader-2.3.1-win-arm64.zip)  
	SHA-256: `aba921320713b620d4ce50509be888aca2e4fe645b478541815e690eeb6a2308`
- [Windows x64](../../releases/PhoneSyncDataReader-2.3.1-win-x64.zip)  
	SHA-256: `2de397d98215fdd7557f354626bf10eaf1cd2de057907a491d6a0ec724300f9d`

Run `./scripts/install_windows_reader.ps1 -Launch` from the repository root to install the matching self-contained package and create Desktop and Start-menu shortcuts. No separate .NET installation is required.

## What it does

- Recursively reads the selected archive folder without modifying it.
- Catalogs files, images, video, voicemail, documents, and other recovered artifacts.
- Streams JSON, JSONL/NDJSON, and JSON files inside ZIP archives into a local SQLite index.
- Reads every non-sensitive item in SMS ZIP archives. Non-JSON attachments, media, XML, databases, voicemail, and opaque files are fully streamed and receive searchable archive-entry records with byte counts, SHA-256, and entry metadata.
- Flattens nested fields into queryable `fields` rows.
- Uses archive folders and ZIP entry folders as source, collection, folder, and record labels.
- Deduplicates files by SHA-256 and parsed records by canonical field hash.
- Searches titles, message bodies, field names, and field values.
- Filters directly to Images, Messages, SMS, or Voicemails.
- Supports row selection, select-shown, and selected-only result lists.
- Presents message-friendly summaries, full flattened fields, image previews, and voicemail open/play actions.
- Excludes password and credential artifacts, including sensitive entries nested inside otherwise eligible ZIP files, from parsing and preview.
- Enforces peer-bound Phone Sync manifests and rejects collector, mixed-peer, and ambiguous legacy ZIP entries.

The local index is stored under `%LOCALAPPDATA%\PhoneSync\DataReader\Indexes`. Rebuilding is atomic and produces the same logical record set when the same archive is selected repeatedly.

## Build

```powershell
dotnet restore .\PhoneSyncDataReader.csproj
dotnet build .\PhoneSyncDataReader.csproj -c Release
```

## Run

```powershell
dotnet run --project .\PhoneSyncDataReader.csproj
```

Choose the root of the synchronized OneDrive or local archive folder, then select **Build local index**.

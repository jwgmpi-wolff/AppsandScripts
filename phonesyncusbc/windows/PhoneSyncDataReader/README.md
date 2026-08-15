# Phone Sync Data Reader for Windows

Native Windows reader for an archived Phone Sync recovery folder.

## Download

- [Windows ARM64](../../releases/PhoneSyncDataReader-2.2.0-win-arm64.zip)  
	SHA-256: `09d02f75d12e9339c32aaf7c29ec69b3ea16a9d02bf2623fc1cd7f12c4aebb29`
- [Windows x64](../../releases/PhoneSyncDataReader-2.2.0-win-x64.zip)  
	SHA-256: `afb8a4769718b1c7c179787c972472e79487321418d2c9a67bf1e3a768111ca3`

Extract the self-contained package and run `PhoneSyncDataReader.exe`; no separate .NET installation is required.

## What it does

- Recursively reads the selected archive folder without modifying it.
- Catalogs files, images, video, voicemail, documents, and other recovered artifacts.
- Streams JSON, JSONL/NDJSON, and JSON files inside ZIP archives into a local SQLite index.
- Reads every non-sensitive item in SMS ZIP archives. Non-JSON attachments, media, XML, databases, voicemail, and opaque files are fully streamed and receive searchable archive-entry records with byte counts, SHA-256, and entry metadata.
- Flattens nested fields into queryable `fields` rows.
- Uses archive folders and ZIP entry folders as source, collection, folder, and record labels.
- Deduplicates files by SHA-256 and parsed records by canonical field hash.
- Searches titles, message bodies, field names, and field values.
- Presents message-friendly summaries, full flattened fields, and image previews.
- Excludes password and credential artifacts, including sensitive entries nested inside otherwise eligible ZIP files, from parsing and preview.

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

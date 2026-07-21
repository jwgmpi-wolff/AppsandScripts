#!/usr/bin/env python
"""Check stream detection in database."""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent / "cameras.db"

conn = sqlite3.connect(DB_PATH)
cur = conn.cursor()

# Total cameras
total = cur.execute("SELECT COUNT(*) FROM cameras").fetchone()[0]
print(f"Total cameras: {total}")

# Cameras with stream_url
with_stream = cur.execute("SELECT COUNT(*) FROM cameras WHERE stream_url IS NOT NULL AND stream_url != ''").fetchone()[0]
print(f"With stream_url: {with_stream}")

# Count by feed_type
feed_types = cur.execute("SELECT feed_type, COUNT(*) FROM cameras GROUP BY feed_type").fetchall()
print("\nFeed type breakdown:")
for ft, count in feed_types:
    print(f"  {ft}: {count}")

# Sample stream URLs
print("\nSample stream URLs:")
samples = cur.execute("SELECT title, stream_url, feed_type FROM cameras WHERE stream_url IS NOT NULL AND stream_url != '' LIMIT 10").fetchall()
for title, stream_url, feed_type in samples:
    print(f"  [{feed_type}] {title[:50]:50} → {stream_url[:80]}")

# Check for MJPEG streams (NYC cameras)
mjpeg_count = cur.execute("SELECT COUNT(*) FROM cameras WHERE stream_url LIKE '%mjpeg%'").fetchone()[0]
print(f"\nMJPEG streams: {mjpeg_count}")

# Check for HLS streams
hls_count = cur.execute("SELECT COUNT(*) FROM cameras WHERE stream_url LIKE '%.m3u8%'").fetchone()[0]
print(f"HLS streams: {hls_count}")

# NYC surveillance should have MJPEG
nyc_surveillance = cur.execute("SELECT COUNT(*) FROM cameras WHERE site_name = 'NYC TMC'").fetchone()[0]
nyc_with_stream = cur.execute("SELECT COUNT(*) FROM cameras WHERE site_name = 'NYC TMC' AND stream_url LIKE '%mjpeg%'").fetchone()[0]
print(f"\nNYC TMC cameras: {nyc_surveillance}, with MJPEG streams: {nyc_with_stream}")

conn.close()

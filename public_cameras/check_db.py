#!/usr/bin/env python
import sqlite3
conn = sqlite3.connect('cameras.db')
conn.row_factory = sqlite3.Row

# Count cameras by feed type
hls = conn.execute('SELECT COUNT(*) as cnt FROM cameras WHERE feed_type = "hls"').fetchone()
image = conn.execute('SELECT COUNT(*) as cnt FROM cameras WHERE feed_type = "image"').fetchone()
youtube = conn.execute('SELECT COUNT(*) as cnt FROM cameras WHERE source = "youtube" AND feed_type = "hls"').fetchone()

print(f'Total HLS: {hls["cnt"]}')
print(f'Total Images: {image["cnt"]}')
print(f'YouTube HLS: {youtube["cnt"]}')

# Get sample YouTube camera
row = conn.execute('SELECT id, title, stream_url FROM cameras WHERE source = "youtube" AND feed_type = "hls" LIMIT 1').fetchone()
if row:
    print(f'\nSample YouTube Camera:')
    print(f'ID: {row["id"]}')
    print(f'Title: {row["title"][:60]}')
    print(f'Stream URL: {row["stream_url"][:120]}...')
conn.close()

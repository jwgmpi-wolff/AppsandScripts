#!/usr/bin/env python
"""Check YouTube stream URLs."""
from agent.db import get_conn

conn = get_conn()

results = conn.execute('''
  SELECT title, stream_url, feed_type
  FROM cameras 
  WHERE source = ?
  LIMIT 5
''', ('youtube',)).fetchall()

print('YouTube streams in database:')
for title, stream_url, feed_type in results:
    print(f'\nTitle: {title[:60]}')
    print(f'Type: {feed_type}')
    print(f'Stream URL: {stream_url[:100] if stream_url else "N/A"}')

conn.close()

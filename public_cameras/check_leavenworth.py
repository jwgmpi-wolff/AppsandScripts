#!/usr/bin/env python
"""Check for Leavenworth camera."""
from agent.db import get_conn

conn = get_conn()

# Check YouTube cameras
yt_count = conn.execute('SELECT COUNT(*) FROM cameras WHERE source = ?', ('youtube',)).fetchone()[0]
print(f'YouTube cameras in DB: {yt_count}')

# Search for Leavenworth
results = conn.execute('''
  SELECT title, source, stream_url 
  FROM cameras 
  WHERE LOWER(title) LIKE '%leavenworth%'
  LIMIT 10
''').fetchall()

if results:
    print('\nLeavenworth cameras found:')
    for title, src, url in results:
        print(f'  Title: {title}')
        print(f'  Source: {src}')
        print(f'  Stream: {url[:80] if url else "N/A"}')
        print()
else:
    print('Leavenworth not found')
    
    # Show YouTube cameras
    print('\nYouTube cameras in database:')
    results = conn.execute('''
      SELECT title, source 
      FROM cameras 
      WHERE source = ?
      LIMIT 10
    ''', ('youtube',)).fetchall()
    
    if results:
        for title, src in results:
            print(f'  - {title[:70]}')
    else:
        print('  (none)')

conn.close()

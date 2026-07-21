#!/usr/bin/env python
"""Check what feed types exist in database."""
from agent.db import get_conn

conn = get_conn()

results = conn.execute('''
  SELECT feed_type, COUNT(*) as count
  FROM cameras 
  GROUP BY feed_type
  ORDER BY count DESC
''').fetchall()

print('Feed types in database:')
for feed_type, count in results:
    print(f'  {feed_type}: {count}')

# Get a sample of each type
print('\nSample cameras by type:')
for feed_type, _ in results:
    cam = conn.execute('''
      SELECT title, source, feed_type, stream_url
      FROM cameras 
      WHERE feed_type = ?
      LIMIT 1
    ''', (feed_type,)).fetchone()
    if cam:
        print(f'\n{feed_type}:')
        print(f'  Title: {cam[0][:60]}')
        print(f'  Source: {cam[1]}')
        print(f'  Stream URL: {str(cam[3])[:80] if cam[3] else "None"}')

conn.close()

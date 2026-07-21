#!/usr/bin/env python
"""Test search and database population."""
import logging
logging.basicConfig(level=logging.INFO)

from agent.crawler import run_search
from agent.db import get_conn

print('Running search...')
result = run_search(keyword='')

print(f"Search returned {result['total']} cameras")
print(f"Filtered: {result['filtered']}")
print(f"Cameras in response: {len(result['cameras'])}")

# Check database
conn = get_conn()
count = conn.execute('SELECT COUNT(*) FROM cameras').fetchone()[0]
print(f'Cameras in database: {count}')

# Show samples if any
if count > 0:
    rows = conn.execute('SELECT title, feed_type, source FROM cameras LIMIT 3').fetchall()
    print('\nSample cameras:')
    for title, ft, src in rows:
        print(f'  - {title[:50]} ({ft}, from {src})')

conn.close()

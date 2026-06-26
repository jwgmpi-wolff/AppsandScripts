import sqlite3
import requests
from collections import Counter

DB='cameras.db'
TIMEOUT=8
SAMPLE_LIMIT=300

conn=sqlite3.connect(DB)
cur=conn.cursor()
cur.execute("SELECT id,title,url FROM cameras ORDER BY discovered_at DESC LIMIT ?", (SAMPLE_LIMIT,))
rows=cur.fetchall()
conn.close()

results=[]
for row_id,title,url in rows:
    status='offline'
    code=None
    ctype=''
    err=''
    try:
        r=requests.head(url, allow_redirects=True, timeout=TIMEOUT)
        code=r.status_code
        ctype=(r.headers.get('content-type') or '').lower()
        if code==200 and (ctype.startswith('image/') or ctype.startswith('video/') or 'mpegurl' in ctype or ctype.startswith('application/octet-stream')):
            status='online'
        else:
            r2=requests.get(url, stream=True, allow_redirects=True, timeout=TIMEOUT)
            code=r2.status_code
            ctype=(r2.headers.get('content-type') or '').lower()
            if code==200 and (ctype.startswith('image/') or ctype.startswith('video/') or 'mpegurl' in ctype or ctype.startswith('application/octet-stream')):
                status='online'
    except Exception as e:
        err=str(e)
    results.append((row_id,title,url,status,code,ctype,err))

counts=Counter([r[3] for r in results])
print('Tested:', len(results))
print('Online:', counts.get('online',0))
print('Offline:', counts.get('offline',0))

print('\nSample offline entries:')
shown=0
for r in results:
    if r[3]=='offline':
        print(f"- {r[1]} => {r[2]} | code={r[4]} ctype={r[5]} err={r[6]}")
        shown += 1
        if shown >= 15:
            break

print('\nSample online entries:')
shown=0
for r in results:
    if r[3]=='online':
        print(f"- {r[1]} => {r[2]} | code={r[4]} ctype={r[5]}")
        shown += 1
        if shown >= 10:
            break

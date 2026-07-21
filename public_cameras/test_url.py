#!/usr/bin/env python
import requests

resp = requests.post('http://localhost:7000/api/camera/8563/refresh-youtube')
data = resp.json()

if data.get('ok'):
    url = data['stream_url']
    print(f'URL length: {len(url)}')
    print(f'Has expire: {"expire=" in url}')
    print(f'Starts with https: {url.startswith("https")}')
    print(f'Contains manifest: {"manifest.googlevideo.com" in url}')
    print(f'Has /id/: {"/id/" in url}')
    print(f'First 100 chars: {url[:100]}')
    print(f'Last 50 chars: {url[-50:]}')
else:
    print(f'Error: {data.get("error")}')

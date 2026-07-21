#!/usr/bin/env python
"""Test normalize_camera."""
from agent.crawler import _normalize_camera, _looks_direct_media

# Test with a real camera dict
test_cam = {
    'title': 'Test Camera',
    'image_url': 'https://cdn.skylinewebcams.com/live1670.jpg',
    'stream_url': 'https://cdn.skylinewebcams.com/live1670.jpg',
    'feed_type': 'image',
    'url': 'https://cdn.skylinewebcams.com/live1670.jpg'
}

print('Testing _normalize_camera...')
result = _normalize_camera(test_cam)
if result:
    print(f'Result: {result}')
    print(f'  image_url: {result.get("image_url")}')
    print(f'  stream_url: {result.get("stream_url")}')
    print(f'  feed_type: {result.get("feed_type")}')
else:
    print('Result: None (filtered out)')

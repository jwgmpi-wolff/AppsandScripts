#!/usr/bin/env python
import asyncio
from agent.sources.youtube import fetch_cameras
from agent.db import init_db, upsert_cameras

async def refresh():
    init_db()
    # Fetch YouTube cameras
    cameras = await fetch_cameras(None)
    print(f'Found {len(cameras)} YouTube cameras')
    
    # Show first camera's stream URL
    if cameras:
        cam = cameras[0]
        print(f'Camera: {cam["title"][:60]}')
        print(f'Stream URL: {cam["stream_url"][:100]}')
        # Upsert to update database
        upsert_cameras(cameras)
        print('Updated database')
    else:
        print('No cameras found!')

asyncio.run(refresh())

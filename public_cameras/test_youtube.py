#!/usr/bin/env python
"""Test YouTube source."""
import asyncio
import logging
logging.basicConfig(level=logging.INFO)

from agent.sources import youtube
import httpx

async def test():
    async with httpx.AsyncClient() as client:
        cams = await youtube.fetch_cameras(client)
        print(f'YouTube source returned: {len(cams)} cameras')
        if cams:
            for cam in cams[:5]:
                print(f'  - {cam["title"]}')
                print(f'    Source: {cam["source"]}')

asyncio.run(test())

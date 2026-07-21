#!/usr/bin/env python
"""Test camera insertion."""
import sqlite3
from pathlib import Path

# Create test camera
test_camera = {
    "title": "Test Camera",
    "url": "https://example.com/camera",
    "image_url": "https://example.com/image.jpg",
    "stream_url": "https://example.com/stream.m3u8",
    "feed_type": "hls",
    "location": "Test Location",
    "country": "USA",
    "state": "CA",
    "city": "San Francisco",
    "latitude": 37.7749,
    "longitude": -122.4194,
    "site_name": "Test Site",
    "description": "Test Description",
    "tags": "test,camera",
    "source": "test",
    "keywords": "test camera"
}

# Try to insert
from agent.db import upsert_cameras

result = upsert_cameras([test_camera])
print(f"Inserted: {result}")

# Check database
DB_PATH = Path(__file__).resolve().parent / "cameras.db"
conn = sqlite3.connect(DB_PATH)
cur = conn.cursor()
count = cur.execute("SELECT COUNT(*) FROM cameras").fetchone()[0]
print(f"Total cameras in database: {count}")
conn.close()

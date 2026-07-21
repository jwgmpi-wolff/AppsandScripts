#!/usr/bin/env python
"""Trigger search to discover streams."""
import requests
import json

print("Triggering API search...")
try:
    resp = requests.post("http://localhost:7000/api/search", json={"keyword": ""})
    print(f"Status code: {resp.status_code}")
    data = resp.json()
    print(f"Response: {json.dumps(data, indent=2)}")
except Exception as e:
    print(f"Error: {e}")

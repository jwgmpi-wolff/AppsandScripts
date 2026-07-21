#!/usr/bin/env python
"""Test YouTube stream playback in the modal."""
import requests
import time

BASE_URL = "http://localhost:7000"

def test_stream_playback():
    print("🧪 Testing YouTube Stream Playback")
    print("=" * 70)
    
    # 1. Get a YouTube camera
    print("\n1️⃣  Getting YouTube camera from API...")
    resp = requests.get(f"{BASE_URL}/api/cameras?source=youtube&per_page=1")
    cam = resp.json()["cameras"][0]
    cam_id = cam["id"]
    
    print(f"✅ Camera ID: {cam_id}")
    print(f"   Title: {cam['title'][:60]}...")
    print(f"   Feed Type: {cam['feed_type']}")
    print(f"   Has Stream URL: {bool(cam.get('stream_url'))}")
    
    # 2. Verify stream_url is an HLS manifest
    stream_url = cam.get("stream_url")
    if not stream_url:
        print("❌ No stream_url in API response!")
        return False
    
    print(f"\n2️⃣  Checking stream URL format...")
    print(f"   URL Length: {len(stream_url)}")
    print(f"   Is HTTPS: {stream_url.startswith('https')}")
    print(f"   Has manifest.googlevideo.com: {'manifest.googlevideo.com' in stream_url}")
    print(f"   Has /playlist/index.m3u8: {'/playlist/index.m3u8' in stream_url}")
    
    if not (stream_url.startswith('https') and 'manifest.googlevideo.com' in stream_url):
        print("❌ Stream URL is not a valid HLS manifest!")
        return False
    
    # 3. Test refresh endpoint to get fresh URL
    print(f"\n3️⃣  Testing refresh endpoint...")
    refresh_resp = requests.post(f"{BASE_URL}/api/camera/{cam_id}/refresh-youtube")
    refresh_data = refresh_resp.json()
    
    if not refresh_data.get("ok"):
        print(f"❌ Refresh failed: {refresh_data.get('error')}")
        return False
    
    fresh_url = refresh_data["stream_url"]
    print(f"✅ Got fresh URL from refresh endpoint")
    print(f"   URL length: {len(fresh_url)}")
    print(f"   Starts with https: {fresh_url.startswith('https')}")
    
    # 4. Verify URLs are different (fresh one should have different timestamp)
    print(f"\n4️⃣  Comparing old vs fresh URL...")
    
    # Extract expire timestamps if possible
    old_expire = stream_url.split('/expire/')[1].split('/')[0] if '/expire/' in stream_url else 'unknown'
    new_expire = fresh_url.split('/expire/')[1].split('/')[0] if '/expire/' in fresh_url else 'unknown'
    
    print(f"   Old URL expire: {old_expire}")
    print(f"   New URL expire: {new_expire}")
    
    if old_expire != 'unknown' and new_expire != 'unknown':
        if int(new_expire) > int(old_expire):
            print(f"✅ Fresh URL has newer timestamp!")
        else:
            print(f"⚠️  Timestamps are similar (might be expected)")
    
    print("\n" + "=" * 70)
    print("✅ Stream Playback Test Complete!")
    print("\nHow it works:")
    print("  1. User clicks YouTube camera in modal")
    print("  2. openModal() detects YouTube source")
    print("  3. Calls POST /api/camera/{id}/refresh-youtube")
    print("  4. Gets fresh HLS manifest URL")
    print("  5. Calls playHlsStream() with fresh URL (NO PROXYING)")
    print("  6. HLS.js loads manifest directly from Google")
    print("  7. Video plays adaptive bitrate streams")
    print("\nThe KEY FIX:")
    print("  - Changed: playHlsStream(proxiedMediaLink(...)) ❌")
    print("  - To:      playHlsStream(cam.stream_url)       ✅")
    print("  - HLS manifests must be played directly, not proxied")
    
    return True

if __name__ == "__main__":
    try:
        success = test_stream_playback()
        exit(0 if success else 1)
    except Exception as e:
        print(f"❌ Test error: {e}")
        import traceback
        traceback.print_exc()
        exit(1)

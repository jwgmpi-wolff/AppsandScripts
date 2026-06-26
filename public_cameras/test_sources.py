"""Quick test of each camera source."""
import asyncio
import httpx

async def main():
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept-Language": "en-US,en;q=0.9",
    }
    limits  = httpx.Limits(max_connections=20)
    timeout = httpx.Timeout(30.0, connect=10.0)

    async with httpx.AsyncClient(headers=headers, limits=limits, timeout=timeout) as client:

        # --- AlertWildfire ---
        try:
            r = await client.get("https://data.alertwildfire.org/api/firehawks-rv3/latest")
            d = r.json()
            feats = d.get("features", [])
            print(f"AlertWildfire: HTTP {r.status_code}  features={len(feats)}")
            if feats:
                p = feats[0].get("properties") or {}
                print("  First cam props:", list(p.keys())[:12])
                print("  Sample:", {k: p[k] for k in list(p.keys())[:5]})
        except Exception as e:
            print(f"AlertWildfire FAILED: {e}")

        # --- Caltrans D7 ---
        try:
            r = await client.get("https://cwwp2.dot.ca.gov/data/d7/cc/ccTVData.json")
            print(f"Caltrans D7: HTTP {r.status_code}  len={len(r.text)}")
            if r.status_code == 200:
                try:
                    d = r.json()
                    print("  Type:", type(d), "keys:" if isinstance(d, dict) else "len:", (list(d.keys())[:5] if isinstance(d, dict) else len(d)))
                except Exception as je:
                    print("  JSON parse failed:", je)
                    print("  Raw (first 300):", r.text[:300])
        except Exception as e:
            print(f"Caltrans D7 FAILED: {e}")

        # --- COTRIP ---
        try:
            r = await client.get("https://cotrip.org/speed/getCameras.do")
            print(f"COTRIP: HTTP {r.status_code}  len={len(r.text)}")
        except Exception as e:
            print(f"COTRIP FAILED: {e}")

        # --- UDOT ---
        try:
            r = await client.get("https://www.udottraffic.utah.gov/1.0/udotcamera")
            print(f"UDOT: HTTP {r.status_code}  len={len(r.text)}")
        except Exception as e:
            print(f"UDOT FAILED: {e}")

        # --- TripCheck ---
        try:
            r = await client.get("https://www.tripcheck.com/tripcheck/Cameras",
                                 headers={"Accept": "application/json"})
            print(f"TripCheck: HTTP {r.status_code}  len={len(r.text)}")
        except Exception as e:
            print(f"TripCheck FAILED: {e}")

asyncio.run(main())

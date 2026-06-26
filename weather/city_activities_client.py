# city_client.py
import asyncio
import os
import sys
from pathlib import Path
from fastmcp import Client
from fastmcp.client.transports import StdioTransport

HERE = Path(__file__).resolve().parent
SERVER = str(HERE / "city_server.py")

def _get(flag: str, default=None):
    argv = sys.argv
    if flag in argv:
        i = argv.index(flag)
        if i + 1 >= len(argv):
            raise SystemExit(f"Missing value after {flag}")
        return argv[i + 1]
    return default

def _require(name: str) -> str:
    v = os.environ.get(name)
    if not v:
        raise RuntimeError(f"{name} not set")
    return v

async def main():
    tool = _get("--tool", "get_city_activities")
    city = _get("--city") or (_get("--c") or (sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else None))
    if not city:
        raise SystemExit("City required. Example: python city_client.py --tool get_city_activities --city Seattle")

    category = _get("--category", "things_to_do")
    limit = int(_get("--limit", "10"))

    # Build env for server process (STDIO servers do not inherit your shell env by default). 【4-d4c320】
    env = {}

    # Add keys only if present (so weather-only can still run without Yelp, etc.)
    for k in ["OPENWEATHER_API_KEY", "TICKETMASTER_API_KEY", "YELP_API_KEY", "OPENTRIPMAP_API_KEY"]:
        if os.environ.get(k):
            env[k] = os.environ[k]

    transport = StdioTransport(
        command=sys.executable,
        args=[SERVER],
        cwd=str(HERE),
        env=env,
        keep_alive=False
    )

    client = Client(transport)

    # Tool args dictionary (FastMCP call_tool takes name + dict). 【9-bcf659】
    args = {"city": city}
    if tool in ("get_city_activities", "plan_city_day"):
        args["category"] = category
        args["limit"] = limit

    async with client:
        result = await client.call_tool(tool, args)
        print(result.data)

if __name__ == "__main__":
    asyncio.run(main())

import asyncio
import os
import sys
from pathlib import Path

from fastmcp import Client
from fastmcp.client.transports import StdioTransport

HERE = Path(__file__).resolve().parent
SERVER = str(HERE / "weather_mcp.py")

async def main():
    api_key = os.environ.get("OPENWEATHER_API_KEY")
    if not api_key:
        raise RuntimeError("OPENWEATHER_API_KEY is not set in this process")

    transport = StdioTransport(
        command=sys.executable,              # ✅ use the venv interpreter running this script
        args=[SERVER],                       # ✅ explicit path to the server script
        cwd=str(HERE),                       # ✅ stable working directory
        env={"OPENWEATHER_API_KEY": api_key} # ✅ pass key into isolated stdio env
        # keep_alive=False,                  # optional if you want a fresh process each run
    )

    client = Client(transport)

    async with client:
        result = await client.call_tool("get_weather", {"city": "Hawaii"})
        print(result.data)

asyncio.run(main())
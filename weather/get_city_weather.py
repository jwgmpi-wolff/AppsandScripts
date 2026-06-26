import asyncio
import os
import sys
from pathlib import Path

from fastmcp import Client
from fastmcp.client.transports import StdioTransport

HERE = Path(__file__).resolve().parent
SERVER = str(HERE / "weather_mcp.py")


def get_city_from_args() -> str:
    # Accept either:
    #   python get_city_weather.py Seattle
    # or:
    #   python get_city_weather.py --city Seattle
    if "--city" in sys.argv:
        i = sys.argv.index("--city")
        if i + 1 >= len(sys.argv):
            raise SystemExit("Missing value after --city")
        return sys.argv[i + 1]

    # positional city: first arg after script name
    if len(sys.argv) >= 2:
        return sys.argv[1]

    # default (optional)
    return "Seattle"


async def main():
    city = get_city_from_args()

    api_key = os.environ.get("OPENWEATHER_API_KEY")
    if not api_key:
        raise RuntimeError("OPENWEATHER_API_KEY is not set in this process")

    transport = StdioTransport(
        command=sys.executable,               # ✅ venv interpreter running this script
        args=[SERVER],                        # ✅ server script path
        cwd=str(HERE),                        # ✅ stable working directory
        env={"OPENWEATHER_API_KEY": api_key}, # ✅ pass key into isolated stdio env
        # keep_alive=False,                   # optional: fresh process per run
    )

    client = Client(transport)

    async with client:
        # IMPORTANT: tool name must match your server tool exactly:
        # "get_weather" vs "get_weather" / "get_weather" etc.
        result = await client.call_tool("get_weather", {"city": city})
        print(result.data)


if __name__ == "__main__":
    asyncio.run(main())
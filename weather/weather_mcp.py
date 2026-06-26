from fastmcp import FastMCP
from typing import TypedDict
import os
import httpx

mcp = FastMCP("weather")

class WeatherResult(TypedDict):
    city: str
    temperature_f: float
    condition: str

@mcp.tool
def get_weather(city: str) -> WeatherResult:
    """
    Get current weather for a city using OpenWeatherMap.
    """
    api_key = os.environ.get("OPENWEATHER_API_KEY")
    if not api_key:
        raise RuntimeError("OPENWEATHER_API_KEY not set")

    resp = httpx.get(
        "https://api.openweathermap.org/data/2.5/weather",
        params={
            "q": city,
            "appid": api_key,
            "units": "imperial",
        },
        timeout=10,
    )

    resp.raise_for_status()
    data = resp.json()

    return {
        "city": data["name"],
        "temperature_f": data["main"]["temp"],
        "condition": data["weather"][0]["main"],
    }

if __name__ == "__main__":
    mcp.run()
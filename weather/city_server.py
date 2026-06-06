# city_server.py
from fastmcp import FastMCP
from typing import TypedDict, Literal
import os
import httpx
import sys

mcp = FastMCP("city")

class WeatherResult(TypedDict):
    city: str
    temperature_f: float
    condition: str

class Activity(TypedDict):
    name: str
    category: str
    url: str | None

class ActivitiesResult(TypedDict):
    city: str
    category: str
    activities: list[Activity]

def _log(msg: str) -> None:
    print(msg, file=sys.stderr)

@mcp.tool
def get_weather(city: str) -> WeatherResult:
    """Get current weather for a city (OpenWeatherMap)."""
    api_key = os.environ.get("OPENWEATHER_API_KEY")
    if not api_key:
        raise RuntimeError("OPENWEATHER_API_KEY not set")

    resp = httpx.get(
        "https://api.openweathermap.org/data/2.5/weather",
        params={"q": city, "appid": api_key, "units": "imperial"},
        timeout=10,
    )
    resp.raise_for_status()
    data = resp.json()
    return {
        "city": data["name"],
        "temperature_f": float(data["main"]["temp"]),
        "condition": data["weather"][0]["main"],
    }

@mcp.tool
def get_city_activities(city: str, category: str = "things_to_do") -> ActivitiesResult:
    """Get activities for a city (calls an activities provider API)."""
    # Suggestion: use a provider like Eventbrite/Ticketmaster/Yelp/etc.
    # This is your "another tool/provider" behind the scenes.
    activities_api_key = os.environ.get("ACTIVITIES_API_KEY")
    if not activities_api_key:
        raise RuntimeError("ACTIVITIES_API_KEY not set")

    # Example placeholder call (replace with your provider endpoint/params)
    # IMPORTANT: don't print to stdout in STDIO servers. 【5-41ab8c】
    _log(f"Fetching activities for {city} ({category})")

    # TODO: Implement provider call here
    return {"city": city, "category": category, "activities": []}

if __name__ == "__main__":
    mcp.run()
import requests

BASE_URL = "http://localhost:5000"

test_cases = [
    {"query": "microsoft public profile", "source": "duckduckgo", "max_results": 5},
    {"query": "microsoft public profile", "source": "brave", "max_results": 5},
    {"query": "microsoft public profile", "source": "all", "max_results": 5},
]

for payload in test_cases:
    response = requests.post(f"{BASE_URL}/search", data=payload, timeout=30)
    print("=" * 80)
    print("payload:", payload)
    print("status:", response.status_code)
    data = response.json()
    print("result_count:", len(data.get("results", [])))
    print("fetch_errors:", data.get("fetch_errors", []))
    for item in data.get("results", [])[:5]:
        print("-", item.get("source"), item.get("title"), item.get("url"))

"""
EDS Data Scraper — Extracts speed camera coordinates from the EGM EDS map service.

Strategy:
  1. Try direct HTTP requests to discovered XHR/REST endpoints first.
  2. Fall back to Selenium headless Chrome if the site requires JS rendering
     or uses encrypted tokens.

Usage:
  pip install -r requirements.txt
  python scraper.py --output raw_data.json
  python scraper.py --output raw_data.json --use-selenium
"""

import argparse
import json
import sys
import time
from pathlib import Path

import requests

# ─── Configuration ───────────────────────────────────────────────────

# TODO: Replace with the actual XHR endpoint URL discovered via Chrome DevTools.
# Open https://www.egm.gov.tr/edsharita.aspx, press F12 → Network → XHR,
# interact with the map, and find the JSON/Protobuf endpoint.
DEFAULT_ENDPOINT = "https://www.egm.gov.tr/api/eds/points"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Accept": "application/json, text/javascript, */*; q=0.01",
    "X-Requested-With": "XMLHttpRequest",
    "Referer": "https://www.egm.gov.tr/edsharita.aspx",
}


# ─── Direct HTTP Strategy ────────────────────────────────────────────

def scrape_http(endpoint: str) -> list[dict]:
    """
    Attempt to fetch EDS data from a direct REST endpoint.
    Returns a list of raw dictionaries.
    """
    print(f"[HTTP] Fetching data from: {endpoint}")
    try:
        response = requests.get(endpoint, headers=HEADERS, timeout=30)
        response.raise_for_status()

        data = response.json()

        # Handle various response shapes
        if isinstance(data, list):
            print(f"[HTTP] Received {len(data)} points")
            return data
        elif isinstance(data, dict):
            # Try common wrapper keys
            for key in ("data", "result", "features", "points", "items"):
                if key in data and isinstance(data[key], list):
                    print(f"[HTTP] Received {len(data[key])} points (under '{key}')")
                    return data[key]

        print(f"[HTTP] Unexpected response shape: {type(data)}")
        return []

    except requests.RequestException as e:
        print(f"[HTTP] Request failed: {e}")
        return []
    except json.JSONDecodeError:
        print("[HTTP] Response is not valid JSON")
        return []


# ─── Selenium Fallback Strategy ──────────────────────────────────────

def scrape_selenium(url: str = "https://www.egm.gov.tr/edsharita.aspx") -> list[dict]:
    """
    Fall back to headless Chrome if the site requires JS rendering.
    Intercepts network responses to capture the data payload.
    """
    try:
        from selenium import webdriver
        from selenium.webdriver.chrome.options import Options
        from selenium.webdriver.chrome.service import Service
        from selenium.webdriver.common.by import By
        from selenium.webdriver.support.ui import WebDriverWait
        from selenium.webdriver.support import expected_conditions as EC
    except ImportError:
        print("[Selenium] selenium not installed. Run: pip install selenium")
        return []

    print(f"[Selenium] Opening headless browser at: {url}")

    options = Options()
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument(f"user-agent={HEADERS['User-Agent']}")

    # Enable network interception via performance logging
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})

    driver = webdriver.Chrome(options=options)
    captured = []

    try:
        driver.get(url)
        # Wait for the map to fully load
        time.sleep(8)

        # Try scrolling/zooming to trigger tile or data loads
        driver.execute_script("window.scrollTo(0, document.body.scrollHeight);")
        time.sleep(3)

        # Extract network responses from performance logs
        logs = driver.get_log("performance")
        for entry in logs:
            log_data = json.loads(entry["message"])
            message = log_data.get("message", {})

            if message.get("method") == "Network.responseReceived":
                resp = message.get("params", {}).get("response", {})
                resp_url = resp.get("url", "")
                mime = resp.get("mimeType", "")

                if "json" in mime or "eds" in resp_url.lower() or "point" in resp_url.lower():
                    request_id = message["params"]["requestId"]
                    try:
                        body = driver.execute_cdp_cmd(
                            "Network.getResponseBody", {"requestId": request_id}
                        )
                        content = json.loads(body.get("body", "[]"))
                        if isinstance(content, list) and len(content) > 0:
                            print(f"[Selenium] Captured {len(content)} items from {resp_url}")
                            captured.extend(content)
                        elif isinstance(content, dict):
                            for key in ("data", "result", "features", "points"):
                                if key in content and isinstance(content[key], list):
                                    print(f"[Selenium] Captured {len(content[key])} items")
                                    captured.extend(content[key])
                    except Exception:
                        pass

        print(f"[Selenium] Total captured items: {len(captured)}")

    finally:
        driver.quit()

    return captured


# ─── Main ────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="EDS Camera Data Scraper")
    parser.add_argument("--output", default="raw_data.json", help="Output file path")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="REST API endpoint")
    parser.add_argument("--use-selenium", action="store_true",
                        help="Use Selenium headless browser instead of direct HTTP")
    args = parser.parse_args()

    if args.use_selenium:
        data = scrape_selenium()
    else:
        data = scrape_http(args.endpoint)

    if not data:
        print("\n⚠  No data retrieved. Please check:")
        print("   1. The endpoint URL is correct (use Chrome DevTools to discover it)")
        print("   2. The site is accessible and not blocking automated requests")
        print("   3. Try --use-selenium flag for JS-rendered content")
        sys.exit(1)

    output_path = Path(args.output)
    output_path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\n✓ Saved {len(data)} entries to {output_path}")


if __name__ == "__main__":
    main()

"""OpenDART '공시검색'(list) API 테스트: 최근 공시 목록 조회."""

import json
import os
import sys

import requests
from dotenv import load_dotenv

LIST_URL = "https://opendart.fss.or.kr/api/list.json"


def fetch_recent_disclosures(api_key: str, page_count: int = 10) -> dict:
    params = {
        "crtfc_key": api_key,
        "page_no": 1,
        "page_count": page_count,
        "sort": "date",
        "sort_mth": "desc",
    }
    resp = requests.get(LIST_URL, params=params, timeout=10)
    resp.raise_for_status()
    return resp.json()


def main() -> None:
    load_dotenv()
    api_key = os.getenv("DART_API_KEY")
    if not api_key:
        sys.exit("DART_API_KEY가 .env에 설정되어 있지 않습니다.")

    data = fetch_recent_disclosures(api_key)
    print(json.dumps(data, indent=2, ensure_ascii=False))

    if data.get("status") != "000":
        sys.exit(f"API 오류: {data.get('status')} - {data.get('message')}")


if __name__ == "__main__":
    main()

"""OpenDART '공시원문'(document) API 테스트.

최근 공시 목록에서 rcept_no를 하나 가져온 뒤 원문을 내려받아
실제 파일 형식(ZIP/XML/HTML)과 내부 구조를 확인한다.
"""

import io
import json
import os
import sys
import zipfile

import requests
from dotenv import load_dotenv

LIST_URL = "https://opendart.fss.or.kr/api/list.json"
DOCUMENT_URL = "https://opendart.fss.or.kr/api/document.xml"


def get_recent_disclosures(api_key: str, page_count: int = 10) -> list[dict]:
    params = {
        "crtfc_key": api_key,
        "page_no": 1,
        "page_count": page_count,
        "sort": "date",
        "sort_mth": "desc",
    }
    resp = requests.get(LIST_URL, params=params, timeout=10)
    resp.raise_for_status()
    data = resp.json()
    if data.get("status") != "000":
        sys.exit(f"목록 조회 실패: {data.get('status')} - {data.get('message')}")
    return data["list"]


def fetch_document(api_key: str, rcept_no: str) -> bytes:
    params = {"crtfc_key": api_key, "rcept_no": rcept_no}
    resp = requests.get(DOCUMENT_URL, params=params, timeout=10)
    resp.raise_for_status()
    return resp.content


def is_no_document_error(raw: bytes) -> bool:
    # 원문이 없는 공시 유형은 ZIP 대신 status=014 XML 에러를 준다.
    return not zipfile.is_zipfile(io.BytesIO(raw)) and b"<status>014</status>" in raw


def inspect_content(raw: bytes) -> None:
    print(f"응답 바이트 크기: {len(raw)}")
    print(f"매직 넘버(hex): {raw[:8].hex()}")

    if zipfile.is_zipfile(io.BytesIO(raw)):
        print("형식: ZIP 파일")
        with zipfile.ZipFile(io.BytesIO(raw)) as zf:
            names = zf.namelist()
            print(f"ZIP 내부 파일 목록: {names}")
            for name in names:
                inner = zf.read(name)
                print(f"\n--- {name} (크기: {len(inner)} bytes) ---")
                for enc in ("utf-8", "euc-kr", "cp949"):
                    try:
                        text = inner.decode(enc)
                        print(f"디코딩 성공 (encoding={enc}), 앞부분 500자:")
                        print(text[:500])
                        break
                    except UnicodeDecodeError:
                        continue
                else:
                    print("텍스트 디코딩 실패 (바이너리로 추정)")
    else:
        # ZIP이 아니면 보통 JSON 에러 응답이거나 순수 XML/HTML 텍스트
        text_preview = raw[:500]
        try:
            decoded = text_preview.decode("utf-8")
        except UnicodeDecodeError:
            decoded = text_preview.decode("euc-kr", errors="replace")
        print("형식: ZIP 아님 (에러 응답 또는 순수 텍스트로 추정)")
        print("앞부분 미리보기:")
        print(decoded)


def main() -> None:
    load_dotenv()
    api_key = os.getenv("DART_API_KEY")
    if not api_key:
        sys.exit("DART_API_KEY가 .env에 설정되어 있지 않습니다.")

    candidates = get_recent_disclosures(api_key)

    for item in candidates:
        rcept_no, report_nm = item["rcept_no"], item["report_nm"]
        raw = fetch_document(api_key, rcept_no)
        if is_no_document_error(raw):
            print(f"(원문 없음, 건너뜀) rcept_no={rcept_no}, report_nm={report_nm}")
            continue
        print(f"샘플 공시: rcept_no={rcept_no}, report_nm={report_nm}\n")
        inspect_content(raw)
        break
    else:
        sys.exit("최근 목록에서 원문을 제공하는 공시를 찾지 못했습니다.")


if __name__ == "__main__":
    main()

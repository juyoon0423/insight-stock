import io
import os
import zipfile

import requests
from dotenv import load_dotenv

load_dotenv("/Users/bagjuyun/insight-stock/.env")
api_key = os.getenv("DART_API_KEY")

candidates = [
    ("20260721000157", "[기재정정]주식등의대량보유상황보고서(일반)"),
    ("20260721000155", "투자설명서(일괄신고)"),
    ("20260721000150", "합병등종료보고서(합병)"),
    ("20260721000138", "주식등의대량보유상황보고서(일반)"),
]

for rcept_no, name in candidates:
    resp = requests.get(
        "https://opendart.fss.or.kr/api/document.xml",
        params={"crtfc_key": api_key, "rcept_no": rcept_no},
        timeout=10,
    )
    raw = resp.content
    is_zip = zipfile.is_zipfile(io.BytesIO(raw))
    print(f"{rcept_no} ({name}): size={len(raw)}, is_zip={is_zip}")
    if not is_zip:
        print("  ->", raw[:200])
    else:
        with zipfile.ZipFile(io.BytesIO(raw)) as zf:
            print("  -> zip contents:", zf.namelist())

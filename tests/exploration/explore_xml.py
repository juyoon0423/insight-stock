import io
import os
import re
import zipfile
import xml.etree.ElementTree as ET

import requests
from dotenv import load_dotenv

load_dotenv("/Users/bagjuyun/insight-stock/.env")
api_key = os.getenv("DART_API_KEY")
RCEPT_NO = "20260721000160"

resp = requests.get(
    "https://opendart.fss.or.kr/api/document.xml",
    params={"crtfc_key": api_key, "rcept_no": RCEPT_NO},
    timeout=10,
)
with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
    name = zf.namelist()[0]
    xml_bytes = zf.read(name)

xml_text = xml_bytes.decode("utf-8")
root = ET.fromstring(xml_bytes)

# ---------- 1) 전체 본문 텍스트(태그 제거) ----------
def normalize_ws(s: str) -> str:
    return re.sub(r"[ \t]+", " ", s).strip()

all_text_raw = "".join(root.itertext())
lines = [normalize_ws(line) for line in all_text_raw.splitlines()]
lines = [line for line in lines if line]
full_text = "\n".join(lines)

print("=" * 70)
print("1) 태그 제거 후 본문 텍스트 (앞부분 1500자)")
print("=" * 70)
print(full_text[:1500])

print()
print("=" * 70)
print(f"3) 전체 텍스트 길이: {len(full_text)}자 (줄 수: {len(lines)})")
print("=" * 70)

# ---------- 2) TABLE 구조 ----------
print()
print("=" * 70)
print("2) TABLE 구조 -> 텍스트 변환 예시")
print("=" * 70)

tables = root.findall(".//TABLE")
print(f"문서 내 TABLE 개수: {len(tables)}")

for i, table in enumerate(tables[:3]):
    print(f"\n--- TABLE #{i} ---")
    rows = table.findall(".//TR")
    for row in rows[:6]:
        cells = row.findall("./TD")
        cell_texts = [normalize_ws("".join(td.itertext())) for td in cells]
        print(" | ".join(cell_texts))

# ---------- 비교: 훨씬 큰 문서(투자설명서) ----------
print()
print("=" * 70)
print("비교용: 투자설명서(더 큰 문서) 통계")
print("=" * 70)

RCEPT_NO2 = "20260721000155"
resp2 = requests.get(
    "https://opendart.fss.or.kr/api/document.xml",
    params={"crtfc_key": api_key, "rcept_no": RCEPT_NO2},
    timeout=15,
)
with zipfile.ZipFile(io.BytesIO(resp2.content)) as zf2:
    name2 = zf2.namelist()[0]
    xml_bytes2 = zf2.read(name2)

try:
    root2 = ET.fromstring(xml_bytes2)
    all_text2 = "".join(root2.itertext())
    table_count2 = len(root2.findall(".//TABLE"))
except ET.ParseError as e:
    # 이 문서(투자설명서)는 well-formed XML이 아니다: 본문 텍스트에
    # 이스케이프되지 않은 "<표1>" 같은 문자열이 있어 태그로 오인됨.
    # (docs/dart-findings.md §2-2 참고) 정규식 기반 태그 제거로 폴백한다.
    print(f"ElementTree 파싱 실패({e}) -> 정규식 폴백 사용")
    text2 = xml_bytes2.decode("utf-8")
    all_text2 = re.sub(r"<[^>]+>", "\n", text2)
    table_count2 = len(re.findall(r"<TABLE[ >]", text2))

lines2 = [normalize_ws(l) for l in all_text2.splitlines()]
lines2 = [l for l in lines2 if l]
full_text2 = "\n".join(lines2)

print(f"XML 크기: {len(xml_bytes2)} bytes")
print(f"추출 텍스트 길이: {len(full_text2)}자 (줄 수: {len(lines2)})")
print(f"TABLE 개수: {table_count2}")

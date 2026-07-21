# OpenDART API 탐색 기록

Open DART API(`https://opendart.fss.or.kr/api`)의 "공시검색"(list)과 "공시원문"(document)
API를 실제로 호출해보며 확인한 응답 구조와 함정, 그리고 이후 RAG 파이프라인을 만들 때
고려할 청킹 전략을 정리한다.

## 1. 응답 구조

### 1-1. list API (`GET /api/list.json`)

공시 목록을 조회한다.

**주요 요청 파라미터**
- `crtfc_key` (필수): 발급받은 인증키
- `corp_code`: 특정 회사만 조회 (생략 시 전체 회사)
- `bgn_de`, `end_de`: 조회 기간 (생략 시 최근 기간 기본값 적용)
- `page_no`, `page_count`: 페이징
- `sort`, `sort_mth`: 정렬 기준/방향 (예: `date`, `desc`)

**응답 구조**
```json
{
  "status": "000",
  "message": "정상",
  "page_no": 1,
  "page_count": 10,
  "total_count": 123,
  "total_page": 13,
  "list": [
    {
      "corp_code": "01786958",
      "corp_name": "큐리오시스",
      "stock_code": "494120",
      "corp_cls": "K",
      "report_nm": "임원ㆍ주요주주특정증권등소유상황보고서",
      "rcept_no": "20260721000162",
      "flr_nm": "허대성",
      "rcept_dt": "20260721",
      "rm": ""
    }
  ]
}
```

**주요 필드**
- `status` / `message`: 응답 코드. `000`이 정상, 그 외는 에러(§2-1 참고)
- `rcept_no`: 접수번호. document API 호출 시 핵심 키
- `report_nm`: 공시 유형명. 원문 존재 여부/문서 크기를 가늠하는 힌트가 됨 (§3 참고)
- `corp_code` / `corp_name` / `stock_code`: 회사 식별 정보
- `corp_cls`: 법인구분 (K=코스닥, Y=유가증권, N=코넥스, E=기타)
- `rm`: 비고 (코스닥/유가증권 표시 등)

### 1-2. document API (`GET /api/document.xml`)

특정 공시 1건의 원문을 내려받는다.

**요청 파라미터**
- `crtfc_key` (필수)
- `rcept_no` (필수): list API에서 얻은 접수번호

**응답 형식**
- 정상일 때: **ZIP 파일** (Content가 `PK\x03\x04`로 시작). 내부에 `{rcept_no}.xml` 파일 하나가 들어있음 (UTF-8 인코딩)
- 실패일 때: ZIP이 아니라 순수 XML 에러 응답. 예:
  ```xml
  <?xml version="1.0" encoding="UTF-8" standalone="yes"?><result><status>014</status><message>파일이 존재하지 않습니다.</message></result>
  ```

**내부 XML 구조 (DART 자체 스키마, `dart4.xsd`)**
```xml
<DOCUMENT xmlns:xsi="..." xsi:noNamespaceSchemaLocation="dart4.xsd">
  <DOCUMENT-NAME ACODE="...">임원ㆍ주요주주 특정증권등 소유상황보고서</DOCUMENT-NAME>
  <FORMULA-VERSION ADATE="...">6.1</FORMULA-VERSION>
  <COMPANY-NAME AREGCIK="...">주식회사 큐리오시스</COMPANY-NAME>
  <SUMMARY>...</SUMMARY>
  <BODY ATOCID="...">
    <TITLE ATOC="Y" ...>...</TITLE>
    <TABLE ACLASS="NORMAL" ...>
      <TBODY>
        <TR><TD>...</TD><TD>...</TD></TR>
      </TBODY>
    </TABLE>
    ...
  </BODY>
</DOCUMENT>
```
표준 HTML이 아니라 DART가 정의한 태그 체계(`TITLE`, `TABLE`/`TBODY`/`TR`/`TD`, `P`, `SPAN` 등)로
공시서식 레이아웃(표, 문단, 강조)을 표현한다.

## 2. 발견한 함정들

### 2-1. `status: "014"` — 원문이 없는 공시 유형

일부 `report_nm`(예: 임원·주요주주 소유상황보고서 중 특정 건)은 document API로 원문을 주지 않고
ZIP 대신 아래와 같은 XML 에러를 반환한다.

```xml
<result><status>014</status><message>파일이 존재하지 않습니다.</message></result>
```

**대응**: document API 호출 후 응답이 ZIP인지 먼저 확인(`zipfile.is_zipfile`)하고, 아니면
`<status>014</status>` 포함 여부로 "원문 없음"을 구분해 건너뛰어야 한다. list에서 얻은 rcept_no를
무조건 성공한다고 가정하면 안 된다.

### 2-2. DART XML이 항상 well-formed하지는 않음

투자설명서(`report_nm: 투자설명서(일괄신고)`, rcept_no `20260721000155`) 문서를
`xml.etree.ElementTree`로 파싱하면 에러가 발생한다.

```
xml.etree.ElementTree.ParseError: mismatched tag: line 3620, column 157
```

원인을 확인해보니 본문 텍스트 안에 이스케이프되지 않은 `<` 문자가 그대로 들어있었다.

```
<P USERMARK="F-GL "><표1>신용환산율</P>
```

`<표1>`이 실제로는 서술형 텍스트의 일부("<표1> 참고")인데, 파서는 이를 태그로 오인해서
이후 태그 매칭이 전부 어긋난다. 즉 DART가 "XML"이라 부르지만 실제로는 완전히
well-formed한 XML이 아닌 문서가 섞여 있다.

**대응**: 엄격한 `xml.etree.ElementTree` 단독 사용은 위험하다.
- `lxml`의 `recover=True` 파서로 관대하게 파싱하거나
- 파싱 실패 시 정규식 기반 태그 제거(`re.sub(r"<[^>]+>", ...)`)로 폴백하는 등
파싱 실패를 전제로 한 방어적 처리가 필요하다.

### 2-3. 문서 크기 편차가 매우 큼

같은 "원문 텍스트 추출" 작업이어도 문서 유형에 따라 결과 크기가 크게 다르다.

| 문서 | XML 크기 | 태그 제거 후 텍스트 길이 |
|---|---|---|
| 임원·주요주주 소유상황보고서 (`20260721000160`) | 25,756 bytes | 1,591자 |
| 투자설명서(일괄신고-사채) (`20260721000155`) | 697,651 bytes | 123,413자 |

약 80배 차이. 정형 서식(소유상황보고서 등)은 태그 오버헤드가 실제 텍스트보다 커서 결과가
작고, 서술형 문서(투자설명서, 사업보고서류)는 리스크 고지 등 방대한 텍스트를 포함한다.
고정된 처리 방식(예: 한 문서 = 한 청크)으로는 대응할 수 없다.

### 2-4. 표를 단순 태그 제거하면 라벨-값 관계가 깨짐

`"".join(element.itertext())`처럼 텍스트만 단순 추출하면, 표의 각 셀 값이 줄 단위로
나열될 뿐 어떤 라벨에 어떤 값이 붙는지 알 수 없게 된다.

```
회 사 명
주식회사 큐리오시스
법인구분
코스닥상장법인
```

`TABLE` → `TR` → `TD` 단위로 순회하며 같은 행의 셀을 `" | "` 등으로 재조합하면
라벨-값 관계가 어느 정도 복원된다.

```
보고자 : | 윤호영
```

다만 셀 내부에 줄바꿈이 여러 개 포함된 경우(여러 항목이 한 셀에 세로로 나열)에는
이 방식도 완벽하지 않고 후처리가 더 필요하다.

## 3. 청킹 전략 제안 (RAG용)

1. **문서 유형(`report_nm`)별로 처리 전략을 분기한다.**
   소유상황보고서류처럼 원문이 작은(수백~수천 자) 문서는 통째로 1개 청크로 넣어도 충분하고,
   투자설명서·사업보고서류처럼 큰(수만~수십만 자) 문서는 아래 2, 3번 전략이 필요하다.

2. **1차 분할은 구조 기반(섹션 단위)으로 한다.**
   문서 안에 법정 서식이 정한 번호 붙은 섹션(`TITLE`, 예: "1. 발행회사에 관한 사항",
   "2. 보고자에 관한 사항")이 이미 존재한다. 이 경계로 먼저 나누면 "보고자가 누구야?" 같은
   질의에 관련 섹션만 정확히 걸리는 청크를 만들 수 있다. 고정 길이로만 자르면 섹션이 중간에
   잘려 검색 품질이 떨어질 위험이 크다.

3. **섹션이 너무 크면 2차로 토큰/문자 기반 슬라이딩 윈도우를 적용한다.**
   리스크 고지문처럼 서술형 섹션 하나가 수천 자를 넘는 경우, 섹션 내부에서 다시
   500~1,000자(또는 토큰) 단위 + 100~150자 오버랩으로 분할한다.

4. **표는 "라벨: 값" 형태로 직렬화해 청크에 포함한다.**
   표를 억지로 잘게 쪼개면 라벨과 값이 서로 다른 청크에 들어가는 최악의 경우가 생긴다.
   표 하나를 통째로 유지하거나 소속 섹션에 붙이고, 셀을 flatten하지 말고 행 단위로
   `"회사명: 주식회사 큐리오시스"`처럼 재구성해서 넣는다.

5. **파싱은 실패를 전제로 방어적으로 구현한다.**
   §2-2에서 확인했듯 일부 문서는 엄격한 XML 파서로 파싱되지 않는다. 관대한 파서(`lxml recover=True`)
   또는 정규식 폴백을 반드시 준비한다.

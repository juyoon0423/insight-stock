# 진행 상황 (Progress)

마지막 업데이트: 2026-07-27

다음 세션 시작 시 이 문서부터 읽으면 지금까지 뭘 했고 다음에 뭘 할지 바로 파악할 수 있다.

## 1. 완료된 것 (기반 공사)

- **Open DART API 탐색** — list(공시검색)/document(공시원문) API를 실제로 호출해보고
  응답 구조, 함정(원문 없는 공시의 `status=014`, well-formed하지 않은 XML, 문서 크기
  편차 1,591자~123,413자, 표 flatten 시 라벨-값 관계 붕괴)을 확인했다.
  → [docs/dart-findings.md](./dart-findings.md), 탐색에 쓴 스크립트는 `tests/exploration/`
- **ERD 설계** — `companies` / `disclosures` / `chunks` 3테이블 구조와 설계 배경
  (ElasticSearch+pgvector 이중 저장/RRF, `document_status` 기반 배치 재시작,
  `report_nm` 기반 청킹 전략 분기)을 정리했다.
  → [docs/erd.md](./erd.md)
- **로컬 Docker 개발 환경** — pgvector 포함 PostgreSQL + Nori 플러그인 포함
  Elasticsearch를 `docker-compose`로 구성. 데이터는 named volume으로 유지.
- **Spring Boot 백엔드 뼈대** — Java 17 / Spring Boot 3.5.16 / Gradle. DB 접속 정보는
  `.env`(spring-dotenv로 로드)에서 읽고, `ddl-auto: validate`로 스키마는 Flyway가
  전담하게 설정.
- **Flyway 초기 스키마(V1)** — ERD를 그대로 반영한 테이블/제약/인덱스, `chunks.embedding`에
  HNSW(vector_cosine_ops) 인덱스 적용.
- **JPA 엔티티** — `Company` / `Disclosure` / `Chunk` + `DocumentStatus`/`ChunkType` enum.
  `chunks.embedding(vector(1536))`은 `com.pgvector:pgvector` + 커스텀 Hibernate
  `UserType`(`VectorType`)으로 `float[]`와 매핑. 실제 저장/조회 왕복 검증 완료.
- **Git 저장소 초기화 및 GitHub push** — 7개 커밋으로 논리 단위 분리, `.env`가 커밋에
  포함되지 않았음을 재확인 후 `origin`(GitHub private repo)에 push 완료.
- **DART list API 수집 파이프라인** — `CompanyRepository`/`DisclosureRepository`,
  `DartListClient`(RestClient로 list API 호출), `DisclosureCollectorService`(회사는
  `corp_code` 기준 find-or-create, 공시는 `rcept_no` 존재 여부로 중복 스킵 — 매 실행마다
  같은 날짜를 재조회해도 안전한 멱등 구조), `DisclosureCollectorScheduler`(`@Scheduled`
  10분 간격)를 구현. 실제 DART API로 검증까지 완료: 회사 268건, 공시 1204건이 정상
  수집됐고, `document_status=PENDING`/`has_document=false` 초기값과 회사↔공시 FK
  연결(예: 삼성전자 725건 — 소유상황보고서 대량 제출일, 버그 아님을 별도 확인)을
  psql로 직접 조회해 확인. → 커밋 `5e37f9e`
- **document API 원문 수집 파이프라인** — `DartDocumentClient`(document API 호출,
  ZIP/에러 판별은 호출부가 하도록 원시 바이트 그대로 반환), `XmlTextExtractor`(DOM
  파싱 + XXE 방지 설정, `TABLE`/`TR`/`TD`를 `"셀1 | 셀2"` 행 단위로 직렬화해 라벨-값
  관계 보존, 엄격 파싱 실패 시 정규식 태그 제거로 폴백), `DocumentCollectorService.
  collectPending(limit)` + `DocumentCollectorScheduler`(10분 간격, 배치 20건)를 구현.
  상태 흐름은 `PENDING` → `FETCHED`(ZIP 다운로드 성공) → `PARSED`(정제 성공),
  `status=014`(원문 없음)는 `NO_DOCUMENT`, 그 외 예외는 `FAILED`로 terminal 처리 —
  `DocumentStatus` enum에 `NO_DOCUMENT` 추가. 실제 DART API로 검증: 단건 처리(표가
  라벨-값 유지하며 정제된 것 확인, 최대 12,077자), `status=014` 케이스(효력발생안내
  유형에서 재현), 20건 배치(`PARSED` 7건 / `NO_DOCUMENT` 15건으로 정확히 분류)까지
  DBeaver로 직접 확인. → 커밋 `b143532`

- **청킹 파이프라인** — `XmlTextExtractor`에 `TITLE` 요소를 만나면 `[[SECTION]]`
  마커를 남기도록 수정(V2 마이그레이션으로 `chunks.chunk_source` 컬럼 추가, 기존
  PARSED 7건 재정제). 실제 배치(20건)로 검증한 결과 엄격 XML 파싱이 6/20(30%)에서
  실패해 정규식 폴백을 타는 걸 확인했고, 이 경로는 `[[SECTION]]` 마커도 표
  라벨-값 구조(`" | "` 조합)도 남기지 않는다는 걸 발견(dart-findings.md 2-5) —
  기존에 "미검증"으로 남겨뒀던 폴백 경로가 실제로 흔하게(약 30%) 발동하며, 발동 시
  청킹에 필요한 구조 정보를 통째로 잃는다는 뜻이라 청킹 설계에 직접 반영했다.
  `ChunkingService.chunkPending(limit)`(+ `ChunkingScheduler`, 10분 간격, 배치 20건)를
  구현: `char_length`가 임계치(2,000자) 미만이면 통째로 1개 청크
  (`chunk_source=WHOLE_DOCUMENT`), 그 이상이고 `[[SECTION]]` 마커가 있으면 구조
  기반 분할(`SECTION`, 섹션이 크면 내부에서 800자+150자 오버랩 슬라이딩 윈도우),
  마커가 없으면 전체를 슬라이딩 윈도우만으로 분할(`SLIDING_WINDOW`). 정보량이
  거의 없는 청크(표의 빈 셀만 남은 경우 등)는 필터링하며, 임계치는
  `application.yml`의 `chunking.empty-content-filter`로 설정값화해 나중에 검색
  품질을 보고 조정할 수 있게 했다. 임베딩은 아직 크레딧이 없어 붙이지 않음 —
  `chunks.embedding`은 전부 null이고, `ChunkingService`는 임베딩 관련 코드를 전혀
  참조하지 않는다(청킹과 임베딩을 처음부터 별개 서비스로 분리). 실제 데이터(20건,
  1,337개 청크)로 검증: `SECTION` 105개 / `SLIDING_WINDOW` 1,221개 / `WHOLE_DOCUMENT`
  11개. 구현 중 `Disclosure.chunks`가 지연 로딩 컬렉션인데 세션 밖에서 접근해
  `LazyInitializationException`이 나는 버그가 있었다 — 처음엔 `chunkPending()`
  전체를 `@Transactional`로 묶어 해결했는데, 이 방식은 나중에 재청킹 과정에서 더
  심각한 문제를 드러냈다(바로 아래 항목).

- **청크 폭증 원인 분석 + CSS 오염 제거(1단계) + 재청킹 버그 2건 수정** —
  `SLIDING_WINDOW` 청크(786,390자 문서, 1,210개 청크)를 실제로 열어봐서 원인을
  정량 확인했다: 청크의 72.1%(872/1,210)가 정규식 폴백에서 표가 압축되지 않고
  셀이 한 줄씩 풀린 것("의미 있는 문자 비율" 필터로는 안 걸러짐 — 실제 회사명/
  숫자라 필터를 통과함). 여기서 별개의 문제를 하나 더 발견했다: 일부 "자율공시"
  유형(기업설명회 IR개최, 최대주주등소유주식변동신고서 등 여러 report_nm에 걸침)은
  DART4 스키마가 아니라 `<html><head><STYLE>...</STYLE></head>` 형태의 HTML
  보일러플레이트를 쓰는데, 정규식 폴백이 이 CSS 텍스트를 그대로 청크에 남겼다
  (상세: dart-findings.md 2-6). 기존 `PARSED`/`CHUNKED` 59건 중 18건(30.5%)이
  영향받고 있었다.

  대응: `XmlTextExtractor.regexStrip()`이 일반 태그 제거 전에 `head`/`style`/
  `script` 블록을 내용째로 먼저 잘라내도록 수정. 영향받은 18건을 `PENDING`으로
  되돌려 재정제 → CSS 흔적 0건 확인(예: 20260721800091은 2,380자→1,235자로 축소,
  내용은 진짜 공시 텍스트만 남음).

  재청킹 과정에서 두 가지 버그를 추가로 발견/수정했다:
  1. **유니크 제약 위반** — `Chunk.id`가 `GenerationType.IDENTITY`라 새 청크의
     INSERT가 즉시 실행되는데, `orphanRemoval`로 인한 기존 청크 DELETE는 flush
     시점까지 지연된다. 재청킹 시 `chunk_index`를 그대로 재사용하면 DELETE보다
     INSERT가 먼저 나가 `uk_chunks_disclosure_chunk_index` 제약을 위반했다 —
     `disclosure.getChunks().clear()` 직후 `saveAndFlush()`로 DELETE를 먼저
     확정시켜 해결.
  2. **배치 전체 트랜잭션의 연쇄 실패** — 위 버그로 한 문서가 실패하자 그 문서
     하나만 실패하는 게 아니라 같은 배치의 나머지 19건도 전부
     `"current transaction is aborted"`로 실패했다. `chunkPending()` 전체를
     하나의 `@Transactional`로 묶어서, 한 문서의 SQL 에러가 Postgres 커넥션
     자체를 오염시켜 이후 모든 문서가 연쇄적으로 실패한 것 — try/catch로 예외를
     잡아도 트랜잭션이 이미 오염된 상태라 소용없었다. `TransactionTemplate`으로
     문서 하나당 독립된 트랜잭션을 쓰도록 재구성해 해결(각 문서는 자신만의
     트랜잭션에서 다시 조회 → 청킹 → 저장; 실패해도 그 문서의 트랜잭션만
     롤백되고 나머지에는 영향 없음).

  수정 후 18건 중 8건 재청킹 성공 확인(나머지 10건은 배치 크기 20건 제한으로
  이번 실행에 안 걸렸을 뿐 — 다음 청킹 실행 때 자연히 처리됨, 버그 아님).
  `chunk_index` 중복 0건, CSS 잔존 0건까지 psql로 확인.

- **표 미압축 문제(2단계) — 정규식 폴백 자체를 거의 안 타게 만드는 방향으로 해결** —
  원래 계획은 "정규식 폴백에서도 표를 압축"이었는데, 원본 XML을 다시 열어보니 폴백
  원인이 대부분 이스케이프 안 된 `&`와 `<표1>`/`<개정 2009.2.3>` 같은 가짜 태그
  두 가지로 좁혀졌고, 둘 다 복구 가능하다는 걸 실측으로 확인했다(dart-findings.md
  2-7). `XmlTextExtractor.extract()`를 3단계로 재구성: ①엄격 파싱 → ②실패 시
  이스케이프 안 된 `&`를 `&amp;`로, "ASCII로 시작하는 태그명이 아닌 `<...>`"를
  `&lt;`/`&gt;`로 정제 후 재시도(성공하면 검증된 DOM 경로 그대로 사용 — 표 압축
  로직을 새로 안 짜도 됨) → ③그래도 실패하면 기존 정규식 폴백. 이 과정에서 중요한
  걸 하나 더 발견했다: 기존 정규식 폴백이 `<개정 2009.2.3>` 같은 법령 인용 표기를
  태그로 오인해서 **에러 없이 조용히 삭제**하고 있었다 — 파싱 에러와 달리 눈에
  안 띄는 데이터 손실이었다(dart-findings.md 2-7).

  정제 규칙이 정상 내용을 훼손하지 않는지 자동 검증하는 단위 테스트 5개를 추가했다
  (`XmlTextExtractorTest`, 이 클래스 최초의 테스트) — 정상 XML, `&` 복구,
  가짜 태그 보존, 이중 이스케이프 방지, HTML 보일러플레이트의 정상 폴백 하강까지
  커버.

  기존 `PARSED`/`CHUNKED` 121건 전체를 재정제+재청킹해서 비교했다:
  - 폴백(마커 없음) 비율: 42.1% → **12.5%**
  - 786,390자 문서(투자설명서): `TABLE` 2,944개·`TITLE` 64개 복구, "표 나열형"
    청크 비율 72.1% → **3.6%**. char_length/청크 수는 오히려 늘었지만(786,390자→
    898,165자, 1,210개→1,406개) — 표 구분자(`" | "`)와 삭제되던 법령 인용 텍스트가
    복원된 결과라 나쁜 신호가 아님. "청크 개수"가 아니라 "표 구조가 살아있는
    비율"이 원래 문제였다는 게 드러났다.
  - 남은 `SLIDING_WINDOW`는 21개 청크뿐(전체 5,201개 중) — 원래 계획했던 "폴백에서
    표 압축"은 실익이 작다고 판단해 보류하기로 했다.

  재정제 과정에서 알게 된 별개 이슈: `document_status=FAILED`가 1→5건으로
  늘었는데 전부 `감사보고서`/`연결감사보고서` 유형이고, 원인은 오늘 건드린
  `XmlTextExtractor`가 아니라 `DocumentCollectorService`의 ZIP 내부 파일명 불일치
  (`ZIP 안에 {rcept_no}.xml 항목이 없습니다`)다 — 오늘 작업과 무관, 다음에 감사보고서
  유형 document API 응답을 직접 확인해볼 것.

  `ChunkingScheduler`는 계속 비활성화(`chunking.collector.enabled: false`) 상태로
  둠 — 임베딩 전에 검증할 게 더 있으면 켜기 전에 확인.

- **ElasticSearch 색인 파이프라인** — 코드를 짜기 전에 실제 청크 내용을 ES
  `_analyze` API로 먼저 테스트했다. Nori 자체는 표준 분석기 대비 압도적으로
  유효했지만(dart-findings.md 2-8), 이 도메인에서 제일 중요한 단어인 "보고서"가
  기본 사전으로는 "보"+"고서"로 잘못 쪼개지는 걸 발견 — 파싱 에러 없이 검색만
  조용히 안 되는 유형이라 2-7의 데이터 손실 사례와 비슷하다. `특수관계인`/`감사인`
  (자모 조각 생성), `이사회`(사회로 오분석) 등 추가로 깨지는 단어도 실측으로
  확인했다. `보고서`/`신고서`/`설명서`(report_nm 복합어 18종의 공통 접미사 —
  개별 등록 없이 이 3개만으로 전부 해결됨)/`특수관계인`/`감사인`/`이사회` 6개만
  사용자 사전에 최소로 추가(유상증자/최대주주 등 나머지 후보는 분해돼도 각 조각이
  검색 가능해서 제외).

  `ChunkDocument`(Spring Data Elasticsearch `@Document`, `chunks` 인덱스) +
  `ChunkDocumentRepository` + `IndexingService`(+`IndexingScheduler`, 기존
  패턴과 동일하게 문서 하나당 독립 트랜잭션)를 구현. `DocumentStatus`에 `INDEXED`
  추가(`CHUNKED → INDEXED`, 재색인은 `INDEXED → CHUNKED`로 되돌리는 기존 패턴
  재사용). ES 문서 `_id`는 Postgres `chunk.id`를 그대로 써서 재색인해도 중복이
  안 생기고(upsert), 재색인 시에는 먼저 `disclosure_id`로 delete-by-query 해서
  재청킹으로 고아가 된 예전 문서를 정리한다. `embedding`은 ES에 넣지 않음(pgvector
  전용, erd.md 이중 저장 설계 그대로 — BM25/벡터 검색 역할 분리).

  단계별로 검증: ①인덱스 매핑/설정이 의도대로 생성됐는지 확인(`_mapping`/
  `_settings` API로 Nori 분석기·사용자 사전 반영 확인) → ②49개 청크(disclosure
  8건) 파일럿 색인 후 "보고서" 검색(8건 히트), "합병" multi_match, `corp_name.
  keyword` 정확 필터까지 확인 → ③전체 5,201개 색인, Postgres `chunks` 테이블
  건수(5,201)와 ES 문서 수(5,201) 일치, `chunk_source` 집계도 정확히 일치
  확인. 실패 0건.

- **OpenAI 임베딩 파이프라인 — 전체 5,201건 완료** — 착수 전 비용부터 실측했다:
  `chunks` 5,201건 전체를 tiktoken(`cl100k_base`)으로 세어 총 3,403,072 토큰을
  확인하고, `text-embedding-3-small` 표준 단가($0.02/1M) 기준 예상 비용 $0.068이
  크레딧 $5의 1.4%도 안 됨을 먼저 확인한 뒤 진행했다. Batch API(50% 할인)는
  절감액이 3센트뿐이라 비동기 복잡도를 감수할 실익이 없다고 판단해 채택하지
  않았다.

  구현은 기존 `ChunkingService`/`IndexingService`와 같은 패턴(문서/청크 단위
  독립 트랜잭션)을 따르되, 처리 단위가 Disclosure가 아니라 Chunk라는 점이
  다르다. 새로 추가한 `ChunkRepository`로 `embedding IS NULL`인 청크를 여러
  Disclosure에 걸쳐 한 번에 묶어(요청당 100개) `EmbeddingClient`/
  `OpenAiEmbeddingClient`(`RestClient` 기반, 기존 DART 클라이언트들과 동일한
  패턴)로 OpenAI에 보낸다. `EmbeddingService`는 sub-batch(100개)마다 독립
  트랜잭션으로 저장하고, HTTP 호출은 트랜잭션 밖에서 실행해 DB 트랜잭션을 짧게
  유지한다. `embedding IS NULL` 자체가 "대기" 표시라 별도 `EMBEDDED` 상태 없이도
  재실행 시 중복 호출이 없다. `EmbeddingScheduler`는 비용이 드는 외부 API를
  부르는 스케줄러라 다른 스케줄러와 달리 `matchIfMissing=false`로 설정해
  안전장치를 이중으로 뒀다(운영 시엔 `enabled: false`로 유지).

  설계에는 없었지만 구현 중 필요해서 추가한 것: OpenAI 응답은 sub-batch 합계
  토큰만 주고 청크별 토큰 수는 안 줘서, `chunks.token_count`를 정확히 채우려면
  로컬 토크나이저가 필요했다. `jtokkit`(Java, `cl100k_base` — 비용 추정 때 쓴
  Python `tiktoken`과 동일 인코딩)을 추가해 API 호출 전에 청크별 정확한 토큰
  수를 계산하고, 8,191 토큰(임베딩 입력 한도) 초과 청크를 API 호출 전에 걸러내는
  방어선으로도 쓴다.

  검증: ①10개 파일럿 — 벡터 저장(`vector_dims`=1536), `embedding_model`/
  `token_count` 채워짐, round-trip 무결성(`embedding <=> embedding` = 0), 로그의
  토큰/비용 출력, 재실행해도 중복 없음까지 확인 → ②전체 5,191건(남은 전부) —
  실패 0건, `embedding IS NULL` 잔여 0건, `vector_dims` 불일치 0건.

  **실측 결과가 특히 의미 있다**: 전체 5,201건의 실제 토큰 수가 사전 추정(Python
  `tiktoken`) / 로컬 계산(Java `jtokkit`) / OpenAI 서버 응답(`usage.total_tokens`
  합산) 세 가지 방법 모두에서 정확히 **3,403,072**로 일치했다. 실제 비용은
  **$0.068**(예측과 동일) — 앞으로는 API를 호출하지 않고도 토큰 수/비용을
  정확히 예측할 수 있다는 뜻이다.

- **하이브리드 검색 엔진(BM25 + 벡터 + RRF 융합)** — `search/` 패키지에
  `KeywordSearchService`(ES BM25), `VectorSearchService`(pgvector 코사인,
  `EmbeddingClient` 재사용으로 질의도 색인과 같은 모델로 임베딩), `RrfFuser`
  (순수 함수 - 단위 테스트 3개로 RRF 공식 자체 검증), `HybridSearchService`
  (`CompletableFuture`로 두 검색을 병렬 실행 후 RRF 융합, Top-K만 hydrate)를
  구현했다. 각 검색은 후보 50개씩 가져온 뒤 RRF로 합친다 - top-k(10개)만
  가져오면 두 결과가 겹칠 여지가 거의 없어 RRF가 사실상 무의미해지기 때문이다.

  실제 회사/공시 데이터 기반 질의 3종(키워드 지향/의미 지향/일반)으로 검증했다.
  가장 값진 확인은 RRF가 "합의(consensus)를 우대"한다는 걸 수치로 본 것이다 -
  키워드 1위(벡터 39위)인 청크보다 키워드 3위+벡터 4위인 청크의 최종 RRF
  점수가 더 높았다(한쪽만 압도적이어도 다른 쪽에서 안 나오면 밀림). 반대로
  검색이 실패하는 사례도 정직하게 발견했다: "차입금"을 "빌린 돈"으로
  패러프레이즈한 질의에서 정답 청크가 BM25 top-10에도 벡터 top-200에도 안
  잡혔다 - 원인 분석과 향후 검토 옵션은 dart-findings.md 2-9, 아래 4절 참고.

  HNSW 인덱스 사용 여부도 `EXPLAIN`으로 확인했다: `<=>` 연산자로 정렬하면
  `enable_seqscan=off` 강제 시 `idx_chunks_embedding_hnsw`가 정확히 잡히지만,
  5,201건 규모에서는 플래너가 기본적으로 `Seq Scan`을 선택한다 - 인덱스 결함이
  아니라 테이블이 작을 때 흔한 정상 동작(데이터가 늘면 자연히 인덱스를 쓰게 됨).

- **RAG 답변 생성 — 환각 제어 + 출처 명시(CLAUDE.md 핵심 목표) 실동작 검증 완료** —
  코드를 짜기 전에 설계안(전체 흐름/프롬프트/모델·비용/출처 표시/top-k)을 먼저
  제안하고 사용자 승인을 받은 뒤 한 단계씩 구현했다. 새 `rag/` 패키지: `PromptBuilder`
  (`HybridSearchService`의 `RankedChunk` 리스트를 `[1] (회사명 / 공시유형 / 섹션명)`
  형식으로 번호 매겨 조립, system prompt는 `src/main/resources/prompts/
  rag-system-prompt.txt`로 분리해 코드 재컴파일 없이 튜닝 가능하게 함),
  `ChatCompletionClient`/`OpenAiChatCompletionClient`(임베딩 클라이언트와 동일한
  `RestClient` 패턴), `CitationParser`(답변 텍스트에서 `[숫자]` 인용을 정규식으로
  추출하는 순수 함수), `AnswerService`(검색→프롬프트→LLM→출처 후처리를 엮는
  조립점 - 실제로 인용된 출처만 노출, 검색 결과가 없으면 LLM 호출 없이 바로
  "찾지 못했습니다" 반환해 비용 절감).

  **핵심 설계 원칙 — 모델에게 URL/출처를 만들게 하지 않는다**: 출처(rcept_no,
  회사명, DART 링크)는 서버가 이미 아는 값이라 모델에게는 `[1]`, `[2]`처럼
  번호로만 인용하게 하고, 번호 → 실제 출처 매핑은 `AnswerService`가 결정적으로
  수행한다. 모델이 URL 텍스트를 직접 생성하게 하면 그 자체가 환각 지점이 되기
  때문. DART 원문 링크는 `https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rceptNo}`
  형식(웹 검색으로 실제 URL 규칙임을 확인 후 사용). 모델은 `gpt-4o-mini`(비용
  대비 검증에 충분한 품질부터 시작, 품질 부족 확인되면 상위 모델로 교체하는
  전략 - `application.yml`의 `rag.chat.model`로 설정값화), top-k=10은 기존
  하이브리드 검색 검증 때 쓰던 값 그대로(비용 차이가 미미해 품질 우선 판단).

  실제 LLM 호출로 2가지 핵심 시나리오를 검증했다(`AnswerServiceManualIT`,
  비용이 들어 CI 대상이 아닌 수동 검증용 - 회귀 확인 가치가 있어 계속 보존):
  - **컨텍스트에 답이 있는 질문** — "한국투자증권이 발행한 주가연계파생결합사채
    상품은 어떤 내용인가요?" → 정확한 답변을 생성하고 `[3][4]`로 인용, 실제
    노출된 출처도 정확히 그 2건(투자설명서·일괄신고추가서류)뿐이었다 - 인용
    번호와 실제 출처 매핑이 설계대로 일치함을 확인.
  - **컨텍스트에 답이 없는 질문** — "테슬라의 최근 자율주행 기술 개발 현황은
    어떤가요?"(DART/한국 공시와 무관) → 지어내지 않고 정확히 "제공된 공시
    자료에서는 이 질문에 답할 수 있는 내용을 찾지 못했습니다"라고 답변, 출처
    0건. **CLAUDE.md의 핵심 가치("사실에 근거해 답변 생성, 환각 제어")가 실제
    동작으로 증명된 지점**.
  - 비용: 2건 합계 입력 10,563토큰 / 출력 181토큰 → **$0.0017**(예산 $4.93 대비
    무시할 수준).

  구현 중 겪은 별개 이슈(OpenAI restricted API key 스코프 부족으로 채팅 완성이
  401로 막혔던 것, 과금 없이 해결)는 아래 4절에 트러블슈팅으로 별도 기록.

- **Controller/API 계층 — 백엔드 MVP 완성** — `AnswerService`/`HybridSearchService`를
  HTTP로 노출하는 `api/` 패키지를 구현했다. `AnswerController`(`POST
  /api/v1/answers`), `SearchController`(`GET /api/v1/search` - LLM 호출 없이
  RRF 순위 정보까지 그대로 노출하는 검색 전용 엔드포인트), `GlobalExceptionHandler`
  (`@RestControllerAdvice`, RFC 7807 `ProblemDetail` + `errorCode` 확장 필드로
  프론트가 에러 종류를 분기할 수 있게 함), `WebConfig`(CORS, 허용 origin을
  `application.yml` 값으로 빼서 배포 시 코드 변경 없이 교체 가능)로 구성.
  `AnswerResponse`에 `query` 필드를 추가해 프론트가 응답만 보고 어느 질문의
  답인지 알 수 있게 했다. 두 Controller 모두 처리 시간을 로그로 남겨(나중에
  스트리밍 필요 시점을 실측으로 판단하기 위함).

  코드를 짜기 전에 엔드포인트 설계(경로/메서드/DTO/예외 처리/CORS/응답 시간)를
  먼저 제안하고 승인받은 뒤 구현했다. 주요 결정: `/answers`는 LLM 호출이라는
  부수효과(비용)가 있는 "생성" 액션이라 `POST`, `/search`는 순수 조회라
  쿼리파라미터 기반 `GET`(REST 관례). `/search`를 별도로 둔 이유는 이 프로젝트의
  핵심 가치인 하이브리드 검색(BM25+벡터+RRF)이 실제로 어떻게 순위를 매기는지
  `rrfScore`/`keywordRank`/`vectorRank`를 프론트에서 시각적으로 보여줄 수 있게
  하기 위함. 에러 응답은 직접 만든 DTO 대신 Spring 표준 `ProblemDetail`을
  채택 - 프레임워크가 자동 처리하는 케이스(validation 등)와 형식이 통일됨.
  OpenAI/Elasticsearch/Postgres의 원본 에러 메시지는 절대 클라이언트에 노출하지
  않고(내부 정보 유출 방지) 서버 로그에만 전체 스택트레이스를 남기도록 설계.

  실제로 서버를 띄워 검증했다:
  - `POST /api/v1/answers`: 답이 있는 질문(정확한 답변 + 인용 매핑 일치),
    답이 없는 질문(테슬라, 환각 없이 "찾지 못했습니다") 모두 통과.
  - `GET /api/v1/search`: `rrfScore`/`keywordRank`/`vectorRank`가 결과마다
    정확히 노출됨 확인.
  - 잘못된 요청 4종(빈 `query`, `topK=100` x2, `q` 파라미터 누락) 모두 400 +
    통일된 `ProblemDetail` 형식(`errorCode=VALIDATION_ERROR`) 확인.
  - **처리 시간 실측**: `/answers` 1,917ms(정상 상태, 첫 호출은 JIT/커넥션풀
    워밍업 포함 5,480ms) / `/search` 288ms - 스트리밍 없이 동기 응답으로
    충분하다는 설계 판단(2절 5번 참고)을 실측 데이터로 뒷받침함.

  이로써 **백엔드 MVP(DART 수집 → 청킹 → 하이브리드 검색 → RAG 답변 → API)가
  완성**됐다.

- **프론트엔드 프로젝트 셋업 + 백엔드 API 연결 검증** — `frontend/`에 Next.js
  프로젝트를 생성했다(App Router, TypeScript, Tailwind CSS, npm - 전역 설치
  없이 `npx create-next-app`으로 생성, 최신 안정 버전인 Next.js 16.2.11 /
  React 19.2.4). 화면 디자인은 다음 단계이고 이번엔 "연결이 되는지"까지만이
  목표였다.

  백엔드 API 연결 기반: `lib/types.ts`(`AnswerRequest`/`AnswerResponse`/
  `SourceRef`/`SearchResponse`/`SearchResultItem`/`ProblemDetail` - 2절 "API
  명세"와 1:1로 맞춤), `lib/api.ts`(`search()`/`askAnswer()` + `ApiError`
  클래스로 `errorCode` 포함 에러 처리). API base URL은 `NEXT_PUBLIC_API_
  BASE_URL` 환경변수로 분리(하드코딩 금지 원칙 - `.env.local`, git 제외).

  구현 중 발견한 것: `create-next-app`이 만들어주는 기본 `frontend/.gitignore`의
  `.env*` 규칙이 `.env.local.example`까지 막아버렸다(백엔드가 `.env`/
  `.env.example`로 예시 파일은 커밋하는 것과 같은 패턴을 프론트에도 쓰려면
  예시 파일이 추적 대상이어야 함) - `!.env*.example` 예외를 추가해 해결.

  `app/search-test`(임시 페이지, 디자인 없음)로 실제 연결을 검증했다:
  브라우저에서 `GET /api/v1/search`를 호출해 결과와 RRF 순위 정보
  (`rrfScore`/`keywordRank`/`vectorRank`)가 정상 표시됨을 사용자가 직접
  확인했고, `curl`로 CORS 헤더(`Access-Control-Allow-Origin`)도 GET 실제
  요청과 POST 프리플라이트(OPTIONS) 양쪽에서 확인했다. `/answers`는 비용이
  들어 이번엔 테스트하지 않고 `/search`로만 검증(스케줄러는 임베딩만
  비활성 확인 후 진행 - 기존 원칙 그대로).

- **1단계 화면 구현 — 메인 페이지(질문→답변→출처)** — `app/page.tsx`를 실제
  화면으로 교체하고 `components/`(`QuestionForm`/`AnswerText`/`SourceCard`/
  `ResultPanel`)와 `lib/citations.ts`를 추가했다. 요구사항이었던 로딩
  스켈레톤/에러 분기(`errorCode` → 사용자 메시지 매핑)/"찾지 못했습니다"
  구분 표시/모바일 반응형(`sm:` 브레이크포인트)을 모두 구현.

  **인용 번호 재매핑**: 백엔드 `CitationParser`는 답변에 처음 등장한 순서로
  중복 제거한 인용 번호만 `sources` 배열에 담는다(예: 답변이 `[3][4]`를
  인용하면 `sources`는 2건이고 `sources[0]`이 `[3]`에 대응) - 프론트가 원본
  번호를 그대로 쓰면 카드 번호와 텍스트 번호가 어긋난다. `lib/citations.ts`의
  `splitAnswerByCitations()`가 백엔드와 동일한 규칙(첫 등장 순서 기준
  재번호)으로 답변 텍스트를 파싱해, 인용 배지를 클릭하면 해당 출처 카드로
  스크롤 이동하도록 구현했다.

  **화면 작업은 목업으로, 검증은 실제 API로 최소 횟수만** — `/answers`가
  호출당 약 $0.0015라 화면을 다듬는 동안은 `lib/mockAnswers.ts`(찾음/
  못찾음/에러 3가지 케이스, 1.5초 인위적 지연)로 작업했다. 처음엔 예시
  질문 칩 3개를 아무 질문이나 넣었는데, 사용자가 "삼성전자 유상증자"
  칩을 눌렀을 때 목업이 조건에 안 걸려 무관한 한국투자증권 ELS 답변이
  그대로 나오는 걸 발견 - 실제 DB(7/20~21 수집분 147건)에 그 내용이 없을
  가능성이 높아 실제 API에서도 재현될 문제였다. 대응: ①`GET
  /api/v1/search`(비용 없음)로 DB에 실제로 답이 있는 질문을 먼저 찾고
  (`disclosures`를 `report_nm`/`corp_name`으로 훑어 후보를 고른 뒤 검색
  결과 순도를 확인 - "파라택시스코리아의 합병 결정 내용은?"은 topK=10
  중 10/10이 그 회사 자신의 공시, "한국투자증권 ELS" 질문은 기존
  `AnswerServiceManualIT`로 이미 실 LLM 검증까지 끝난 질문이라 재사용),
  ②목업을 예시 칩 질문 3개에 대한 정확 문자열 매칭으로 바꿔 매칭 안 되는
  질문은 항상 "찾지 못했습니다"를 반환하게 해 무관한 답이 나올 여지를
  없앴다("테슬라 자율주행 현황은?"을 세 번째 칩으로 추가해 환각 제어를
  클릭 한 번으로 시연 가능하게 함), ③"찾지 못했습니다" 화면에 "국내
  DART 공시 기반이라 해외 기업/수집 범위 밖 정보는 답할 수 없다"는 안내
  문구를 추가해 왜 못 찾았는지 설명.

  마지막으로 `MOCK_MODE`를 끄고 실제 `POST /api/v1/answers`를 예시 칩
  2건(ELS/합병)에 대해 딱 2회만 호출해 검증했다 - 둘 다 정확하고 인용
  번호-출처 매핑이 일치하는 답변을 확인했고, `lib/mockAnswers.ts`는
  삭제했다. 부수 발견: 합병 질문 답변이 인용한 4개 출처 중 2쌍이 같은
  공시·같은 섹션 제목(다른 청크)이라 카드가 시각적으로 거의 동일하게
  두 번 나온다 - 틀린 데이터는 아니고(실제로 다른 청크를 인용) 백엔드
  계약대로 정확한 동작이라 이번엔 그대로 두기로 함(사용자 확인 완료).

- **`NEXT_PUBLIC_USE_MOCK_ANSWERS` 목업 토글 상시 도구화** — 매번 목업 파일을
  만들었다 지우는 대신, `.env.local`(로컬 전용, `true`)/`.env.local.example`
  (커밋 대상, 기본값 `false`)로 상시 토글 가능하게 만들었다. `lib/mockAnswers.ts`
  는 더 이상 지우지 않고 2~4단계 화면 작업에서도 계속 재사용한다. 목업 모드일
  땐 헤더에 노란 배지("목업 모드 — 예시 질문 3개만 정상 응답")를 띄워, 이전에
  겪은 "무관한 질문에 엉뚱한 답이 나와 헷갈리는" 문제를 화면에서부터 방지했다.

- **2단계 화면 — 하이브리드 검색(RRF) 시각화 `/search` 페이지** — 코드를 짜기
  전에 시각화 방식 3안(순위표/3열 범프차트/카드+듀얼마커)을 비교 제안하고,
  "안 C(카드+듀얼마커)를 기본으로, 안 B(범프차트)는 상단 요약용 보조로 나중에"
  라는 결정을 받은 뒤 단계적으로 구현했다.

  1단계(이번에 완료): `lib/rankScale.ts`(순위→트랙 위치 % 변환 순수 함수,
  범프차트에서도 재사용 예정) + `components/search/`(`DualRankMarker`-키워드/
  벡터 순위를 점 두 개로 표시해 가까우면 합의형·멀면 편중형이 한눈에 보이게 함,
  `RankedResultCard`-순위 배지+RRF점수+content 180자 미리보기+더보기,
  `SearchResultList`-top-10 강조+11~20위 접기, `SearchQueryForm`) +
  `app/search/page.tsx`(신규 라우트, `topK=20` 고정). 메인 페이지 헤더에
  `/search`로 가는 작은 링크를 추가했고, 대체된 임시 페이지 `app/search-test`는
  삭제했다.

  **topK를 10이 아니라 20으로 요청한 이유**: top-10만 받으면 "한쪽 검색에서는
  1위였지만 최종 순위에서 밀려난 청크"가 응답에 아예 안 잡혀서 "왜 하이브리드가
  필요한가"라는 이야기가 완성되지 않는다. 실제로 20위까지 받아보니 "GRT의
  유상증자 결정 내용은?" 질의에서 키워드 1위인데 벡터 후보 50위 안에 아예 없어
  최종 8위로 밀린 청크가 확인됐다 - 강조 로직(3단계)의 실제 근거 데이터로 확보.

  예시 질문 칩 3개도 실 `/search` 호출(비용 없음)로 미리 검증해서 골랐다:
  "신영증권의 증권발행실적보고서 내용은?"(키워드 1위=벡터 1위, 합의형),
  "GRT의 유상증자 결정 내용은?"(키워드 8위·벡터 1위, 편중형), "한국투자증권
  ELS"(혼합형, 기존 검증 질문 재사용).

- **top-K 중복 콘텐츠 발견 및 제거 — 검색/RAG 품질 문제 해결** — 2단계 화면을
  확인하던 사용자가 "2·3위 카드 content가 거의 동일하다"는 걸 직접 발견했다.
  코드를 고치기 전에 DB를 먼저 조회해 규모(전체 청크의 19.3%가 다른 청크와
  content 완전 동일, 그중 65%가 500자 이상)와 원인(전량 `chunk_source=SECTION`
  이라 슬라이딩 윈도우 오버랩 버그가 아니라 일괄신고/다회차 발행 같은 공시
  업무 관행이 원인)을 실측으로 확인한 뒤 대응했다. 상세 원인 분석과 3가지
  반복 패턴은 [dart-findings.md 2-10](./dart-findings.md)에 기록.

  대응은 `HybridSearchService`에서 RRF 융합 후(content가 있어야 중복 판정이
  가능해서 hydrate 순서를 top-K로 자르기 전으로 변경) content 정규화 완전
  일치 기준으로 대표 청크만 남기는 방식(`hybrid-search.dedup.enabled`로 토글,
  기본 `true`). **"완전 일치"만 쓰고 날짜/숫자를 무시하는 퍼지 매칭은 의도적으로
  배제**했다 - "Ⅳ. 조달된 자금의 사용내역" 같은 섹션은 회차마다 실제로 다른
  금액이 적혀있어서, 숫자를 무시하면 서로 다른 금액을 같다고 오판할 위험이
  있기 때문(금융 도메인에서 이런 오판은 출처 명시라는 핵심 가치와 정면 충돌).
  제거된 출처는 버리지 않고 `RankedChunk.duplicateSources`(신규 DTO
  `DuplicateDisclosureRef`)에 보존해 `/search` 화면에서 "관련 공시 N건
  보기"로 펼쳐 DART 링크까지 전부 확인할 수 있게 했다. `dartUrl` 생성 로직은
  `AnswerService`에만 있던 걸 `dart.DartUrls` 공용 유틸로 빼서 두 곳에서
  재사용하도록 정리했다.

  적용 전후를 실측으로 비교했다: `/search`에서 중복이 없던 질의(GRT,
  파라택시스코리아)는 dedup 전후로 top-10이 1도 안 바뀌어 회귀 없음을
  확인했고, 중복이 심했던 신영증권 질의는 top-4를 채우던 중복 표지가 정리되며
  이전에 안 보이던 진짜 다른 정보(한국투자신탁운용, 신영증권 실제 재무 정보
  섹션)가 top-10에 새로 들어왔다. **가장 결정적인 증거는 `/answers` 전후
  비교**(실 LLM 호출 2회, 합계 $0.0012): dedup 전엔 답변이 표지 정보로만
  채워졌는데("회사명/대표이사/주소... [1]", 출처 1건), dedup 후엔 같은
  질의에서 실제 재무 정보까지 포함됐다("조달된 자금의 사용내역... 모집총액은
  68,100,000원... [10]", 출처 3건) - "Top-K가 중복으로 채워지면 실질 정보량이
  준다"는 우려가 추측이 아니라 실측으로 확인된 품질 저하였음이 증명됐다.
  입력 토큰도 4,837→3,558개로 줄어 비용도 함께 낮아졌다.

  색인 단계에서 아예 중복을 제거하는 대안도 검토했지만 보류했다 - 그러면
  "청크 1개 = 공시 1개" 관계가 깨지고 대표 청크 하나만 남았을 때 "어느 공시가
  정답 출처인지" 모호해져 출처 명시라는 핵심 가치와 충돌 소지가 있었다.

- **유료 API 호출 테스트를 기본 `test`에서 분리** — 위 작업을 검증하며
  `./gradlew test`(전체 스위트)를 돌렸다가 `AnswerServiceManualIT`가 실
  LLM을 2회 호출(합계 $0.00188)하는 걸 뒤늦게 알아챘다. 애초에 이 클래스는
  "비용이 드므로 CI/자동 실행 대상이 아니다"라는 주석까지 있었지만, 그 의도를
  강제하는 장치(태그/태스크 분리)가 없어서 기본 `test`에 그대로 섞여 있었다 -
  지금은 사소한 금액이지만 나중에 CI/CD가 붙으면 커밋마다 자동 과금되는
  구조적 문제였다.

  `AnswerServiceManualIT`(LLM)와 `HybridSearchManualIT`(임베딩 API 호출,
  `vectorSearchPreview`/`hybridSearchPreview`)에 `@Tag("manual")`을 붙이고,
  `backend/build.gradle`의 `test` 태스크는 `excludeTags 'manual'`로 걸러지게,
  별도 `manualTest` 태스크(`includeTags 'manual'`)를 새로 등록해 수동
  실행만 가능하게 분리했다. 다른 테스트 클래스(`XmlTextExtractorTest`,
  `PromptBuilderTest`, `CitationParserTest`, `RrfFuserTest`,
  `InsightStockApplicationTests`)는 전수 확인한 결과 Spring 컨텍스트 로딩
  이외의 외부 API 호출이 없어 안전함을 확인했다. `./gradlew test` 재실행으로
  두 ManualIT 클래스가 완전히 스킵됨을(테스트 결과 파일이 새로 생성 안 됨)
  확인했고, CLAUDE.md에 "유료 외부 API를 호출하는 테스트는 기본 test에서
  제외한다"는 규칙을 추가해 재발을 방지했다.

- **2단계 화면 마무리 — 매치 패턴 배지 + 상단 요약 통계** — 코드를 짜기 전에
  `/search`에 실 질의 8개(topK=40, 비용 0)를 던져 "한쪽 검색만 찾아낸 청크"
  패턴이 실제로 얼마나 나타나는지부터 조사했다(전체 80슬롯 중 벡터단독 11 /
  키워드단독 16 / 합의 14 / 애매한 나머지 39). 이 조사로 임계값을 보수적으로
  잡기로 결정했다 - 강함=5위 이내, 약함=20위 밖, 그 사이 애매한 경우는
  **배지를 안 붙인다**(배지가 카드 대부분에 붙으면 오히려 눈에 안 띄고,
  경계선 오분류로 신뢰도가 떨어진다는 판단 - 듀얼 마커가 어차피 두 순위를
  그대로 보여주니 정보 손실도 없음).

  `lib/matchPattern.ts`(순수 함수 `classifyMatchPattern`/`summarizeMatchPatterns`/
  `describeMatchPatternSummary`, `rankScale.ts`와 같은 패턴)로 로직을 분리하고,
  `components/search/MatchPatternBadge.tsx`(카드별 배지 - 키워드=파랑/의미=주황,
  듀얼 마커와 색 통일, 합의는 두 색 점을 겹쳐 표시)와
  `components/search/MatchPatternSummary.tsx`(페이지 상단 요약 - 카드와 같은
  `classifyMatchPattern`을 재사용해 숫자가 항상 일치하도록 함)를 구현했다.
  요약 문구는 데이터에 따라 자연스럽게 바뀌게 만들었다 - 합의 0건이면서
  전부 편중이면 "두 검색 결과가 하나도 겹치지 않았습니다", 편중 0건이면
  "편중된 결과가 하나도 없었습니다"처럼 극단적인 케이스를 강조하고, 그 외에는
  일반형 문장으로 폴백.

  **원래 계획(안 B, 상위 5개 범프 차트)을 상단 요약 통계로 대체했다** - 세션
  중 "카드 배지보다 전체 그림이 한눈에 들어오는 게 낫지 않을까"라는 제안이
  나왔고, `classifyMatchPattern`을 그대로 재사용할 수 있어 구현 비용도 낮아
  범프 차트 대신 채택하기로 사용자와 합의했다. 범프 차트는 보류(필요해지면
  `lib/rankScale.ts`를 재사용해 나중에 추가 가능).

  예시 질문 칩도 이번 조사 결과로 다시 골랐다(`전환사채 발행조건과
  전환가액은?`/`GRT의 유상증자 결정 내용은?`/`파라택시스코리아의 합병 결정
  내용은?`) - 기존 신영증권/한국투자증권 ELS는 새 임계값 기준 배지가 7~9/10이
  "배지 없음"이라 데모 효과가 약해 제외했다. 파라택시스코리아는 메인 페이지
  예시 칩과 질문 텍스트가 겹치는데, 두 페이지의 `EXAMPLE_QUERIES`는 서로 다른
  파일의 독립 상수라 실제로는 무관하고(메인은 `POST /answers` 유료, `/search`는
  `GET /search` 무료), "메인에서 답변을 보고 `/search`에서 같은 질문의 검색
  과정을 보는" 자연스러운 연결이라 의도적으로 재사용을 유지했다.

  브라우저에서 Playwright로 실제 구동해 검증했다(이 프로젝트에 `chromium-cli`나
  프로젝트 전용 실행 스킬이 없어 `npx playwright install chromium`으로 임시
  설치해 사용 - 시스템 전역 설치가 아니라 프로젝트 로컬/캐시 범위라 CLAUDE.md의
  "전역 시스템 변경 사전 확인" 규칙과는 무관). 칩 3개를 각각 클릭해 배지
  구성과 요약 문구를 전수 확인했다:
  - **전환사채**: top-10 전부 배지(키워드단독 5 + 의미단독 5, 합의 0) - 요약
    문구 "두 검색 결과가 하나도 겹치지 않았습니다" - 가장 극적인 대비 사례.
  - **GRT**: 키워드단독 3 + 합의 1 - 요약 "키워드 검색이 3건을 단독으로
    찾아냈고, 1건은 양쪽 검색이 일치했습니다" - 키워드 1위인 청크가 실제로는
    GRT와 무관한 회사(키움증권)라는 반대 방향 증명.
  - **파라택시스코리아**: 합의 5 + 편중 0 - 요약 "편중된 결과가 하나도
    없었습니다" - 위 두 사례와 정확히 대비되는 기준점.
  - 신영증권(`키워드 #3`/`벡터 #18`)처럼 애매한 순위엔 배지가 안 붙는 것도
    확인 - 회색지대를 억지로 분류하지 않는다는 설계가 실제로 지켜짐.
  콘솔 에러 0건, `tsc --noEmit`/`eslint` 모두 통과.

- **3단계(인증+WatchList) 착수 — 스키마 + Spring Security/JWT 인프라** — 코드
  작성 전에 스키마/인증 방식/API 보호 범위/기존 코드 영향 4개 영역 설계안을
  먼저 제안하고, 사용자 확인(refresh token은 Stateless, `/search`·`/answers`
  공개 유지, `users.nickname` 추가)을 받은 뒤 한 걸음씩 구현했다.

  **[완료] Flyway V3 + JPA 엔티티** — `users`(email UNIQUE/password_hash/
  nickname)와 `watchlist`(user_id/company_id 복합 UNIQUE + 양쪽 FK `ON DELETE
  CASCADE`, FK 컬럼은 Postgres가 자동 인덱싱 안 해줘서 인덱스 명시 추가 -
  `disclosures.company_id`와 같은 이유). `User`/`Watchlist` 엔티티는 기존
  `Company`/`Disclosure` 컨벤션(surrogate PK, 단방향 연관관계로 대량 컬렉션
  로딩 방지)을 그대로 따름. psql로 스키마 확인 + 백엔드 부팅 시
  `ddl-auto: validate` 통과로 엔티티-스키마 매핑 일치를 검증했다.

  **4단계(알림) 연결 지점을 미리 고려**: `watchlist.created_at`과 기존
  `disclosures.created_at`(TIMESTAMPTZ, 수집 시각 - `rcept_dt`는 String이라
  비교에 안 씀)만 있으면 "관심 등록 이후 신규 공시"를 조회할 수 있어, 알림
  발송 이력용 테이블/컬럼은 지금 안 만들었다 - `watchlist`를 알림 상태로
  오염시키지 않기 위해서다(필요해지면 4단계에서 별도 테이블로).

  **[완료] Spring Security + JWT 인증 인프라** — 자체 발급/자체 검증 JWT라
  OAuth2 리소스 서버(외부 IdP 검증용, JWKSource 구성이 필요해 우리 케이스엔
  과함) 대신 `jjwt`로 직접 필터를 구성하는 쪽을 택했다. HS256(대칭키 - 단일
  백엔드라 RS256 비대칭키는 불필요한 복잡도), access 토큰 30분/refresh 토큰
  14일. **Refresh token은 Stateless로 결정** - WatchList는 결제·개인정보가
  없어 민감도가 낮고 즉시 무효화 요구사항도 없어서다. 필요해지면
  `refresh_tokens` 테이블을 추가하는 확장 경로를 열어뒀다.

  **화이트리스트 방식**(명시한 경로만 공개, 나머지 기본 인증 필수)을 써서
  새 API 추가 시 보호를 깜빡하는 사고를 구조적으로 막는다. 공개 경로:
  `GET /api/v1/search`·`POST /api/v1/answers`(포트폴리오 핵심 가치를 로그인
  벽 없이 체험 가능해야 한다는 판단 - 발표 시연에서 매번 로그인하는 번거로움도
  고려), `/api/v1/auth/**`, `/actuator/health`, CORS 프리플라이트(OPTIONS).

  `WebConfig`를 `CorsConfigurationSource` 빈으로 재구성해 Security의 `cors()`가
  기존 CORS 설정을 그대로 재사용하도록 했다(`WebMvcConfigurer` 방식과 병행하면
  두 경로가 중복/충돌할 수 있어 하나로 단일화). `GlobalExceptionHandler`와
  Security 필터(인증/인가 예외 - 컨트롤러 밖이라 `@RestControllerAdvice`가 못
  잡음)가 같은 `ProblemDetail`+`errorCode` 형식을 쓰도록 `api/ProblemDetails.java`
  로 생성 로직을 공통화했다.

  **회귀 확인**(Spring Security 도입이 기존 API를 막지 않는지가 가장 중요한
  검증 포인트였음, curl 전수 확인):
  - 인증 없이 `GET /api/v1/search`, `GET /actuator/health` → **200**
  - CORS 프리플라이트 `OPTIONS /api/v1/search`, `/api/v1/answers` → **200** +
    `Access-Control-Allow-Origin` 정상
  - 미구현 경로 `GET /api/v1/watchlist`(인증 없음) → **401** `AUTH_REQUIRED`
    - 화이트리스트가 기본적으로 막는다는 반증
  - `POST /api/v1/answers` 빈 body(인증 없음, 비용 0) → **400**
    `VALIDATION_ERROR`(401이 아님 - Security가 안 막고 컨트롤러 검증까지 통과
    한다는 뜻, 실제 LLM 호출 없이 "보호 안 됨"만 확인)
  - `./gradlew test` 전체 스위트 **BUILD SUCCESSFUL**(`GlobalExceptionHandler`
    리팩터링 회귀 없음)
  - 프론트(Playwright로 실제 구동): `/search`, `/`(메인, 목업 모드) 모두 콘솔
    에러 0건, 2단계에서 만든 매치 패턴 배지/요약 통계까지 정상 동작

  **트러블슈팅 - 필터 단계 401 응답의 한글이 깨지던 문제**: 첫 회귀 확인에서
  401 응답의 `detail`이 `"???? ?????."`로 나왔다. 컨트롤러에서 던진 예외는
  Spring이 자동으로 UTF-8 처리해주지만, Security 필터는 `DispatcherServlet`
  이전 단계라 그 혜택을 못 받고 `response.getWriter()`가 컨테이너 기본
  인코딩(ISO-8859-1)을 썼던 게 원인. `ProblemDetails.write()`를 Jackson이
  UTF-8로 직접 쓰는 `OutputStream` 방식으로 바꿔 해결, 재확인 결과
  `"로그인이 필요합니다."`로 정상 표시됨을 확인했다. **필터 레벨에서 응답을
  직접 만들 때는 컨트롤러와 인코딩 처리 경로가 다르다는 걸 기억할 것.**

  `JWT_SECRET`은 `.env`에 키 이름만 추가(`.env.example`도 동일), 실제 값은
  사용자가 직접 입력(회귀 테스트용으로는 임시 개발용 값을 생성해 사용) -
  `git check-ignore`로 `.env`가 계속 무시됨을 재확인했다.

- **3단계(인증+WatchList) 완성 — 회원가입/로그인/토큰 갱신 + 회사 검색 + WatchList
  CRUD** — 코드 작성 전에 API별 요청/응답 DTO, 검증 규칙, 에러 코드를 먼저 제안하고
  4가지 결정을 사용자와 확인한 뒤 구현했다:
  - **토큰 갱신은 엄격 모드** — refresh는 access token 만료 시에만 사용하고, 갱신
    시에도 access token만 재발급(refresh token 자체는 재발급하지 않음, Stateless
    결정과 일관성 유지). 여기에 더해 `JwtAuthenticationFilter`가 refresh token을
    일반 API 인증에 아예 못 쓰게 거부하도록 강화(`isRefreshToken()`이면 인증하지
    않고 통과 - 탈취된 refresh token으로 access token 없이 보호된 API를 호출하는
    경로를 원천 차단).
  - **회사 검색은 현재 `companies` 테이블(558건) 기준 ILIKE로 진행하되 한계로
    인식** — DART 전체 회사 목록(`corpCode.xml`) 적재는 9월 예정 데이터 규모
    확대 때 같이 하는 게 효율적이라는 판단. **지금 WatchList는 "이미 수집한
    558개 회사 중에서만 등록 가능"하다는 제약이 있고, `corpCode.xml`로 회사
    목록이 전체로 확대되면 그때부터 WatchList가 원래 의도한 "미래 알림 예약"
    (아직 공시가 없는 회사도 미리 등록해두고 나중에 알림받기)이라는 의미를
    온전히 갖게 된다** - 4단계(알림) 설계 시 이 제약을 먼저 해소할 필요가 있음.
  - **삭제 인가 실패는 404** — 403은 "그 id가 존재는 한다"는 정보를 노출하므로,
    소유권을 조건절에 녹인 `WatchlistRepository.deleteByIdAndUserId(id, userId)`
    (Spring Data 파생 삭제 쿼리, `@Transactional` 필요)로 삭제 자체를 한 쿼리로
    처리해 "본인 것 아님"과 "존재 안 함"을 구분 없이 0행 삭제 → 404로 통일.
  - **이메일 정규화는 소문자화+trim만** — local part(dot/plus 등)까지 건드리면
    실제로 다른 주소를 같다고 오판할 위험이 있어 보수적으로 여기까지만
    (`AuthService.normalizeEmail()`).

  패키지는 기존 컨벤션 그대로 확장: `repository/`에 `UserRepository`/
  `WatchlistRepository` 추가, 비즈니스 로직은 `auth/AuthService`(+3개 예외:
  `DuplicateEmailException`/`InvalidCredentialsException`/
  `InvalidRefreshTokenException`)와 신규 `watchlist/WatchlistService`(+3개 예외:
  `CompanyNotFoundException`/`DuplicateWatchlistException`/
  `WatchlistNotFoundException`)로, 컨트롤러는 `api/AuthController`/
  `api/CompanyController`/`api/WatchlistController`로, DTO는 전부 `api/dto/`에
  배치(`AnswerRequest`가 이미 그 자리에 있는 패턴 그대로). `GlobalExceptionHandler`에
  6개 예외 핸들러를 추가해 전부 같은 `ProblemDetail`+`errorCode` 형식을 따르게 했다.

  **구현 중 발견한 버그** — curl로 앞뒤 공백이 섞인 이메일(`"  Test.User@Example.com  "`)로
  가입을 시도했더니 400이 났다. 원인은 실행 순서 문제: Bean Validation의 `@Email`이
  `SignupRequest`/`LoginRequest` record가 생성된 직후(서비스 계층 진입 전)에 필드
  값을 그대로 검사하는데, `AuthService.normalizeEmail()`의 trim은 그보다 나중인
  서비스 계층에서 실행된다 - 앞뒤 공백이 있으면 정규화되기도 전에 `@Email` 정규식이
  막아버린 것. record의 compact constructor에서 trim을 먼저 적용해 검증 시점에
  이미 trim된 값을 보게 해서 해결했다. **DTO 필드를 정규화해야 하면 서비스
  계층만으로는 부족하고, Bean Validation이 보는 시점(DTO 생성 시점)에 이미
  정규화가 끝나 있어야 한다는 걸 실측으로 확인한 사례.**

  전부 curl로 실제 동작을 확인했다(회원가입/로그인은 유료 API가 아니라 마음껏
  반복 검증):
  - **인증**: 가입 성공(공백/대소문자 섞인 이메일이 정규화되어 저장됨 확인) →
    토큰 발급, 같은 이메일 다른 대소문자로 재가입 시도 → 409
    `EMAIL_ALREADY_EXISTS`, 로그인 성공(정규화 전 형태로 시도해도 통과),
    틀린 비밀번호/존재하지 않는 이메일 모두 동일하게 401
    `INVALID_CREDENTIALS`(계정 존재 여부 비노출), refresh 정상 동작(access
    token만 재발급 확인), access token으로 refresh 시도 → 401
    `INVALID_REFRESH_TOKEN`, refresh token으로 보호된 API 호출 시도 → 401
    `AUTH_REQUIRED`(엄격 모드 필터가 실제로 막는 것 확인).
  - **회사 검색**: "삼성"(16건 - 삼성전자/삼성SDI/삼성바이오로직스 등 실제
    DB 데이터), "전자"(5건) 검색 정상 동작, 빈 검색어/파라미터 누락 모두 400
    `VALIDATION_ERROR`.
  - **WatchList**: A유저가 삼성전자(companyId=4) 등록 → 201, 같은 회사 중복
    등록 → 409 `WATCHLIST_ALREADY_EXISTS`, 존재하지 않는 companyId → 404
    `COMPANY_NOT_FOUND`, A유저 목록 조회(1건)/B유저 목록 조회(0건) 확인.
    **★핵심 인가 테스트**: B유저 토큰으로 A유저의 WatchList 항목 삭제 시도 →
    정확히 404 `WATCHLIST_NOT_FOUND`(다른 사용자 항목의 존재를 노출하지 않음),
    A유저 본인 항목 삭제 → 204, 삭제 후 재조회 시 빈 배열 확인.
  - **회귀 확인**: `GET /api/v1/search`(신규 API 추가 후에도 정상 동작, 삼성전자
    관련 실제 검색 결과 반환), `actuator/health`, CORS 프리플라이트(기존
    `/api/v1/search` + 신규 보호 경로 `/api/v1/watchlist` 양쪽 모두 정상),
    `./gradlew test` **BUILD SUCCESSFUL**(manual 태그 테스트 제외 확인 - 비용 0).

  **이로써 3단계(인증+WatchList) 백엔드가 완성됐다.** 다음은 프론트엔드
  (로그인/회원가입 화면 + WatchList 화면).

- **토큰 저장 방식 결정 — "절충안"(access는 메모리, refresh는 localStorage)** —
  프론트 구현 전에 localStorage 단독 vs httpOnly 쿠키 vs 절충안 3가지를 이
  프로젝트의 구체적 맥락(백엔드가 이미 헤더 방식, WatchList는 결제·개인정보
  없는 저민감도 데이터, AWS로 다른 도메인 배포 예정, 프로젝트 핵심은 RAG
  파이프라인이지 인증 보안이 아님)에 비춰 비교한 뒤 **절충안**으로 결정했다.
  **이 결정의 "왜"를 면접에서 그대로 쓸 수 있게 남겨둔다:**

  - **httpOnly 쿠키를 안 쓴 이유** — 이미 검증 끝난 헤더 기반 필터
    (`JwtAuthenticationFilter`)를 다시 뜯어야 하고, `csrf().disable()`의
    근거("세션/쿠키를 안 쓰는 순수 헤더 기반 JWT")가 깨져 CSRF 방어를
    재도입해야 한다. 특히 AWS에서 프론트/백엔드가 다른 도메인이면 쿠키는
    `SameSite=None`이 강제되는데, 이는 `Lax`/`Strict`가 막아주던 크로스사이트
    요청 위조를 다시 열어버린다 - "쿠키가 항상 더 안전하다"는 통념과 달리
    **크로스도메인 배포 조건에서는 오히려 쿠키 쪽이 방어 장치(CSRF 토큰)를
    더 필요로 하는 역설**이 있다는 게 비교 과정에서 드러난 핵심 통찰.
    **다만 정직하게 인정할 지점도 있다: 만약 이 프로젝트가 결제 정보를
    다루는 은행 앱이었다면 CSRF 방어 비용을 감수하고서라도 httpOnly
    쿠키로 갔을 것이다** - 트레이드오프이지 절대적 우열이 아니라는 뜻.
  - **localStorage 단독을 안 쓴 이유** — 백엔드 안 고치고 배포도 단순한 건
    맞지만, 탈취 시 노출 창이 refresh token 수명(14일)까지 늘어난다.
    XSS의 실질 위험은 "토큰이 새는가"가 아니라 "얼마나 오래 쓸 수 있는
    토큰이 새는가"이므로, 장수명 토큰을 그대로 노출하는 게 이 방식의
    진짜 약점이었다.
  - **절충안을 택한 이유** — access token(30분, 짧은 수명)만 XSS에
    노출되는 localStorage에 두지 않고 메모리(모듈 스코프 변수)에만
    둬서, 탈취돼도 피해 창이 30분으로 제한된다. refresh token(장수명)은
    여전히 localStorage에 있지만, 이건 이미 헤더 방식으로 검증까지
    끝낸 백엔드를 전혀 고치지 않고, AWS 배포 시 쿠키 도메인 설계
    같은 추가 고민도 없이 그대로 갈 수 있다는 뜻이다. WatchList가
    저민감도 데이터라는 전제와 "인증 보안이 아니라 RAG 파이프라인에
    엔지니어링 비중을 둔다"는 프로젝트 우선순위에 정확히 맞는 지점.

  구현 아키텍처(`frontend/lib/tokenStore.ts`): React Context 대신 순수
  모듈 스코프 스토어 + `useSyncExternalStore`를 썼다 - `lib/api.ts`의
  fetch 로직(컴포넌트 트리 밖)도 현재 access token을 동기적으로 읽어야
  하는데, Context는 Provider 트리 안에서만 값을 꺼낼 수 있어 이 요구와
  안 맞는다. `applySession()`(로그인/가입 성공 시 메모리+localStorage
  동시 갱신), `bootstrapSession()`(앱 시작 시 저장된 refresh token으로
  조용히 access token 재발급), `authFetch()`(401 응답 시 refresh token으로
  단 한 번만 재시도, 동시에 여러 요청이 401을 맞아도 refresh 호출은
  하나만 나가도록 진행 중인 Promise를 공유)로 구성했다. **refresh API가
  accessToken만 돌려주고 사용자 정보는 안 주기 때문에**, 별도 "내 정보
  조회" API 없이 새로고침 후 닉네임을 바로 그리려고 `email`/`nickname`
  (민감하지 않은 표시용 정보)만 refresh token과 함께 localStorage에
  캐시해두기로 했다 - 이 캐시가 없으면 새로고침마다 잠깐 닉네임이
  빈 상태로 보이거나 `/auth/me` 같은 새 엔드포인트가 필요했을 것.

  `app/auth-test`(임시 검증 페이지, `app/search-test`와 같은 목적 -
  실제 로그인 화면을 만들기 전에 구조 자체를 실제 백엔드로 먼저
  검증)를 만들어 Playwright 헤드리스 브라우저로 8단계 시나리오를
  실행해 확인했다: ①최초 진입 시 `anonymous` ②로그인 성공 시
  `authenticated`+닉네임 표시 ③access token으로 `GET /watchlist` 성공
  ④**새로고침 후 세션이 자동 복원되고, 이때 재발급된 access token이
  새로고침 전과 다른 값임을 확인**(캐시 재사용이 아니라 실제로
  refresh 왕복이 일어났다는 증거) ⑤복원된 토큰으로 다시 `GET
  /watchlist` 성공 ⑥로그아웃 시 `anonymous`로 전환 ⑦로그아웃 후
  `GET /watchlist` 시도 시 정확히 `401 AUTH_REQUIRED`(재시도할 refresh
  token이 없으므로 즉시 실패) ⑧로그아웃 후 새로고침해도 `anonymous`
  유지. 콘솔 에러 0건(7번의 의도된 401 네트워크 로그 제외).
  `npx tsc --noEmit`/`npm run lint` 모두 통과.

- **3단계 프론트엔드 — 로그인/회원가입 화면** — `app/login/page.tsx`,
  `app/signup/page.tsx`(둘 다 기존 화면과 같은 Tailwind 톤 - `zinc` 팔레트,
  `ResultPanel`의 에러 박스 스타일 재사용)와 `lib/authValidation.ts`
  (백엔드 Bean Validation 규칙과 메시지까지 동일하게 맞춘 순수 검증 함수)를
  구현했다. 로그인/가입 성공 시 `lib/api.ts`의 `login()`/`signup()`이 이미
  내부적으로 `applySession()`을 호출하므로, 화면은 성공 후 `router.push("/")`
  로 이동만 하면 된다.

  로그인 상태를 화면에 반영하는 부분(원래 계획엔 별도 3단계로 잡혀 있었음)도
  이번에 같이 처리했다 - 검증 기준 자체가 "가입/로그인 후 헤더에 반영되는지"라
  화면 없이는 확인이 불가능했기 때문. `components/AuthStatusBar.tsx`(각
  페이지 자체 헤더와는 별개로 로그인 상태만 보여주는 얇은 전역 상단 바,
  `useSession()` 구독)를 `app/layout.tsx`에 추가해 모든 페이지에서 일관되게
  닉네임+로그아웃(로그인 시) 또는 로그인/회원가입 링크(비로그인 시)가
  보이게 했다.

  Playwright로 실제 백엔드에 대고 검증했다: ①회원가입 폼에 형식 틀린
  이메일/8자 미만 비밀번호/2자 미만 닉네임을 넣었을 때 세 필드 에러 메시지가
  백엔드 메시지와 정확히 같은 문구로 표시됨 ②정상 가입 → `/`로 리다이렉트,
  헤더에 닉네임+로그아웃 버튼 표시 ③같은 이메일로 재가입 시도 → "이미
  가입된 이메일입니다. 로그인해주세요."(`EMAIL_ALREADY_EXISTS`), `/signup`에
  그대로 머묾(리다이렉트 안 됨) ④로그아웃 → 헤더가 로그인/회원가입 링크로
  전환 ⑤틀린 비밀번호로 로그인 시도 → "이메일 또는 비밀번호가 올바르지
  않습니다."(`INVALID_CREDENTIALS`), `/login`에 그대로 머묾 ⑥올바른 비밀번호로
  로그인 → `/`로 리다이렉트, 헤더에 닉네임 다시 표시. 콘솔 에러 0건(③·⑤의
  의도된 409/401 네트워크 로그 제외). `npx tsc --noEmit`/`npm run lint`
  모두 통과. 검증에 쓴 임시 페이지 `app/auth-test`는 이 화면이 실제로
  완성됐으니 삭제(기존 `app/search-test` 삭제 사례와 같은 패턴).

- **3단계 프론트엔드 — WatchList 화면(회사 검색 자동완성 → 등록/조회/삭제) —
  3단계(인증+WatchList) 전체 완성** — `app/watchlist/page.tsx` +
  `components/watchlist/CompanyAutocomplete.tsx`를 구현했다. 자동완성은
  타이핑마다 요청이 나가지 않도록 300ms 디바운스를 적용했고, 이미 등록된
  회사는 검색 후보에서 미리 제외해(`excludeCompanyIds`) 같은 회사를 다시
  클릭해 409를 겪을 일 자체를 줄였다 - 그래도 겪을 경우(경합 등)를 대비해
  `WATCHLIST_ALREADY_EXISTS` 등 백엔드 errorCode를 화면 메시지로 매핑하는
  방어도 함께 뒀다(`COMPANY_NOT_FOUND`/`WATCHLIST_NOT_FOUND`/`AUTH_REQUIRED`
  포함). 목록 조회/등록/삭제 전부 인증이 필요한 API라 `lib/api.ts`의
  `authFetch`(401 시 refresh 자동 재시도)를 그대로 사용한다. 비로그인
  상태에서는 "로그인이 필요합니다" 안내와 로그인 링크를 보여준다. 헤더의
  `AuthStatusBar`에도 로그인 시에만 보이는 "관심 목록" 링크를 추가했다.

  **구현 중 새 lint 규칙(`react-hooks/set-state-in-effect`, Next.js 16이
  포함하는 React Compiler용 `eslint-plugin-react-hooks@7`)에 두 번 걸렸다** -
  effect 본문에서 동기적으로 `setState`를 호출하면 에러가 난다. 처음엔
  "async 함수 호출 직전에 호출되는 setState는 괜찮을 것"이라고 잘못
  추측했는데, 실제로는 위치와 무관하게 effect 본문에서 동기적으로 호출되는
  모든 `setState`가 걸린다는 걸 반복 실행으로 확인했다. 두 곳 모두
  같은 원칙으로 고쳤다: **effect 본문은 async 작업을 "시작"만 하고,
  모든 `setState`는 `.then`/`.catch`/`.finally` 콜백 안에서만 호출한다** -
  `WatchlistPage`의 목록 조회 effect는 `useCallback` 기반 함수 호출 대신
  `getWatchlist().then(...).catch(...).finally(...)` 체인으로, 로딩 상태는
  effect 밖에서 초기값(`true`)으로만 표현하고 완료 시점(`.finally`)에서만
  내리게 바꿨다. `CompanyAutocomplete`는 "검색 중" 표시를 effect가 아니라
  `onChange` 이벤트 핸들러에서 동기적으로 켜도록(이벤트 핸들러 안의
  `setState`는 규칙 대상이 아님) 옮기고, effect는 디바운스 타이머 등록과
  그 콜백 안의 `setState`만 담당하게 분리했다.

  **회귀 확인 중 실제 버그를 하나 발견해 고쳤다** - Playwright로 삭제
  버튼을 눌렀는데 화면에서 항목이 안 사라졌고, 콘솔에 `DELETE
  /api/v1/watchlist/{id}`의 CORS 프리플라이트가 `Access-Control-Allow-Origin`
  헤더 없이 막히는 에러가 찍혔다. 원인은 `WebConfig.corsConfigurationSource()`
  가 `/search`·`/answers`만 있던 시절 그대로 `setAllowedMethods(List.of("GET",
  "POST"))`로 고정돼 있었던 것 - 오늘 만든 `DELETE /api/v1/watchlist/{id}`가
  메서드 허용 목록에 없어서 브라우저가 실제 요청 자체를 막았다. curl로는 이
  문제가 절대 안 보인다(CORS는 브라우저가 강제하는 규칙이라 curl은 애초에
  검사하지 않음) - 오늘 앞서 있었던 curl 기반 백엔드 검증에서는 전혀 드러나지
  않았고, Playwright로 실제 브라우저를 띄워봐서야 잡힌 문제다. `"DELETE"`를
  허용 메서드에 추가해 해결, 재검증으로 삭제가 정상 반영됨을 확인했다.
  **새 HTTP 메서드를 쓰는 API를 추가할 때는 CORS 허용 메서드 목록도 같이
  업데이트해야 한다는 걸 기억할 것** - curl 검증만으로는 이 종류의 버그를
  못 잡는다.

  Playwright로 실제 백엔드에 대고 전 과정을 검증했다: ①비로그인 상태로
  `/watchlist` 접근 → "로그인이 필요합니다" 안내+링크 ②로그인 후 헤더의
  "관심 목록" 링크로 이동 → 빈 목록 ③"삼성" 검색(디바운스 확인 - 입력 직후엔
  아직 요청 전, 300ms+응답 대기 후 후보 표시) ④삼성전자 클릭 → 등록,
  목록에 표시 ⑤다시 "삼성" 검색 → 이미 등록된 삼성전자가 후보에서 제외됨
  확인 ⑥새로고침 → 목록 그대로 유지(세션 복원 + 재조회) ⑦삭제 → 목록에서
  사라짐(위 CORS 버그 수정 후 재검증) ⑧로그아웃 후 재접근 → 다시 안내 화면.
  기존 화면(`/`, `/search`, `/login`, `/signup`, `/watchlist`) 5개 전부
  콘솔 에러 0건. 백엔드 회귀: `./gradlew test` BUILD SUCCESSFUL(비용 0),
  `GET /api/v1/search` 정상 동작, CORS 프리플라이트 GET/POST/DELETE 전부
  통과. `npx tsc --noEmit`/`npm run lint` 모두 통과.

  **이로써 3단계(인증+WatchList, 백엔드+프론트엔드) 전체가 완성됐다.**

- **corpCode.xml 회사 목록 확대 — companies 1,044건 → 118,535건, "미리
  최적화"가 뒤집힌 실측 사례 발견** — 코드 작성 전에 설계안(corpCode.xml
  구조/적재 방식/중복 처리/스키마 변경 여부)을 먼저 제안하고, 사용자와 3가지
  결정(관리 가능한 배치로 구현/pg_trgm 인덱스 미리 추가/기존 데이터는
  corpCode.xml 값으로 덮어씀)을 확인한 뒤 실물부터 받아 구조를 검증하고
  단계적으로 구현했다.

  **[실측] corpCode.xml 구조** — ZIP(3.6MB) 안에 `CORPCODE.xml`(30MB, UTF-8)
  단일 파일, 전체 118,535건, `corp_code` 전부 고유. 예상대로 `corp_cls`(법인구분)
  필드가 없었고, `stock_code` 빈 값은 공백 문자 정확히 1개(8칸 아님) - `.strip()`
  후 판단해야 함을 확인했다. `corp_name`은 11.3%(13,382건)가 동명이인이라
  `corp_code`만이 유일 키임을 재확인. 기존 DB(당시 1,044건) 전량이 corpCode.xml에
  그대로 존재했고(누락 0), 실제 상호변경 사례(corp_code=01065013: 우정바이오
  →콜마바이오텍) 1건을 발견해 "corpCode.xml 값으로 덮어쓴다"는 결정의 실익을
  증명했다. 상세: dart-findings.md 2-11.

  **[완료] Flyway V4** — `corp_cls`를 nullable로 완화(corpCode.xml에 없는
  필드라 신규 회사는 알 수 없음), `pg_trgm` 확장 + `corp_name` GIN 인덱스
  추가(자동완성 성능 대비), `companies.updated_at` 추가(disclosures와 같은
  패턴).

  **[완료] `CorpCodeSyncService`/`CorpCodeSyncScheduler`** — `DartCorpCodeClient`로
  ZIP을 받아 `CORPCODE.xml`을 엄격 DOM 파싱(실측 결과 이 파일은 document API
  원문과 달리 전량 well-formed라 정제/정규식 폴백이 불필요)한 뒤, `JdbcTemplate`
  배치(1,000건 단위) `INSERT ... ON CONFLICT (corp_code) DO UPDATE`로 upsert한다.
  **`corp_cls`를 UPSERT 구문에서 아예 참조하지 않아** SQL 구조 자체로 기존
  값을 보존하는 게 핵심 설계다. list/document 수집기와 달리 페이징 없는 전체
  스냅샷이라 스케줄은 하루 1회(cron, 새벽 3시)로 잡고 다른 collector들처럼
  검증 전엔 비활성화 상태로 뒀다.

  파일럿(501건)→전체(118,535건) 순서로 단계적으로 검증했다(`CorpCodeSyncManualIT`,
  manualTest 전용 - DART corpCode.xml 자체는 무료지만 실제 DB를 변경하는
  통합 테스트라 기존 ManualIT 패턴을 그대로 재사용). 파일럿에서 상호변경
  케이스가 실제로 덮어써지는 것과 `corp_cls`(K)가 그대로 보존되는 것을 직접
  확인한 뒤 전체 적재를 진행했다. 최종: `companies` 1,044 → **118,535건**
  (기존 1,044건 전량이 corpCode.xml의 부분집합이라 합집합이 곧 전체 건수),
  기존 1,044건 전수 비교로 `corp_cls` 유실/변경 0건 확인.

  **[완료] `DisclosureCollectorService` 보정** — `findOrCreateCompany()`가
  기존 회사를 찾았을 때 `corp_cls`가 null이면 이번 list API 응답 값으로
  채우도록 수정했다 - corpCode.xml로만 존재하던 회사가 나중에 실제 공시를
  내면 자연히 법인구분이 채워지는 구조.

  **[완료, 계획에 없던 발견] pg_trgm 인덱스가 실측에서 무효로 판명 - "먼저
  실측하고 설계한다"는 원칙의 반대 사례** — 적재 후 자동완성 성능을
  검증하려고 Hibernate가 생성하는 실제 SQL을 로그로 확인했더니, Spring
  Data JPA의 `ContainingIgnoreCase`가 `upper(corp_name) LIKE upper(?)`로
  번역돼 원본 `corp_name` 컬럼에 건 인덱스와 predicate가 애초에 안 맞았다
  (`EXPLAIN ANALYZE`, `enable_seqscan=off`로 강제해도 Seq Scan만 선택 -
  매칭되는 플랜 자체가 없음). `upper()` 없는 순수 `ILIKE`로 바꾸면 인덱스가
  기술적으론 매칭되지만, "삼성" 같은 한글 2글자 검색어는 trigram 선택도가
  낮아 강제 스캔이 214.705ms로 Seq Scan(27.969ms)보다 7배 느렸다. 118,535건
  규모에서 Seq Scan 자체가 이미 27~42ms로 충분히 빨라, Flyway V5로 인덱스를
  제거했다(`pg_trgm` 확장은 나중을 위해 유지). "10만 건 LIKE 검색은 느릴
  것"이라는 합리적으로 보이는 추론이 ORM의 실제 SQL 생성 방식과 도메인
  데이터(한글 단문) 특성을 실측하지 않고는 틀릴 수 있음을 보여준 사례.
  상세: dart-findings.md 2-12.

  4개 커밋으로 의미 단위 분리해 push 완료: 스키마(V4+V5), 동기화 배치,
  `corp_cls` 보정, 문서화.

- **검색 품질 정식 측정 — 키워드/벡터/하이브리드 12개 질의 Recall@K·MRR
  (결과가 사전 가설과 정반대로 나옴, 있는 그대로 기록)** — 데이터 규모 확대(33배)
  이후 처음으로 세 검색 방식의 실제 정확도를 측정했다. 설계 단계에서 코드를 짜기
  전에 사용자와 방법론을 여러 차례 논의해 확정했다: ①정답은 corp_code+report_nm으로
  후보를 찾되(실제 DB 조회로 역방향 생성 - 정답 공시 원문을 먼저 읽고 질문을
  만듦) 채점 키는 구체적 `rcept_no` 하나로 얼려서 report_nm 문자열 재매칭을
  피함(`[기재정정]` 표기 변형 문제 원천 회피 - 청킹 때 report_nm 문자열 분기
  대신 char_length로 바꿨던 교훈과 같은 맥락), ②Recall@K는 "top-K 안에 정답
  공시 청크가 하나라도 있으면 1"(정답이 항상 1건이라 수학적으로 Hit@K와 동일,
  청크가 여러 개 걸려도 가산하지 않음), MRR은 "가장 먼저 나온 청크의 순위"만
  사용(RAG가 인용 1개면 충분한 실사용 패턴과 일치), nDCG는 이진 정답+질의당
  1건 조건에서 MRR과 중복이라 제외, ③고유명사형 8개(회사명·수치를 그대로
  인용, 키워드 유리 baseline)와 어휘 미스매치 5개(차입→빌린, 전환사채권→전환사채,
  기업설명회→실적발표회 등, 벡터 기여를 볼 대조군)를 의도적으로 섞어 12개 확정.

  질의 세트는 `backend/src/test/resources/evaluation/search-quality-queries.json`
  으로 파일화(재현 가능 - 나중에 검색 로직 개선 후 같은 세트로 재측정해 전후
  비교 가능), 측정 코드는 `SearchQualityEvaluator`(순수 함수 + 단위 테스트 4개,
  `RrfFuser`와 같은 패턴)와 `SearchQualityEvaluationManualIT`(`@Tag("manual")`,
  키워드/벡터/하이브리드 세 방식을 candidate=50 동일 조건으로 호출, 하이브리드는
  RRF+dedup을 포함한 production 경로 그대로 측정)로 구현. 비용은 질의 임베딩
  24회(질의 12개 × 2 - 벡터 단독 호출 + 하이브리드 내부 호출)뿐이라 사실상 무료.

  **결과가 가설과 정반대로 나왔다**: "미스매치 그룹은 벡터/하이브리드가 유리할
  것"이라는 가설과 달리, 벡터는 미스매치 그룹에서 Recall@20 **0.000**(정확일치
  그룹도 0.143로 저조), 키워드는 오히려 미스매치 그룹에서 더 강했다(Recall@20
  1.000, MRR 0.822). 하이브리드는 전체 Recall@20에서 키워드와 동률(0.917)이지만
  MRR은 뚜렷이 낮음(0.533 vs 0.769). 원인을 추측으로 남기지 않고 실측으로
  검증했다: ①벡터 약세는 정답 공시 12건 중 9건이 `chunk_source=WHOLE_DOCUMENT`
  (라벨+숫자 위주 정형 서식)라 임베딩이 담을 자연어 의미가 빈약했기 때문(2026-07-23
  발견한 "재무 수치 조밀 청크" 현상의 정량적 재확인, dart-findings.md 2-9).
  ②미스매치에서 키워드가 강했던 이유는 처음엔 "Nori 형태소 분석이 흡수했을
  것"이라고 추측했는데, `_analyze` API로 직접 확인해보니 **틀린 추측**이었다
  ("차입"은 한자어 명사, "빌리다"는 순우리말 동사라 형태소를 아무리 분해해도
  안 겹침) - 진짜 이유는 질의에 포함된 희귀 고유명사(회사명/거래상대방명)가
  BM25의 IDF 가중치를 통해 도메인 용어 불일치를 상쇄한 것이었다. 이 과정에서
  Nori 사전 공백을 하나 더 발견("기업설명회"→"기업"+"설"+"명회", 2-8과 같은
  종류). ③하이브리드 MRR 저하는 RRF가 원래 "합의(consensus)를 우대"하도록
  설계됐는데(2026-07-23 발견), 벡터가 전반적으로 약한 상황에서는 정답이 아닌
  청크가 양쪽에서 어중간하게 걸려 합의 보너스를 받아 키워드 단독 지지 정답을
  역전하는 역효과로 해석됨 - "합의 우대"의 전제(양쪽 검색이 어느 정도 신뢰할
  만함)가 이 도메인/청크 유형에서 성립하지 않은 사례.

  **결론**: 이 데이터셋에서는 BM25가 강하고, "하이브리드가 항상 키워드 이상"이라는
  가정은 성립하지 않는다 - HNSW(성공)/pg_trgm(실패) 사례로 이미 확인했던 "검색
  전략은 실측 없이는 모른다"는 교훈이 하이브리드 검색 설계에도 동일하게 적용됨.
  상세 수치·해석 전체는 [docs/search-quality-eval.md](./search-quality-eval.md),
  형태소 분석 검증 과정은 dart-findings.md 2-13 참고. 자연히 이어지는 질문
  ("이 도메인에서 벡터/하이브리드가 정말 필요한가")은 아직 결론 내리지 않고
  다음 논의로 남김.

- **검색 품질 두 번째 측정 — 서술형 문서 벤치마크, 선택 편향을 스스로 발견하고
  보완한 사례** — 위 정형 12개 벤치마크의 정답 판정 방식(corp_code+report_nm
  유일성)이 결과적으로 정형 서식 공시(`chunk_source=WHOLE_DOCUMENT`, 12개 중
  9개)로 표본이 쏠리는 선택 편향을 만들고 있었다는 걸 스스로 발견했다 - 사업
  보고서/투자설명서 같은 서술형 문서는 report_nm이 회사당 여러 번(연간 반복,
  정정) 나와서 이 방법으로는 애초에 후보가 되기 어려웠기 때문. DB 조회로 서술형
  문서(`char_length≥2,000`) 407건을 확인한 뒤, 정답 판정 키(`rcept_no` 고정)는
  그대로 두고 후보 선정만 "사람이 원문을 읽고 특정 rcept_no를 직접 확정"하는
  방식으로 바꿔 6개(차입금/매출·실적/합병목적 3테마 × EXACT/MISMATCH 2조건)를
  추가 측정했다(파일: `search-quality-queries-narrative.json`, 측정 코드는
  기존 `SearchQualityEvaluationManualIT`를 그대로 재사용 - `loadAndEvaluate()`로
  로직을 공용화하고 `@TestInstance(PER_CLASS)`로 정형 결과를 서술형 테스트와
  공유해 정형 12개를 두 번 돌리는 낭비를 피함).

  **결과**: 벡터 MRR이 정형 0.025 → 서술형 0.504로 **20배** 뛰었다(Recall@20은
  0.083→0.500, 6배) - 두 벤치마크 중 가장 극적인 신호. 반면 키워드는 서술형
  (0.667)이 정형(0.769)과 큰 차이가 없어 "정답이 긴 문서에 묻혀 키워드가
  무너진다"는 해석은 채택하지 않았다(수치가 뒷받침하지 못함). 어휘 조건을
  EXACT로 고정한 채 문서 유형만 바꿔보면 가장 선명하다 - 키워드-벡터 MRR 격차가
  정형 EXACT 0.687(0.730 vs 0.043) → 서술형 EXACT 0.047(0.722 vs 0.675)로
  **93% 줄었다.** 하이브리드는 세 비교(전체/정확일치/미스매치) 전부에서 벡터
  단독보다 높아(0.592>0.504 등) RRF의 가치를 뒷받침했다. 테마별로는 차입금만
  벡터가 완전히 무너지는데(Recall@20 0.000, 정확일치·미스매치 무관), 이는
  dart-findings 2-9("재무 수치 조밀 서술형 청크는 임베딩이 잘 못 담는다")가
  그대로 재현된 것 - `chunk_source`는 유용한 대리 지표였지만 진짜 원인은 더
  깊은 "서술 안의 수치 밀도"로 보인다.

  **통합 결론**: 어휘 미스매치 여부가 아니라 **문서 유형(정형 서식 vs 서술형)이
  하이브리드 검색의 가치를 가르는 진짜 변수**다 - 정형 서식은 벡터가 거의
  무의미하고, 서술형은 벡터가 핵심 기여를 하며 하이브리드가 항상 벡터 단독보다
  낫다. 향후 방향(문서 유형별 검색 전략 분기)은 아직 결정하지 않고 다음 논의로
  남김.

  **측정 과정에서의 자기 정정도 정직하게 기록**: 결과를 처음 요약할 때 하이브리드
  MRR을 Recall@20과 혼동해 실제보다 극적으로("1.000") 잘못 인용했다가, 원본 로그와
  즉시 대조해 바로잡았다 - "환각 제어"가 핵심 가치인 프로젝트에서 측정 결과
  해석 과정 자체도 예외가 아니라는 원칙을 재확인한 사례. 상세는
  [docs/search-quality-eval.md](./search-quality-eval.md) 7~9절 참고.

## 2. 현재 프로젝트 상태 요약

### 기술 스택

| 영역 | 선택 |
|---|---|
| 언어/런타임 | Java 17 |
| 백엔드 프레임워크 | Spring Boot 3.5.16 (Web, Data JPA, Validation, Actuator) |
| 빌드 도구 | Gradle 8.11.1 (wrapper 포함) |
| DB | PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`) |
| 검색엔진 | Elasticsearch 8.15.3 + Nori(한글 형태소 분석기), Spring Data Elasticsearch로 연동 |
| 마이그레이션 | Flyway |
| ORM | Hibernate 6.6.x (JPA), pgvector 매핑용 커스텀 UserType |
| 환경변수 | `.env` (spring-dotenv로 로드, 커밋 제외) |

### 폴더 구조

```
insight-stock/
├── docs/
│   ├── dart-findings.md   # DART API 탐색 기록
│   ├── erd.md             # DB 설계(ERD)
│   └── progress.md        # 이 문서
├── docker-compose.yml     # postgres(pgvector) + elasticsearch(nori)
├── docker/
│   ├── postgres/init/01-create-extension.sql
│   └── elasticsearch/Dockerfile
├── backend/               # Spring Boot 프로젝트
│   └── src/main/java/com/insightstock/insightstock/
│       ├── InsightStockApplication.java
│       ├── domain/        # Company, Disclosure, Chunk, DocumentStatus, ChunkType, ChunkSource,
│       │                  # support/VectorType, User, Watchlist
│       ├── repository/    # CompanyRepository, DisclosureRepository, ChunkRepository,
│       │                  # UserRepository, WatchlistRepository
│       ├── dart/          # DartListClient, DartDocumentClient, DartCorpCodeClient, XmlTextExtractor,
│       │                  # DartApiException, DartUrls, dto/(CorpCodeRecord 포함)
│       ├── collector/     # DisclosureCollectorService(+Scheduler), DocumentCollectorService(+Scheduler),
│       │                  # CorpCodeSyncService(+Scheduler)
│       ├── chunking/      # ChunkingService(+Scheduler)
│       ├── indexing/      # ChunkDocument, ChunkDocumentRepository, IndexingService(+Scheduler)
│       ├── embedding/     # EmbeddingClient/OpenAiEmbeddingClient, TokenCounter, EmbeddingService(+Scheduler), dto/
│       ├── search/        # KeywordSearchService, VectorSearchService, RrfFuser, HybridSearchService, dto/
│       │                  # (dto/에 RankedChunk.duplicateSources - DuplicateDisclosureRef)
│       ├── rag/           # PromptBuilder, ChatCompletionClient/OpenAiChatCompletionClient,
│       │                  # CitationParser, AnswerService, dto/
│       ├── auth/          # SecurityConfig, JwtTokenProvider, JwtAuthenticationFilter,
│       │                  # JwtAuthenticationEntryPoint, JwtAccessDeniedHandler, TokenType,
│       │                  # AuthService, dto/TokenPair, Duplicate/InvalidCredentials/
│       │                  # InvalidRefreshTokenException
│       ├── watchlist/     # WatchlistService, CompanyNotFound/DuplicateWatchlist/
│       │                  # WatchlistNotFoundException
│       └── api/           # AnswerController, SearchController, AuthController,
│                          # CompanyController, WatchlistController,
│                          # GlobalExceptionHandler, WebConfig, ProblemDetails, dto/
│   └── src/main/resources/
│       ├── application.yml
│       ├── elasticsearch/chunk-index-settings.json  # Nori 분석기 + 사용자 사전
│       ├── prompts/rag-system-prompt.txt  # RAG system prompt (코드와 분리, 튜닝 시 재컴파일 불필요)
│       └── db/migration/  # V1__init_schema.sql, V2__add_chunk_source.sql,
│                          # V3__add_users_and_watchlist.sql, V4__corp_code_sync.sql,
│                          # V5__drop_unused_corp_name_trgm_index.sql
├── frontend/               # Next.js 16(App Router, TypeScript, Tailwind CSS)
│   ├── app/                # page.tsx(메인 화면), search/(2단계 RRF 시각화 화면),
│   │                       # login/, signup/, watchlist/(3단계 인증+WatchList 화면)
│   ├── components/         # QuestionForm, AnswerText, SourceCard, ResultPanel,
│   │                       # search/(SearchQueryForm, RankedResultCard, DualRankMarker,
│   │                       # SearchResultList, MatchPatternBadge, MatchPatternSummary),
│   │                       # AuthStatusBar(전역 로그인 상태 바), SessionBootstrap
│   │                       # (앱 시작 시 조용한 refresh), watchlist/CompanyAutocomplete
│   ├── lib/                # api.ts(search()/askAnswer()/ApiError + signup()/login()/
│   │                       # searchCompanies()/getWatchlist()/addToWatchlist()/
│   │                       # removeFromWatchlist()/authFetch() - 401시 자동 refresh),
│   │                       # types.ts(백엔드 DTO와 1:1), tokenStore.ts(access 메모리+
│   │                       # refresh localStorage 절충안 - useSyncExternalStore 스토어),
│   │                       # authValidation.ts(백엔드 검증 규칙과 동일한 순수 함수),
│   │                       # citations.ts(인용 번호 재매핑), rankScale.ts(순위→트랙 위치 변환),
│   │                       # matchPattern.ts(배지 분류/요약 문구 순수 함수),
│   │                       # mockAnswers.ts(NEXT_PUBLIC_USE_MOCK_ANSWERS 토글용 상시 개발 도구)
│   ├── .env.local          # NEXT_PUBLIC_API_BASE_URL, NEXT_PUBLIC_USE_MOCK_ANSWERS (git 제외)
│   └── .env.local.example  # 커밋 대상 (frontend/.gitignore에 !.env*.example 예외 추가함)
├── tests/exploration/     # DART API 탐색용 파이썬 스크립트 (참고용, 자동화 테스트 아님)
├── .env / .env.example    # DART_API_KEY, POSTGRES_USER, POSTGRES_PASSWORD, OPENAI_API_KEY, JWT_SECRET
└── requirements.txt       # tests/exploration/ 실행용 파이썬 의존성
```

### 현재 데이터 상태 (2026-07-22 기준, 임베딩 완료 후)

- `companies`: 558건, `disclosures`: 1960건, `chunks`: 5,201건 — ElasticSearch
  `chunks` 인덱스에도 5,201건 색인 완료(Postgres/ES 건수 일치 확인)
  (**갱신, 2026-07-27**: corpCode.xml 확대로 `companies`는 **118,535건**으로
  늘었다 - `disclosures`/`chunks`는 이 작업과 무관해 그대로. 위 1절 corpCode.xml
  항목 참고.)
- `chunks.embedding`: 5,201건 전부 채워짐(`embedding IS NULL` 0건),
  `vector_dims` 전부 1536, `embedding_model` 전부 `text-embedding-3-small`
  1종. 총 토큰 3,403,072 / 실제 비용 $0.068(사전 추정과 정확히 일치)
- `document_status` 분포: `PENDING` 1584 / `PARSED` 208 / `INDEXED` 147 /
  `NO_DOCUMENT` 16 / `FAILED` 5 (`FAILED` 5건은 전부 `감사보고서`류 — document
  API ZIP 안에 `{rcept_no}.xml`을 안 준 케이스, `XmlTextExtractor`와 무관)
- `chunks.chunk_source` 분포(ES 집계로도 동일하게 확인): `SECTION` 5,094 /
  `WHOLE_DOCUMENT` 86 / `SLIDING_WINDOW` 21
- `ChunkingScheduler`는 계속 비활성화(`chunking.collector.enabled: false`) —
  표 미압축은 보류 결정(3절 1번). `IndexingScheduler`는 검증 완료 후 활성화
  (`indexing.collector.enabled: true`)해서 앞으로 청킹이 다시 켜지면 자동으로
  뒤따라 색인한다. list/document 수집 스케줄러는 계속 켜져 있어 백엔드를
  띄워두면 `PENDING`/`PARSED` 비율은 계속 올라간다.

### 실행 방법 요약

```bash
# 1. Docker 개발 환경 (Postgres + Elasticsearch)
docker compose up -d --build
docker compose ps   # 둘 다 healthy인지 확인

# 2. 백엔드 (JDK 17 필요)
cd backend
./gradlew bootRun

# 헬스체크
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

자세한 확인 명령(psql `\dt`, Elasticsearch 플러그인 확인 등)은 루트 [README.md](../README.md) 참고.

### API 명세 (프론트엔드 개발 참고용)

인증은 아직 없음(MVP 범위에서 제외, 나중에 JWT 등으로 추가 예정). base URL은
로컬 개발 기준 `http://localhost:8080`.

**`POST /api/v1/answers`** — 하이브리드 검색 + LLM 답변 생성 (비용 발생, OpenAI
채팅 완성 호출)

```jsonc
// 요청
{ "query": "한국투자증권이 발행한 주가연계파생결합사채 상품은 어떤 내용인가요?", "topK": 10 }
// topK는 선택(생략 시 서버 기본값 10), 1~50 범위

// 응답 200
{
  "query": "...",
  "answer": "... 답변 본문에 [1][2] 형태로 인용 표시 ...",
  "sources": [
    { "rceptNo": "20260721000155", "corpName": "한국투자증권",
      "reportNm": "투자설명서(일괄신고)", "sectionTitle": null,
      "dartUrl": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260721000155" }
  ]
}
```

**`GET /api/v1/search?q=...&topK=10`** — 하이브리드 검색만(LLM 호출 없음, 비용
거의 없음). RRF 순위 정보를 그대로 노출 - 하이브리드 검색이 실제로 어떻게
동작하는지 프론트에서 보여줄 때 사용.

```jsonc
// 응답 200
{
  "query": "...",
  "results": [
    { "corpName": "신영증권", "reportNm": "증권발행실적보고서", "sectionTitle": null,
      "content": "...", "rrfScore": 0.0328, "keywordRank": 1, "vectorRank": 1,
      // content가 완전히 동일해 이 결과 하나로 합쳐진 다른 공시들의 출처.
      // hybrid-search.dedup.enabled(기본 true)가 꺼져있거나 중복이 없으면 빈 배열.
      // dart-findings.md 2-10 참고.
      "duplicateSources": [
        { "rceptNo": "20260721000084", "corpName": "신영증권", "reportNm": "증권발행실적보고서",
          "sectionTitle": null, "dartUrl": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260721000084" }
      ] }
  ]
}
```

**`POST /api/v1/auth/signup`** / **`POST /api/v1/auth/login`** — 회원가입/로그인,
성공 시 access/refresh 토큰과 사용자 정보를 함께 반환(가입과 동시에 로그인 상태).
이메일은 소문자화+trim으로 정규화되어 저장/조회됨(local part는 그대로 보존).

```jsonc
// 요청(signup만 nickname 포함)
{ "email": "user@example.com", "password": "password123", "nickname": "juyoon" }
// 응답 200(signup은 201)
{
  "accessToken": "...", "refreshToken": "...",
  "user": { "id": 1, "email": "user@example.com", "nickname": "juyoon" }
}
```

**`POST /api/v1/auth/refresh`** — access token 재발급 전용(엄격 모드 - refresh
token 자체는 재발급하지 않고, access token으로는 호출 불가).

```jsonc
{ "refreshToken": "..." } // 요청
{ "accessToken": "..." } // 응답 200
```

**`GET /api/v1/companies?q=검색어`** — WatchList 등록용 회사 자동완성(공개
엔드포인트). `corp_name` 부분일치(대소문자 무관), 최대 20건.

```jsonc
// 응답 200
{ "companies": [
  { "id": 4, "corpCode": "00126380", "corpName": "삼성전자", "stockCode": "005930", "corpCls": "Y" }
] }
```

**`POST`/`GET`/`DELETE /api/v1/watchlist`** — 인증 필수(`Authorization: Bearer
{accessToken}`). 본인 것만 조회/삭제 가능 - 다른 사용자 항목을 삭제하려 하면
소유권 여부와 무관하게 항상 404(존재 자체를 노출하지 않음).

```jsonc
// POST 요청
{ "companyId": 4 }
// POST(201)/GET(200, watchlist 배열) 공통 항목 형태
{ "id": 1, "company": { "id": 4, "corpCode": "00126380", "corpName": "삼성전자",
  "stockCode": "005930", "corpCls": "Y" }, "createdAt": "2026-07-25T04:40:56.317188Z" }
// DELETE /api/v1/watchlist/{id} 성공 시 204 No Content
```

**에러 응답** — 모든 엔드포인트가 실패 시 RFC 7807 `ProblemDetail` 형식 +
`errorCode` 확장 필드로 통일:

```jsonc
{
  "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "query: 질문을 입력해주세요.",
  "instance": "/api/v1/answers", "errorCode": "VALIDATION_ERROR"
}
```

| `errorCode` | HTTP 상태 | 발생 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 빈 query, topK 범위(1~50) 초과, 필수 파라미터 누락 |
| `LLM_UNAVAILABLE` | 502 | OpenAI 채팅 완성 호출 실패 |
| `SEARCH_UNAVAILABLE` | 502 | 검색용 쿼리 임베딩 실패 |
| `INTERNAL_ERROR` | 500 | 그 외 예상 못 한 서버 오류 |
| `AUTH_REQUIRED` | 401 | 인증이 필요한 API에 토큰 없이(또는 무효한 토큰으로) 접근 |
| `FORBIDDEN` | 403 | 인증은 됐지만 권한이 없는 접근(현재는 역할 구분이 없어 실사용 경로 없음) |
| `EMAIL_ALREADY_EXISTS` | 409 | 회원가입 시 이메일 중복 |
| `INVALID_CREDENTIALS` | 401 | 로그인 실패(이메일/비밀번호 구분 없이 동일 처리) |
| `INVALID_REFRESH_TOKEN` | 401 | refresh 토큰 만료/위조/access 토큰으로 refresh 시도 |
| `COMPANY_NOT_FOUND` | 404 | WatchList 등록 시 존재하지 않는 companyId |
| `WATCHLIST_ALREADY_EXISTS` | 409 | 이미 등록된 회사를 다시 등록 시도 |
| `WATCHLIST_NOT_FOUND` | 404 | 본인 것이 아니거나 없는 항목 삭제 시도 |

**처리 시간 실측**(실 서버 기준, 2026-07-23): `/answers` 1,917ms(정상 상태,
첫 호출은 워밍업 포함 5,480ms) / `/search` 288ms. 지금은 동기 응답으로
충분하다고 판단(스트리밍 미도입) - 이 수치가 늘어나면(예: top-k 확대, 더 큰
모델로 교체) 스트리밍(SSE) 재검토 근거로 삼을 것.

CORS는 `application.yml`의 `cors.allowed-origins`(현재 `http://localhost:3000`)
로 허용 - Next.js 프론트를 다른 포트/도메인에서 띄운다면 이 값부터 확인.

## 3. 다음에 할 일

list API 수집, document 원문 수집, 청킹, 청크 폭증 대응, ElasticSearch 색인,
임베딩, 하이브리드 검색 엔진(RRF 융합)까지 완료. **다음은 RAG 답변 생성 단계다.**

1. **[완료] 청크 폭증 문서 대응** — `SLIDING_WINDOW` 청크를 실제로 열어봐서 원인을
   두 가지로 분리하고 둘 다 해결했다(dart-findings.md 2-6, 2-7). CSS 오염 제거 +
   XML 3단계 파싱(엄격→정제 후 재시도→정규식 폴백)으로 폴백 비율 42.1%→12.5%,
   786,390자 문서의 "표 나열형" 청크 비율 72.1%→3.6%까지 개선. 부수 발견: 정규식
   폴백이 법령 인용 표기(`<개정 2009.2.3>` 등)를 태그로 오인해 조용히 삭제하던
   데이터 손실도 같이 해결됨.
2. **[완료] ElasticSearch 색인** — `chunks` 인덱스에 5,201건 전부 색인, Postgres와
   건수 일치 확인. Nori 사용자 사전(`보고서`/`신고서`/`설명서`/`특수관계인`/
   `감사인`/`이사회` 6개)까지 실측 검증 후 반영(dart-findings.md 2-8 — "보고서"가
   기본 사전으로는 깨지는 걸 코드 짜기 전에 `_analyze` API로 미리 발견한 사례).
3. **[완료] 임베딩** — `chunks` 5,201건 전부 `text-embedding-3-small`로 임베딩,
   실패 0건. 착수 전 tiktoken으로 토큰 수(3,403,072개)를 실측해 예상 비용($0.068)이
   $5 크레딧의 1.4%도 안 됨을 먼저 확인한 뒤 진행했고, Batch API는 절감액이
   3센트뿐이라 채택하지 않았다. 실제 토큰 수가 사전 추정(Python tiktoken)/로컬
   계산(Java jtokkit)/OpenAI 서버 응답 세 방법 모두에서 정확히 일치해, 앞으로는
   API 호출 없이도 비용을 정확히 예측할 수 있음을 확인했다. 상세는 위 1절 참고.
4. **[완료] 하이브리드 검색 엔진** — `search/` 패키지에 `KeywordSearchService`
   (ES BM25, `ElasticsearchOperations`+`NativeQuery`)와 `VectorSearchService`
   (pgvector 코사인, `ChunkRepository` native query + `EmbeddingClient` 재사용)를
   각각 만들고, `RrfFuser`(순수 함수, DB/ES/API 의존성 없음 - 단위 테스트 3개로
   공식 자체를 검증)로 융합, `HybridSearchService`가 둘을 `CompletableFuture`로
   병렬 실행(전용 4-스레드 풀) 후 RRF로 합쳐 Top-K만 hydrate해서 반환한다.
   각 검색은 후보 50개씩 가져온다(top-k 10개만 가져오면 RRF가 무의미해지므로).

   실제 질의 3종(회사/공시 실데이터 기준)으로 검증했다:
   - **RRF의 "합의 우대"를 실측으로 확인** — 키워드 1위(벡터 39위)인 청크보다
     키워드 3위+벡터 4위인 청크의 최종 RRF 점수가 더 높았다. 한쪽에서 압도적
     1위여도 다른 쪽에서 밀리면, 양쪽에서 고르게 상위권인 청크에 최종 순위가
     밀린다는 걸 확인.
   - **벡터 검색의 한계도 정직하게 발견** — "차입금"을 "빌린 돈"으로 패러프레이즈한
     질의에서, 미리 확인해둔 정답 청크가 BM25 top-10에도 벡터 top-200에도 안 잡힘.
     상세와 원인 분석은 dart-findings.md 2-9, 4절 참고.
   - HNSW 인덱스가 실제로 쓰이는지 `EXPLAIN`으로 확인 — `enable_seqscan=off`로
     강제하면 `idx_chunks_embedding_hnsw`가 정확한 연산자(`<=>`)로 잡힘. 다만
     5,201건 규모에서는 플래너가 기본적으로 `Seq Scan`을 더 싸다고 판단해 인덱스를
     안 씀 — 인덱스 결함이 아니라 테이블이 작을 때의 정상적인 플래너 동작(데이터가
     늘어나면 자연히 선택될 것).

5. **[완료] RAG 답변 생성** — `rag/` 패키지(`PromptBuilder`/
   `ChatCompletionClient`+`OpenAiChatCompletionClient`/`CitationParser`/
   `AnswerService`)로 검색→프롬프트→LLM→출처 후처리 흐름을 구현했다. 모델은
   `gpt-4o-mini`, top-k=10, system prompt는 별도 리소스 파일로 분리. 실 LLM
   호출로 "답이 있는 질문"(정확한 답변 + 인용 매핑 일치)과 "답이 없는
   질문"(환각 없이 "찾지 못했습니다")을 모두 확인 - CLAUDE.md 핵심 가치(환각
   제어)가 실동작으로 증명됨. 비용 2건 합계 $0.0017. 상세는 위 1절 참고,
   `AnswerServiceManualIT`에 회귀 검증용으로 두 시나리오 그대로 보존.
6. **[완료] Controller/API 계층** — `api/` 패키지로 `POST /api/v1/answers`,
   `GET /api/v1/search` 2개 엔드포인트와 전역 예외 처리(`ProblemDetail`),
   CORS 설정을 구현하고 실 서버로 검증까지 마쳤다. **이로써 백엔드 MVP가
   완성됐다**(DART 수집 → 청킹 → 하이브리드 검색 → RAG 답변 → API). 상세는
   위 1절 마지막 항목, API 명세는 2절 "API 명세" 참고.
7. **[완료] Next.js 프론트엔드 셋업 + API 연결 검증** — `frontend/`에 Next.js
   16(App Router/TypeScript/Tailwind) 프로젝트를 생성하고 `lib/api.ts`/
   `lib/types.ts`로 백엔드 API 연결 기반을 마련, `/search` 실 호출과 CORS를
   브라우저에서 직접 확인했다. 상세는 위 1절 마지막 항목 참고. 화면 디자인은
   아직 없음(임시 검증 페이지 `app/search-test`만 존재).
8. **[완료] 1단계 화면 구현** — `app/page.tsx` + `components/`(`QuestionForm`/
   `AnswerText`/`SourceCard`/`ResultPanel`)로 메인 화면(질문→답변→출처)을
   구현했다. 로딩 스켈레톤, `errorCode`별 에러 메시지, "찾지 못했습니다"
   구분 표시(+ 안내 문구), 인용 번호 클릭 시 출처 카드로 스크롤, 모바일
   반응형까지 포함. 실제 DB에서 답이 확실히 나오는 질문 2개를 `GET
   /api/v1/search`(비용 없음)로 검증해 예시 칩으로 채택하고, 실제
   `POST /api/v1/answers`로 딱 2회만 최종 확인했다. 상세는 위 1절 마지막
   항목 참고.
   - `/search`(RRF 순위 노출) 화면은 다음 우선순위로 논의 예정(미확정).
     인증(JWT)은 계속 MVP 범위 밖.
9. **[완료] 2단계 화면 — 하이브리드 검색(RRF) 시각화** — 시각화 방식 설계안을
   먼저 제안·합의(안 C 카드+듀얼마커 기본 + 안 B 범프차트는 상단 요약용으로
   나중에)한 뒤 단계적으로 구현했다. 2026-07-23~24 두 세션에 걸쳐 완료.
   - **[완료]** 카드 리스트 + 듀얼 마커(`app/search/page.tsx`,
     `components/search/`), topK=20 요청 + top-10 강조/11~20위 접기, 예시
     질문 3개(합의형/편중형/혼합형) 실 API로 검증. 상세는 위 1절 참고.
   - **[완료, 계획에 없던 발견]** 화면을 확인하다 top-K 중복 콘텐츠 문제를
     발견해 먼저 처리했다 — 전체 청크의 19.3%가 다른 청크와 content 완전
     동일, `HybridSearchService`에 content 기준 중복 제거 추가
     (`hybrid-search.dedup.enabled: true`), `/search`에 "관련 공시 N건"
     표시까지 완료. `/answers` 품질이 실측으로 개선됨을 확인(표지 정보만 →
     실제 재무 정보 포함). 상세는 위 1절 및 dart-findings.md 2-10 참고.
   - **[완료, 부수 작업] 유료 API 테스트 분리** — `AnswerServiceManualIT`/
     `HybridSearchManualIT`에 `@Tag("manual")`을 붙이고 `build.gradle`에
     `manualTest` 태스크를 신설해, 기본 `./gradlew test`는 항상 비용 0이
     되도록 분리했다. CLAUDE.md에 재발 방지 규칙 추가. 상세는 위 1절 참고.
   - **[완료] 2단계 마무리 — 매치 패턴 배지 + 상단 요약 통계** — 원래
     계획이던 "상위 5개 범프 차트"는 세션 중 상단 요약 통계로 대체하기로
     합의해 대신 구현했다(범프 차트는 보류, 필요시 `lib/rankScale.ts` 재사용
     가능). 배지 임계값을 실측 조사(8개 질의) 기반으로 보수적으로 잡아
     애매한 케이스는 배지를 안 붙이도록 했고, 예시 칩도 배지 데모 효과가
     가장 큰 3개(전환사채/GRT/파라택시스코리아)로 교체했다. 상세는 위 1절
     마지막 항목 참고.
   - **[완료 - 3단계로 이어짐]** 인증(JWT) + WatchList 설계·1~2단계 구현
     완료. 상세는 아래 10번 항목 참고.

10. **[완료] 3단계 — 인증(JWT) + WatchList** — 설계안(스키마/JWT 방식/
    API 보호 범위/기존 코드 영향)을 먼저 제안하고 사용자 확인(refresh token
    Stateless, `/search`·`/answers` 공개 유지, `users.nickname` 추가)을
    받은 뒤 한 걸음씩 구현했다. 상세는 위 1절 마지막 항목 참고.
    - **[완료]** Flyway V3(`users`/`watchlist`) + JPA 엔티티, psql +
      `ddl-auto: validate`로 검증.
    - **[완료]** Spring Security + JWT 인프라(화이트리스트 방식, HS256,
      access 30분/refresh 14일 Stateless). 회귀 확인 전부 통과(curl +
      `./gradlew test` + 프론트 Playwright). 필터 단계 응답 한글 인코딩
      버그 발견/수정.
    - **[완료]** 회원가입/로그인/토큰 갱신 API, 회사 검색 API, WatchList
      CRUD API 전부 구현 + curl로 실제 동작 검증(핵심 인가 테스트인 "타
      유저 항목 삭제 시도 → 404" 포함), 회귀 확인(`/search`, CORS,
      `./gradlew test`)까지 완료 - **3단계 백엔드 완성**. 상세는 위 1절
      마지막 항목 참고.
    - **[완료] 프론트엔드 전체** — 토큰 저장 방식(access는 메모리, refresh는
      localStorage 절충안 - 결정 근거는 위 1절 참고) 결정 후, 인증 상태
      관리 구조(`lib/tokenStore.ts`) → 로그인/회원가입 화면(+헤더 로그인
      상태 반영) → WatchList 화면(회사 검색 자동완성/등록/조회/삭제) 순서로
      구현하고 각 단계 Playwright로 실제 백엔드에 대고 검증했다. 구현 중
      CORS 허용 메서드 누락(`DELETE`) 버그를 발견해 수정했다. 상세는 위
      1절 마지막 3개 항목 참고.

    **이로써 3단계(인증+WatchList, 백엔드+프론트엔드) 전체가 완성됐다.**

11. **[완료] corpCode.xml 회사 목록 확대** — 설계안(구조/적재 방식/중복 처리/
    스키마 변경)을 먼저 제안하고 사용자 확인(관리 가능한 배치/pg_trgm 인덱스
    선반영/기존 데이터 덮어쓰기)을 받은 뒤, 실물부터 받아 구조를 실측하고
    파일럿→전체 순서로 단계적으로 구현했다. `companies` 1,044 → 118,535건.
    부수 발견으로 미리 추가한 `pg_trgm` 인덱스가 실측(EXPLAIN ANALYZE)에서
    무효로 판명돼 제거했다 - "먼저 실측하고 설계한다"는 원칙의 반대 사례로
    dart-findings.md에 남김. 상세는 위 1절 마지막 항목 참고.

12. **[진행중] 4단계 — 알림 (설계 + 스키마 + 감지 쿼리 실측 완료, 구현은 다음
    세션)** — 설계안(감지 방식/알림 상태 저장/전달 방식/생성 시점/기존 코드
    영향 5가지)을 먼저 제안하고 사용자 확인을 받은 뒤 스키마부터 구현했다.
    - **결정된 것**: 전달 방식은 **A(화면 내 배지+목록, 폴링)** 로 확정 -
      외부 의존성/비용 0, 발표 시연이 self-contained하다는 게 근거. 이메일은
      확장 포인트로만 문서에 남기고 구현 안 함. 알림 생성 시점은 **별도
      스케줄러**(기존 수집 스케줄러 안 건드림)로 확정 - 사용자가 추가로
      짚은 근거: 나중에 무중단 배포로 인스턴스가 잠깐 2개 뜰 때, 수집 로직에
      알림이 박혀 있으면 중복 실행 위험이 있는데 관심사를 분리해두면 "이
      스케줄러는 한 인스턴스만" 같은 제어를 걸기 쉬워진다는 것.
    - **[완료]** Flyway V6(`notification`) — `(user_id, disclosure_id)`
      UNIQUE(watchlist의 `(user_id, company_id)`와 동일 패턴, `INSERT ...
      ON CONFLICT DO NOTHING`으로 스케줄러 재실행 안전), FK 둘 다 `ON DELETE
      CASCADE`(부모가 없는 알림은 의미 없다는 판단 - watchlist의 FK 정책과
      동일 논리), `is_read`+`created_at` 인덱스(헤더 배지/목록 조회용).
      `disclosures.created_at` 인덱스는 이번엔 추가 안 함 - V4→V5(pg_trgm)
      교훈대로 실측 없이 먼저 넣지 않기로 함.
    - **[완료]** Notification JPA 엔티티/리포지토리 작성, 실제 `bootRun`으로
      V6 적용 확인(`Successfully applied 1 migration ... now at version v6`),
      psql `\d notification`으로 UNIQUE/FK CASCADE/컬럼 기본값 3종 전부
      실측 확인(추측 아님).
    - **[완료]** 감지 쿼리(watchlist↔disclosures를 `company_id`로 직접
      조인 - corp_code를 거칠 필요 없음, disclosures가 애초에 company_id로
      바로 연결됨. `created_at` 시간 윈도우로 후보를 좁힌 뒤 조인) `EXPLAIN
      ANALYZE`를 실제 DB(공시 4,127건, watchlist 2건)에 대해 트랜잭션+
      ROLLBACK으로 실행(데이터 안 건드림). 조인은 `idx_watchlist_company_id`
      로 사실상 무비용, 병목은 disclosures 시간 윈도우 필터(인덱스 없어
      Seq Scan으로 4,127건 스캔해 25건으로 축소) - 전체 실행시간 2.4ms로
      현재 규모에선 문제 없음. 단 이 Seq Scan 비용은 새로 생긴 공시 수가
      아니라 disclosures 테이블 전체 크기에 비례해 자란다는 한계를 기록해둠
      (1만 건 넘어가면 재측정 후 `idx_disclosures_created_at` 추가 검토).
    - **다음 세션에 할 일**: 감지 로직 서비스 + 스케줄러(기본 비활성화,
      기존 6개 스케줄러와 동일한 `@Scheduled(fixedDelayString=...)` 패턴) →
      알림 조회/읽음 처리 API(인증 필수, 본인 것만 - watchlist 때처럼 "남의
      알림 못 보게" 인가 확인) → 그다음 세션에 프론트엔드(헤더 배지+알림
      목록, Playwright 검증).
    - 이 세션 중 사용자가 "disclosures PK가 rcept_no(문자열)"/"EXPLAIN을
      이미 돌렸다"고 스스로 잘못 기억해 질문한 것을, 실제 엔티티/마이그레이션
      파일 대조로 정정한 순간이 있었다(본인이 "검색 품질 측정 때 수치
      과장한 것과 같은 종류의 실수"라고 직접 짚음). 실물 확인 없이 넘겨짚지
      않는다는 이 프로젝트의 원칙이 이번에도 확인된 사례.

## 4. 알려진 미해결 / 주의사항 (반드시 기록)

- **[해결됨] CORS 허용 메서드 목록에 새 HTTP 메서드 추가를 깜빡하면 curl
  검증으로는 안 잡힌다** — WatchList 화면에서 삭제 버튼을 눌러도 목록에서
  안 사라져 Playwright 콘솔을 보니 `DELETE /api/v1/watchlist/{id}`의 CORS
  프리플라이트가 `Access-Control-Allow-Origin` 없이 막혀 있었다.
  `WebConfig.corsConfigurationSource()`가 `/search`·`/answers`만 있던
  시절 그대로 `setAllowedMethods(List.of("GET", "POST"))`로 고정돼 있어
  오늘 추가한 `DELETE`가 허용 목록에 없었던 것. CORS는 브라우저가 강제하는
  규칙이라 같은 날 앞서 했던 curl 기반 백엔드 검증(전부 통과)에서는 전혀
  드러나지 않았다 - curl은 애초에 CORS를 검사하지 않기 때문. `"DELETE"`를
  추가해 해결. **새 HTTP 메서드(PUT/PATCH/DELETE 등)를 쓰는 API를 추가할
  때는 `WebConfig`의 CORS 허용 메서드 목록도 같이 업데이트해야 하고, 이
  종류의 버그는 브라우저로 직접 확인해야만 드러난다는 걸 기억할 것.**
- **[해결됨] `react-hooks/set-state-in-effect`(Next.js 16의 새 React
  Compiler용 lint 규칙) - effect 본문의 동기적 setState는 위치와 무관하게
  전부 걸림** — WatchList 화면 구현 중 두 파일에서 걸렸다. 처음엔 "async
  작업 시작 직전에 호출되는 setState는 괜찮을 것"이라고 추측했는데, 실제로는
  effect 본문에서 동기적으로 호출되는 모든 `setState`가 위치와 무관하게
  걸린다는 걸 반복 실행으로 확인했다. **effect 본문은 async 작업을 "시작"만
  하고 모든 setState는 `.then`/`.catch`/`.finally` 콜백 안에서만 호출해야
  한다** - 목록 조회 effect는 `getWatchlist().then(...).catch(...).
  finally(...)` 체인으로, "검색 중" 같은 사용자 입력에 즉각 반응해야 하는
  상태는 effect가 아니라 `onChange` 이벤트 핸들러에서 동기적으로 설정하도록
  옮겨서 해결(이벤트 핸들러 안의 setState는 이 규칙 대상이 아님). 나중에
  "마운트 시 조회" 패턴을 또 쓸 때 같은 함정이 재현될 수 있음.
- **[해결됨] Bean Validation은 서비스 계층의 정규화보다 먼저 실행된다** —
  회원가입 API에 앞뒤 공백이 섞인 이메일(`"  Test.User@Example.com  "`)로
  curl 테스트했더니 400이 났다. `AuthService.normalizeEmail()`(trim+lowercase)은
  서비스 계층에서 실행되는데, `@Valid`의 `@Email` 검증은 컨트롤러 진입 시점
  (record가 생성된 직후)에 원본 필드 값 그대로 실행돼 그보다 먼저 막아버린
  것 - 검증이 정규화보다 먼저 일어난다는 실행 순서를 놓친 사례. `SignupRequest`/
  `LoginRequest` record의 compact constructor에서 trim을 먼저 적용해, 검증
  시점에 이미 trim된 값을 보게 해서 해결. **DTO 필드에 정규화가 필요하면
  서비스 계층만으로는 부족하고, Bean Validation이 보는 시점(DTO 생성 시점)에
  이미 정규화가 끝나 있어야 한다** — 나중에 다른 입력 필드도 정규화가
  필요해지면 같은 함정이 재현될 수 있음.
- **[해결됨] Spring Security 필터 단계 응답에서 한글이 ISO-8859-1로 깨짐** —
  JWT 인증 도입 후 첫 회귀 확인에서 401 응답의 `detail`이
  `"???? ?????."`로 나왔다. 컨트롤러에서 던진 예외는 Spring이 자동으로
  UTF-8 처리해주지만, Security 필터는 `DispatcherServlet` 이전 단계라 그
  혜택을 못 받고 `response.getWriter()`가 컨테이너 기본 인코딩
  (ISO-8859-1)을 썼던 게 원인. `api/ProblemDetails.write()`를 Jackson이
  UTF-8로 직접 쓰는 `OutputStream`(`objectMapper.writeValue(response.
  getOutputStream(), ...)`) 방식으로 바꿔 해결. **필터/인터셉터 레벨에서
  응답을 직접 만들 때는 컨트롤러와 인코딩 처리 경로가 다르다는 걸 기억할
  것** — `getWriter()`류를 다시 쓰게 되면 같은 함정이 재현될 수 있음.
- **[해결됨] OpenAI restricted API key에 채팅 완성 스코프가 없어 401 발생** —
  RAG 답변 생성(`ChatCompletionClient`) 구현 후 첫 실 호출 테스트에서 전부
  `401 Unauthorized`로 실패했다. `curl`로 직접 원인을 확인한 결과 임베딩 때부터
  써온 `OPENAI_API_KEY`가 **restricted API key**(스코프 제한 키)였고, `embeddings`
  스코프만 있어 그동안 임베딩 파이프라인(5,201건)은 문제없이 동작했지만 이번에
  처음 쓰는 채팅 완성 API에는 권한이 없었다 — 에러 메시지가 명확히 알려줬다:
  `"Missing scopes: model.request. ... if you're using a restricted API key,
  that it has the necessary scopes."` 인증 단계에서 거부된 요청이라 **과금은
  전혀 없었다**(모델이 토큰을 생성하지 않음). OpenAI 플랫폼에서 해당 키의
  스코프에 "Chat completions"를 추가로 활성화해 해결했다.

  부수적으로 발견한 점: 스코프를 수정한 직후 한동안 요청의 70~80%가 여전히
  같은 401로 실패하고 나머지만 200이 성공하는 **불안정한 전파 지연**이 있었다
  (같은 키/같은 요청을 반복 `curl`로 호출해도 성공/실패가 뒤섞임 — OpenAI 쪽
  권한 캐시가 엣지 노드 전체에 즉시 반영되지 않는 것으로 추정). 수 분간
  재시도(실패해도 무료)하니 안정적으로 200이 나왔다. 나중에 API 키 스코프를
  바꾼 직후 비슷한 401을 보면, 코드를 의심하기 전에 "방금 바꾼 권한이 아직
  전파 중일 수 있다"는 걸 먼저 고려할 것.

- **[해결됨] 정규식 폴백 경로가 청킹 구조 정보를 통째로 잃던 문제** — 처음엔
  마커 유무로 전략을 분기(`chunk_source`)하는 우회책으로 대응했었는데, 나중에
  폴백 자체를 거의 안 타게 만드는 근본 해결로 바꿨다. `XmlTextExtractor`의 3단계
  파싱(엄격→정제 후 재시도→정규식 폴백) 도입 후 폴백 비율 42.1%→12.5%, 786,390자
  문서의 표 미압축 청크 비율 72.1%→3.6%까지 개선. 상세: dart-findings.md 2-5, 2-7.
- **[해결됨] top-K가 중복 콘텐츠로 채워져 실질 정보량이 줄던 문제** — 2단계
  화면(`/search`)을 확인하던 중 사용자가 "2·3위 카드 content가 거의 같다"고
  발견. DB 조회로 전체 청크의 19.3%가 다른 청크와 content 완전 동일함을
  확인했고, 전량 `chunk_source=SECTION`이라 슬라이딩 윈도우 버그가 아니라
  일괄신고/다회차 발행 같은 공시 업무 관행이 원인임을 데이터로 증명했다.
  `HybridSearchService`에 content 완전 일치 기준 중복 제거를 추가
  (`hybrid-search.dedup.enabled: true`, 날짜/숫자 무시하는 퍼지 매칭은 금액
  오판 위험 때문에 의도적으로 배제). `/answers` 전후 비교로 품질 개선을
  실측 확인(표지 정보만 → 실제 재무 정보 포함). 상세: dart-findings.md 2-10.
- **JPA: IDENTITY 생성 전략 + orphanRemoval 컬렉션을 재사용할 때는 clear() 직후
  flush 필요** — `Chunk.id`가 `GenerationType.IDENTITY`라 새 엔티티 INSERT가
  즉시 실행되는데, `orphanRemoval=true`로 인한 기존 엔티티 DELETE는 flush
  시점까지 지연된다. `collection.clear()` 후 바로 새 엔티티를 채워 넣고
  저장하면 (같은 자연키를 재사용하는 경우) DELETE보다 INSERT가 먼저 나가
  유니크 제약을 위반할 수 있다 — `clear()` 직후 `saveAndFlush()`로 DELETE를
  먼저 확정시켜야 한다. `ChunkingService`의 재청킹 흐름에서 실제로 겪음(위 1절
  참고). 나중에 다른 곳에서도 "cascade+orphanRemoval로 컬렉션을 통째로
  교체"하는 패턴을 쓸 때 같은 문제가 재현될 수 있으니 기억해둘 것.
- **배치 처리에서 트랜잭션 범위를 문서 하나보다 크게 잡지 말 것** — 여러 건을
  순회하며 처리하는 배치를 하나의 `@Transactional`로 묶으면, 한 건의 SQL 에러가
  (Postgres는 트랜잭션 안에서 에러가 나면 ROLLBACK 전까지 이후 모든 명령을
  거부한다) 같은 배치의 나머지 건도 전부 연쇄 실패시킨다 — try/catch로 예외를
  잡아도 트랜잭션 자체가 이미 오염돼 있어 소용없다. `ChunkingService`가
  `TransactionTemplate`으로 문서 하나당 독립 트랜잭션을 쓰도록 고친 사례 참고.
  `DocumentCollectorService`/`DisclosureCollectorService`도 같은 구조인지
  점검 필요 → 점검 완료: 둘 다 클래스/메서드에 `@Transactional`이 없어
  (`grep` 확인) 이 버그와 동일한 "커넥션 자체가 오염되는" 문제는 없다.
  `DocumentCollectorService`는 문서 단위로 개별 `save()` + 문서별 try/catch까지
  갖춰서 안전하다. 다만 `DisclosureCollectorService.collect()`는 페이지 순회
  루프 안에 try/catch가 없어서, 한 건(`saveIfNew()`)이 예외를 던지면 그 예외가
  페이지 루프 전체를 빠져나가 같은 실행에서 이후 항목들이 처리되지 않는다 —
  `@Transactional`이 없으니 이미 저장된 항목이 롤백되지는 않고, `rcept_no` 중복
  체크 덕분에 다음 10분 주기 실행 때 자연히 재시도되긴 하지만, 문서 단위
  try/catch가 없다는 점은 `DocumentCollectorService`/`ChunkingService`와
  다르다 — 언젠가 견고하게 만들려면 `saveIfNew()` 호출부에 try/catch 추가를
  검토. 배치 서비스를 새로 만들 때는 이 패턴(문서 단위 개별 트랜잭션 + 문서 단위
  try/catch)을 따를 것.
- **스케줄러 10분 간격이 개발 중엔 잦게 느껴질 수 있음** — list/document/chunking/
  indexing 스케줄러 모두 `@Scheduled(fixedDelay)`라 앱을 띄울 때마다 즉시 1회
  실행된다. 개발 프로파일에서 주기를 늘리거나, 로컬 개발 시 스케줄러를 끄고
  수동 트리거로 대체하는 방안을 나중에 검토.
- **정형 서식 공시(소유상황보고서 등)는 빈 표가 많음 — 청킹에서 필터링으로 해결**
  `TABLE`은 있지만 실제 기재된 값이 없는 셀(빈 문자열)이 많아서 `raw_content`에
  `" | "`만 있는 줄이 섞여 들어가는 문제. `ChunkingService`가 청크 생성 후 "의미
  있는 문자(한글/영문/숫자) 비율"이 낮은 청크를 걸러내는 걸로 대응했고, 임계치는
  `application.yml`의 `chunking.empty-content-filter`(`min-length`,
  `min-meaningful-ratio`)로 설정값화해뒀다 — 너무 공격적으로 걸러진다 싶으면
  코드 변경 없이 이 값만 조정하면 됨.
- **한국어 형태소 분석기는 설치만으로 끝나지 않음 — 도메인 사전 튜닝이 필수**
  Nori 기본 사전이 이 도메인에서 제일 중요한 단어 "보고서"를 "보"+"고서"로
  잘못 쪼개고 있었다(파싱 에러 없이 검색만 조용히 안 되는 유형). 코드를 짜기 전에
  `_analyze` API로 실제 청크 내용을 먼저 테스트해서 잡아냈다. 사용자 사전
  (`elasticsearch/chunk-index-settings.json`)에 6개 단어만 최소로 추가했는데,
  나중에 실제 검색 로그를 보면서 비슷하게 깨지는 단어가 더 나오면 이 파일에
  추가하면 된다. 상세: dart-findings.md 2-8.
- **[미해결] `감사보고서`류 5건 `FAILED`** — document API가 준 ZIP 안에
  `{rcept_no}.xml` 파일이 없는 케이스(`DocumentCollectorService.
  extractXmlFromZip`에서 발생). 전부 `감사보고서`/`연결감사보고서` 유형에
  몰려 있어 이 report_nm이 ZIP 내부 파일명 규칙이 다를 가능성이 있음 — 다음에
  이 유형 document API 응답을 직접 열어봐서 확인할 것. 재시도 로직은 아직 없음.
- **[미해결] `SLIDING_WINDOW` 21개(표 나열형)** — 정제로도 복구 안 되는 극소수
  잔여 케이스. 표 압축 별도 구현은 보류하기로 했음(실익 대비 과함). **임베딩
  붙인 뒤 실제 검색 품질을 보고 이 21개가 눈에 띄게 나쁘면 그때 재검토**하기로
  결정(dart-findings.md 2-5, 2-7 / progress.md 3절 1번).
- **[미해결] 재무 수치가 조밀한 서술형 청크는 BM25/벡터 둘 다 놓칠 수 있음** —
  하이브리드 검색 검증 중(2026-07-23) 발견. 정상적으로 청킹된(`chunk_source=
  SECTION`, `chunk_type=TEXT`) 문법적으로 온전한 문단인데도, 패러프레이즈 질의에
  대해 BM25 top-10/벡터 top-200(전체 5,201건 중) 모두에서 안 잡힌 사례를
  확인했다. **위 `SLIDING_WINDOW`와는 원인이 다르다** — 그건 청킹 메커니즘
  결함이고 이건 청킹은 멀쩡한데 임베딩/질의가 수치 조밀 텍스트를 잘 못 담는
  현상이다(다만 "재무 수치 많은 콘텐츠가 검색에 약하다"는 같은 증상의 다른
  얼굴이라 나중에 같이 검토할 가치는 있음). 상세 및 향후 검토 옵션(수치 정규화,
  청크 요약문 추가, 질의 확장 등, 아직 미결정)은 dart-findings.md 2-9 참고.

## 5. 다음 세션 시작 방법

```bash
# 1. 인프라 기동
docker compose up -d
docker compose ps   # postgres/elasticsearch 둘 다 healthy인지 확인
```

Claude Code에서 `/clear`로 새 세션을 시작한 뒤, 아래 순서로 문서를 먼저 읽고
작업을 이어간다.

1. `CLAUDE.md` — 프로젝트 규칙 전반
2. `docs/progress.md`(이 문서) — 오늘까지 뭘 했고 다음이 뭔지
3. `docs/dart-findings.md` — 청킹 전략 설계 시 필요한 문서 유형별 특성/함정
4. `docs/erd.md` — `chunks` 테이블 설계와 RRF 융합 근거

### 참고할 추가 컨텍스트

- `.env`에 `DART_API_KEY`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `OPENAI_API_KEY`가
  모두 채워져 있어야 백엔드가 뜬다(레포에는 `.env.example`만 있음, `.env`는
  로컬에만 존재).
- pgvector 매핑(`Chunk.embedding`)과 임베딩 파이프라인(`embedding/` 패키지) 모두
  검증 완료 — 임베딩 관련 작업을 다시 처음부터 조사할 필요 없음.
- list/document/chunking/indexing/embedding 파이프라인(`repository/`, `dart/`,
  `collector/`, `chunking/`, `indexing/`, `embedding/` 패키지)이 모두 완성되어
  백엔드를 띄워두면 계속 돌아간다. `embedding.collector.enabled`는 비용이 드는
  외부 API를 부르므로 기본 `false`.
- 하이브리드 검색 엔진(`search/` 패키지 - `KeywordSearchService`/
  `VectorSearchService`/`RrfFuser`/`HybridSearchService`)도 완성되어 검증까지
  끝났음 - 다시 조사할 필요 없이 `HybridSearchService.search(query, topK)`를
  그대로 갖다 쓰면 됨. `HybridSearchManualIT`(수동 검증용)에 실제 질의 예시와
  결과 출력 포맷이 남아있으니 참고 가능.
- RAG 답변 생성(`rag/` 패키지 - `PromptBuilder`/`ChatCompletionClient`/
  `CitationParser`/`AnswerService`)도 완성되어 실 LLM 호출로 검증까지 끝났음 -
  `AnswerService.answer(query)`를 그대로 갖다 쓰면 됨. `AnswerServiceManualIT`
  (수동 검증용, 비용이 들어 CI 대상 아님)에 "답이 있는 질문"/"답이 없는 질문"
  두 시나리오가 회귀 검증용으로 보존돼 있으니 참고 가능. OpenAI API 키는
  restricted key라면 Chat completions 스코프가 켜져 있어야 함(4절 트러블슈팅
  참고).
- Controller/API 계층(`api/` 패키지 - `AnswerController`/`SearchController`/
  `GlobalExceptionHandler`/`WebConfig`)도 완성되어 실 서버로 검증까지
  끝났음(2절 "API 명세" 참고) - **백엔드 MVP 완성**. `POST /api/v1/answers`,
  `GET /api/v1/search` 그대로 갖다 쓰면 됨.
- Next.js 프론트엔드 셋업(`frontend/` - Next.js 16/TypeScript/Tailwind,
  `lib/api.ts`/`lib/types.ts`)도 완성되어 백엔드 API 연결까지 브라우저에서
  직접 검증했음 - 다시 조사할 필요 없이 `lib/api.ts`의 `search()`/
  `askAnswer()`를 그대로 갖다 쓰면 됨.
- **1단계 화면(메인 페이지)도 완성됨**(3절 8번 참고) - `app/page.tsx` +
  `components/`로 질문→답변→출처 화면이 실제 API(`askAnswer()`)에 연결돼
  있고, 예시 질문 칩 3개(`한국투자증권 ELS`/`파라택시스코리아 합병`/`테슬라`)는
  실 DB·실 API 기준으로 검증돼 있음. `NEXT_PUBLIC_USE_MOCK_ANSWERS=true`로
  목업 모드 전환 가능(`lib/mockAnswers.ts`, 헤더에 노란 배지로 표시).
- **2단계 화면(`/search`, RRF 시각화) 완성됨**(3절 9번 참고) -
  `app/search/page.tsx` + `components/search/`로 카드+듀얼마커 시각화, 매치
  패턴 배지(`MatchPatternBadge`), 상단 요약 통계(`MatchPatternSummary`)까지
  전부 완성. `lib/matchPattern.ts`를 다시 조사할 필요 없이 그대로 갖다 쓰면 됨.
- **top-K 중복 콘텐츠 제거도 완료**(1절 참고, dart-findings.md 2-10) -
  `hybrid-search.dedup.enabled: true`로 활성화돼 있고 `/search`·`/answers`
  양쪽에 자동 적용됨. 다시 조사할 필요 없음.
- **유료 API 테스트 분리도 완료** - `./gradlew test`는 항상 비용 0(수동 검증은
  `./gradlew manualTest`로만). CLAUDE.md 규칙 참고.
- **3단계(인증+WatchList) 백엔드 완성** - 스키마(`users`/`watchlist`,
  Flyway V3), Spring Security + JWT 인프라, 그리고 회원가입/로그인/토큰
  갱신/회사 검색/WatchList CRUD API 전부 구현 완료, curl로 실동작 검증까지
  마쳤다(핵심 인가 테스트 포함). `auth/AuthService`, `watchlist/
  WatchlistService`, `api/AuthController`/`CompanyController`/
  `WatchlistController`, 프론트 `lib/tokenStore.ts`/`lib/api.ts`(인증
  포함)/`app/login`/`app/signup`/`app/watchlist`를 다시 조사할 필요 없이
  그대로 갖다 쓰면 됨 - **3단계(인증+WatchList) 전체 완성**. 상세는 위
  3절 10번, API 명세는 2절 참고.
- **[해결됨] 회사 검색이 현재 데이터(558개 회사) 범위 내에서만 동작하던 한계** -
  2026-07-27 corpCode.xml 확대로 `companies`가 118,535건이 됐다. WatchList가
  "아직 공시 없는 회사도 미리 등록해 미래 알림을 받는다"는 원래 의미를 이제
  온전히 갖게 됨(상세: 위 1절 corpCode.xml 항목, 3절 11번).

### 다음 세션 시작점 — 검색 품질 측정 사이클 완전히 종료, 다음 우선순위는 4단계 알림

**1~3단계, corpCode.xml 확대, 데이터 규모 확대(7일치), HNSW 검증, 검색 품질
정식 측정(정형+서술형 두 벤치마크)과 그 결과에 따른 의사결정까지 전부 완료**.
현재 데이터 규모: `companies` 118,535건 / `disclosures` 3,991건 / `chunks`
173,035건(전량 임베딩 완료). 예산 $4.93 중 $2.2268 사용, 남은 약 $2.70.

**검색 품질 측정 사이클 요약(상세: 위 1절, [docs/search-quality-eval.md](./search-quality-eval.md)
전체)**: 정형 12개 벤치마크에서 "미스매치엔 벡터가 유리할 것"이라는 가설과
반대로 키워드가 가장 강하게 나왔는데(벡터 Recall@20 0.083), 이 벤치마크 자체가
corp_code+report_nm 유일성 위주로 뽑히다 보니 정형 서식 문서로 쏠린 선택
편향임을 스스로 발견하고, 서술형 문서 벤치마크(6개)를 추가해 보완했다.
결과: **어휘 미스매치가 아니라 문서 유형(정형 서식 vs 서술형)이 벡터
기여도를 좌우하는 진짜 변수**였다 - 벡터 MRR이 정형 0.025→서술형 0.504로
20배, 어휘를 EXACT로 고정해도 문서 유형만 바꾸면 키워드-벡터 격차가 93%
줄어듦(0.687→0.047). 이 결과에서 나온 "그럼 문서 유형별로 검색 전략을
분기해야 하는가"라는 질문은 **검토 후 지금은 구현하지 않기로 결정**했다
(표본 18개로는 과적합 위험, 분기 기준 자체가 불명확, "질의 시점에 답
문서 유형을 미리 모른다"는 근본 난제 - 상세 근거는 docs/search-quality-eval.md
10절). 구현 트리거(더 큰 표본으로 재확인되거나 `/answers` 실사용에서 품질
문제가 관찰되면 재논의)만 남겨두고 **이 사이클은 완전히 종료**.

이 사이클 전체가 "측정 방법 자체를 의심하고 검증하는 과정의 연속"이었다는
점도 기록해뒀다(선택 편향을 스스로 발견 → 보완 → 결과 인용 실수를 원본과
대조해 자체 정정 → 구현 여부까지 근거 기반으로 판단) - docs/search-quality-eval.md
9절 참고.

**다음 세션 우선순위는 4단계(알림)** - "다음 세션 논의거리" 참고.

**검색 품질 측정에서 다루지 않고 남겨둔 후보 관점들**(우선순위 미정, 급하지 않음):

- **`SLIDING_WINDOW`(표 나열형) 잔여 21개 청크**(4절 미해결 항목) - 이번 12개
  질의에는 1건(Q10)만 걸렸음. 이 유형만 표적으로 하는 별도 확인은 아직 안 함.
- **top-K 중복 콘텐츠 비율 재측정**(1절/dart-findings.md 2-10) - "전체 청크의
  19.3%가 content 완전 동일"이라는 수치는 5,201건 기준. 173,035건 기준 재측정
  안 함.
- **매치 패턴 배지 임계값 재검토**(1절, `MatchPatternBadge`) - 강함(5위
  이내)/약함(20위 밖) 기준은 8개 질의 소규모 조사로 정한 값. 이번 12개 결과로
  재검토 가능(예: 벡터가 이렇게 약하면 "약함" 기준 자체를 재고할 여지).
- **HNSW는 근사(approximate) 인덱스라는 점** - "인덱스가 쓰이는지"는 확인했지만
  "정확도(recall)가 어떤지"(Seq Scan 대비 결과 겹침)는 아직 측정 안 함.
- **report_nm 유형 다양성별 품질 분포** - 이번 12개는 report_nm 10종을 다뤘지만
  전체 3,991건의 모든 유형을 커버하진 못함.

`JWT_SECRET`은 `.env`에 값이 채워져 있어야 백엔드가 뜬다(`.env.example`엔 키
이름만 있음). 다음 세션 시작 시 `docker compose up -d`(postgres/elasticsearch
healthy 확인) → `cd backend && ./gradlew bootRun` → `cd frontend && npm run
dev` 순서로 띄우면 된다.

### 다음 세션 논의거리 (우선순위 미정, 시작 시 바로 정할 것)

- ~~`corpCode.xml` 회사 목록 확대~~ — **완료(2026-07-27)**, 위 1절/3절 11번 참고.
- **4단계 알림 — 진행 중(2026-07-27 스키마+감지 쿼리 실측까지 완료)**. 전달
  방식(A: 화면 내 배지+목록)과 생성 시점(별도 스케줄러)은 확정됐고, Flyway
  V6/JPA 엔티티/EXPLAIN 실측까지 끝남. **다음 세션에 이어서 할 것**: ①감지
  로직 서비스 + 스케줄러(기본 비활성화) ②알림 조회/읽음 처리 API(인증 필수,
  본인 것만) ③프론트엔드(헤더 배지 + 알림 목록). 상세는 위 3절 12번 참고.
- ~~데이터 규모 확대 (이틀치 → 몇 달치)~~ — **완료(2026-07-27)**, 7일치로 확대
  (disclosures 147→3,991건, chunks 5,201→173,035건, 임베딩 전부 완료, 실제 비용
  $2.2268). 아래 "데이터 규모 확대 진행 상황" 참고.
- ~~검색 품질 측정~~ — **완료(2026-07-27)**. 정형 12개 벤치마크 → 선택 편향
  자체 발견 → 서술형 6개로 보완 → "문서 유형이 벡터 기여도를 좌우하는 진짜
  변수"라는 결론 → "그럼 검색 라우팅을 구현할지" 검토까지 마쳤고, **지금은
  구현하지 않기로 결정**(표본 부족/분기 기준 불명확/질의 시점 문서 유형을
  모르는 근본 난제 - 근거는 docs/search-quality-eval.md 10절). 이 사이클은
  완전히 종료 - 재논의 트리거(더 큰 표본 재확인 또는 실사용 품질 문제 관찰)만
  남김. 상세: [docs/search-quality-eval.md](./search-quality-eval.md) 전체,
  위 1절 참고.
- AWS 배포 + 무중단 배포
- **감사보고서/연결감사보고서 ZIP 내부 파일명 불일치 조사** — 4절에 기록된 미해결
  항목(`DocumentCollectorService.extractXmlFromZip`에서 `ZIP 안에 {rcept_no}.xml
  항목이 없습니다`). 데이터 규모 확대로 `FAILED` 건수가 5→37건으로 늘었지만
  `DocumentCollectorService`는 이미 건별 try/catch가 있어 이 유형 실패가 배치
  전체를 막지는 않아(블로커 아님) 계속 분리해서 다음으로 미룸 — 원인 파악에는
  그 report_nm 유형의 document API 응답을 직접 열어보는 별도 조사가 필요해서다.

**추천 순서**: 데이터 규모 확대 → 검색 품질 측정 → 4단계 알림 → AWS 배포 —
데이터부터 넓혀야 품질 측정이 의미 있고, 배포는 기능이 갖춰진 뒤 마지막에
보여주는 게 자연스럽기 때문.

### 데이터 규모 확대 진행 상황

**결정된 접근**: 이틀치(147건/5,201청크) 실측값을 외삽한 비용표는 참고용일 뿐 믿지
않기로 했다 — 786,390자 문서 1건이 전체 청크의 27%를 차지했던 사례처럼 "공시
건수"보다 "어떤 유형이 섞였는지"가 청크 수를 훨씬 크게 좌우해서다. 대신 수집→
정제→청킹→색인까지는 전부 무료(임베딩만 유료)라는 점을 이용해 **후보 기간을
먼저 무료로 통째로 돌려 실제 청크/토큰 수를 확정한 뒤, 그 실측값으로 임베딩
범위를 최종 결정**하기로 했다. 순서: 1단계(파이프라인 준비) → 2단계(무료 수집·
청킹·색인 실행) → 3단계(jtokkit 실측 비용 계산 후 임베딩 범위 확정).

- **[완료] 1단계 — 파이프라인 준비** — 실행 전 두 가지를 먼저 고쳤다.
  - `DisclosureCollectorService.collect(beginDate, endDate)`를 `public`으로
    노출(기존엔 `collectToday()`만 공개돼 있어 과거 기간을 당길 방법이 없었음),
    `beginDate.isAfter(endDate)` 가드 추가, 첫 페이지에서 `totalCount`를 로그로
    남기게 함(대량 요청 전에 무료로 규모를 먼저 가늠할 수 있게).
  - `collect()`의 페이지 순회 루프에 항목별 try/catch 추가 — 기존엔 한 건
    저장 실패가 그 페이지(그리고 이후 페이지까지) 전체를 막을 수 있었음(4절에
    기록된 미해결 리스크). `DocumentCollectorService.processDisclosure()`가
    이미 쓰던 패턴과 동일하게 맞춤. `saveIfNew()` 내부 `save()` 호출은
    클래스에 `@Transactional`이 없어 호출마다 개별 커밋되므로, 이 변경은
    순수하게 "한 건 예외가 나머지를 막지 못하게"만 하는 것이라 안전하다.
  - **핵심 발견 — "1회성 백필 모드"에 설정 변경이 필요 없었다**: 처음엔
    스케줄러 배치 크기(`batch-size: 20`, 10분 간격)를 일시적으로 키우는
    방식을 생각했는데, 코드를 다시 보니 `DocumentCollectorService.
    collectPending(limit)` / `ChunkingService.chunkPending(limit)` /
    `IndexingService.indexPending(limit)` / `EmbeddingService.
    embedPending(limit)` 전부 이미 임의의 `limit`을 받는다 — `batch-size`는
    스케줄러가 10분마다 몇 건씩 집을지만 정할 뿐, 서비스 메서드 자체는
    스케줄러 없이 큰 수를 직접 넘겨 한 번에 처리할 수 있다. 그래서 서비스
    로직/설정값 변경 없이, 이 메서드들을 큰 `limit`으로 직접 호출하는
    ManualIT만 추가하면 됐다.
  - **신규 ManualIT 5개**(서비스당 1개 — `CorpCodeSyncManualIT` 선례와 컨벤션
    일관, 단계별 독립 실행 가능, 특히 유일한 유료 단계인 임베딩을 별도
    파일로 격리): `DisclosureCollectorManualIT`(collector 패키지, `backfillRange()`,
    최근 90일 상대 계산 — 날짜 하드코딩 없음), `DocumentCollectorManualIT`
    (collector, `backfillAll()`), `ChunkingManualIT`(chunking, `backfillAll()`),
    `IndexingManualIT`(indexing, `backfillAll()`), `EmbeddingManualIT`
    (embedding, `pilotEmbed()`/`fullBackfillEmbed()` — 기존 임베딩 파이프라인
    도입 때 썼던 파일럿→전체 2단계 패턴 재사용).
  - **이중 안전장치**: "아직 실제 대량 수집은 하지 마"라는 지시를 코드
    레벨에서도 지키도록, 5개 파일 전부에 `BACKFILL_APPROVED = false` 플래그를
    넣어 2단계 실행을 명시적으로 승인하기 전까지는 아무 것도 하지 않고
    메시지만 출력한다(`manualTest` 태스크를 통째로 돌리거나 실수로 개별
    실행해도 안전). 임베딩은 한 겹 더 둬서 `fullBackfillEmbed()`가
    `BACKFILL_APPROVED`와 `BACKFILL_LIMIT > 0`을 모두 만족해야만 동작한다.
    `embedding.collector.enabled: false`도 그대로 유지(스케줄러와 수동 호출이
    동시에 임베딩을 부르는 경로 차단).
  - 검증: `./gradlew test`(회귀, 기존 스위트 전부 통과 — `collect()`의 새 로그
    라인이 앱 컨텍스트 기동 시 실제 스케줄러 호출에서도 정상 출력됨을 확인),
    `./gradlew manualTest --tests`로 새 ManualIT 5개만 타겟팅해 실행 —
    5개 전부 가드 메시지만 출력하고 실제 API 호출/DB 변경 없이 종료됨을 확인
    (다른 유료 ManualIT는 타겟팅에서 제외해 실행되지 않음).
  - **의도적으로 분리한 것**: `감사보고서`/`연결감사보고서` `FAILED` 5건 조사는
    이번 1단계에 포함하지 않음 — 위 "다음 세션 논의거리" 참고.
- **[완료] ★ 실행 전 발견 — 기존 비용 추정의 기준값 자체가 틀려 있었다** —
  2단계를 실행하기 전, `DisclosureCollectorManualIT.peekCount()`(DB에 아무것도
  쓰지 않는 순수 조회, list API를 `page_count=1`로만 호출)로 최근 7/14/30/60/90일
  `totalCount`를 먼저 무료로 확인했다. 90일 결과가 **55,310건**(일평균 614.6건)으로
  나와, 이전 세션까지 비용 추정의 기준값이었던 "이틀치 147건"(73.5건/일)의 **약
  8배**였다 — 90일로 그대로 갔으면 예산을 5배 넘게 초과할 뻔했다. 구간을 7/14/30/
  60/90일로 나눠 비교해본 결과 일평균이 505~615건으로 전 구간에서 고르게 나타나
  특정 시즌(반기보고서 등)에 몰린 게 아니라 **꾸준히 이 규모**라는 것도 확인했다.
  더 중요한 발견은, 7/20~21이 포함된 "최근 7일" 구간도 569.9건/일로 나온다는
  점이었다 — **기존 "이틀치 147건" 실측값 자체가 그 날짜의 전체 공시가 아니라
  부분 수집(세션 초반 앱을 처음 띄운 시점 이후분만 잡혔을 가능성)이었다**는 뜻.
  지금까지의 모든 비용 추정(외삽표 포함)이 이 잘못된 기준값 위에서 계산된
  것이었음을 실행 직전에 잡아냈다 - "실측 후 결정" 원칙이 실제로 막아준 사고.
  이 발견 이후 가장 보수적인 **7일(예상 3,989건)**을 후보로 확정했다.
- **[완료] 2단계 — 무료 수집·청킹·색인 실행** — 5개 ManualIT의 `BACKFILL_APPROVED`를
  순서대로 `true`로 바꿔가며 개별 실행하고, 끝나면 다시 `false`로 되돌리는 방식으로
  진행했다.
  - **수집**(`DisclosureCollectorManualIT.backfillRange()`, 7/20~7/26): 신규
    596건(스킵 3,393건 — 기존 데이터와 겹치는 부분은 `rcept_no` 중복 체크로 안전하게
    걸러짐), `disclosures` 3,395 → **3,991건**(`peekCount()`의 `totalCount=3,989`와
    거의 일치 확인).
  - **원문 수집**(`DocumentCollectorManualIT.backfillAll()`, PENDING 2,015건):
    2분 32초, 결과 `PARSED` 3,568 / `NO_DOCUMENT` 239 / `FAILED` 37(+기존
    `INDEXED` 147 = 3,991건 합계 일치). `FAILED`가 5→37건으로 늘었는데 감사보고서
    ZIP 문제와 같은 유형으로 추정 — 별도 조사 항목으로 남김(위 "다음 세션
    논의거리" 참고).
  - **청킹**(`ChunkingManualIT.backfillAll()`, PARSED 3,568건) — **첫 시도에서
    프로세스가 예외 로그 하나 없이 죽는 사고**를 겪었다. `chunkPending(50000)`을
    한 번에 호출했는데, 대상이 `PENDING`과 달리 이미 `raw_content`(최대 78만자
    문서 포함)를 갖고 있는 `PARSED`라 `findByDocumentStatus()`가 3,568건의 큰
    텍스트를 즉시 메모리에 전부 로딩한 게 원인으로 추정된다(예외/스택트레이스가
    전혀 없어 정확한 원인은 특정 못함 - OS/샌드박스 강제 종료의 특징과 일치).
    `DocumentCollectorManualIT`가 무사했던 건 그때 대상(`PENDING`)이 아직
    원문 없이 메타데이터만 가벼웠기 때문. **대응**: `BATCH_SIZE=200` 단위로
    나눠 반복 호출하도록 재작성(서비스 로직 변경 없음, `chunkPending(int)`을
    여러 번 호출하는 것뿐) → 재시도 성공, 1분 20초, `chunks` 5,201 →
    **173,035건**(+167,834, 평균 47개/공시).
  - **색인**(`IndexingManualIT.backfillAll()`, CHUNKED 3,568건) — 청킹에서
    겪은 것과 같은 이유(`CHUNKED` 공시도 `raw_content`를 그대로 갖고 있음)로
    처음부터 `BATCH_SIZE=200` 반복 방식을 선제 적용해 문제없이 완료, 1분 55초,
    `INDEXED` 147 → **3,715건**.
- **[완료] 3단계 — jtokkit 실측 비용 계산 (API 호출 0)** — `EmbeddingManualIT`에
  `estimateCost()`를 새로 추가했다. `chunks.content`를 `List`로 한 번에 읽지 않고
  `JdbcTemplate`의 `RowCallbackHandler`로 한 행씩 스트리밍 처리해, 청킹 때 겪은
  "대량 텍스트 한 번에 로딩" 위험을 애초에 피했다. 결과: 임베딩 대기 청크
  **167,834건**, 총 토큰 **111,340,288개**, 예상 비용 **$2.2268**(예산 $4.93 대비
  약 45%, 남은 약 $2.7).
- **[완료] 4단계 — 임베딩 실행 중 OpenAI TPM 레이트리밋 발견 + 속도 조절 구현** —
  파일럿(10건, `vector_dims=1536`/`round-trip` 정상 확인) 후 전체 백필을 제한 없이
  실행했다가 **429(Too Many Requests) 3,235건**이 쏟아지는 걸 발견했다. 에러 응답을
  직접 열어본 결과 원인은 명확했다: `"Rate limit reached for text-embedding-3-small
  ... tokens per min (TPM): Limit 1,000,000"` — 우리 sub-batch(100청크 ≈ 실측 평균
  663.4토큰/청크 × 100 ≈ 66,340토큰)를 텀 없이 연달아 쏘다 보니 분당 100만 토큰
  한도 바로 앞에서 계속 걸리는 상황이었다. 다행히 429는 과금되지 않고(토큰 미생성),
  실패한 sub-batch는 `embedding IS NULL`로 남아 다음 호출에서 자연 재시도되는
  구조라 데이터 유실은 없었지만, 의미 없는 재시도가 낭비였다 — 사용자 요청으로
  일시 중단(당시 71,411건 이미 안전하게 저장 확인) 후 설계·구현했다.

  **3계층 방어**로 설계: ①**사전 예방** — `EmbeddingService`가 sub-batch 사이
  최소 간격을 둬서 애초에 429가 거의 안 나게 함(실측 663.4토큰/청크 기준
  sub-batch당 ≈66,340토큰, TPM 한도의 80%(80만) 목표로 역산 → 분당 12회 →
  **5,000ms 간격**). ②**즉시 재시도** — 그래도 429가 뜨면 `OpenAiEmbeddingClient`가
  `HttpClientErrorException.TooManyRequests`만 특정해서 잡아 지수 백오프(2s→4s→8s,
  최대 3회) 재시도, 400/401 등 다른 에러는 즉시 실패(재시도해도 소용없어서).
  ③**기존 안전망** — 그래도 실패하면 기존 그대로 `embedding IS NULL`로 남아 다음
  배치 반복에서 재시도. 설정값은 `application.yml`의
  `embedding.rate-limit.{min-interval-ms, max-retries, initial-backoff-ms}`로
  분리(계정 TPM 등급이 바뀌면 재계산 필요하다고 주석에 남김).

  **검증**: 500건 소량 재실행 → **429 0건**, `vector_dims`/`round-trip` 정상 확인
  후 전체(100,924건) 재개. 재개 도중 첫 실행이 약 51분(46,300건) 만에 코드
  에러 없이 외부 중단되는 일이 있었다(백그라운드 실행 시간 제한으로 추정) —
  `embedding IS NULL` 기준 덕분에 데이터 유실 없이 이어받아, 이후 40,000건 →
  14,124건으로 크기를 나눠 3회에 걸쳐 완료했다.

  **최종 결과**: `embedding IS NULL` **0건**(167,834건 전부 처리), 실패/스킵/
  응답개수불일치 전부 **0건**, **429는 속도 조절 적용 후 전 구간(500+46,300+
  40,000+14,124=100,924건) 0건**(적용 전 3,235건과 뚜렷한 대조 — 대책의 효과가
  숫자로 증명됨). 실제 총 토큰 111,340,288개는 **사전 jtokkit 추정치와 정확히
  일치**, 실제 비용 **$2.2268**도 예측과 일치 — 임베딩 파이프라인 도입 때
  확인했던 "사전 추정=로컬 계산=서버 응답 일치" 패턴이 이번에도 재현됐다.
  `EmbeddingManualIT`는 안전값(`BACKFILL_APPROVED=false`, `BACKFILL_LIMIT=0`)으로
  복귀, `embedding.collector.enabled`도 계속 `false`.

**최종 규모**: `companies` 118,535건(불변) / `disclosures` 147 → **3,991건**(27배) /
`chunks` 5,201 → **173,035건**(33배), 전부 임베딩 완료. 예산 $4.93 중 $2.2268
사용, 남은 약 **$2.70**(RAG 답변 테스트/검색 품질 측정/버퍼용).

- **[완료] 5단계 — HNSW 인덱스 실측 검증 (첫날 진단이 맞았는지 확인)** —
  ERD 설계 때(1절) "빈 테이블에서 시작해 배치로 채워지는 구조라 HNSW가
  IVFFlat보다 적합"이라 판단했고, 하이브리드 검색 검증(2026-07-23, 5,201건
  규모) 때는 `enable_seqscan=off`로 강제해야만 `idx_chunks_embedding_hnsw`가
  잡혔다 - "데이터가 적어 플래너가 Seq Scan을 고르지만, 데이터가 늘면 자연히
  인덱스를 쓰게 될 것"이라고 예상하고 미해결로 남겨뒀었다. 173,035건(33배)이
  된 지금 실제 질의("전환사채 발행조건과 전환가액은?")로 `VectorSearchService`가
  쓰는 것과 동일한 SQL(`ChunkRepository.findNearestByEmbedding`)에 `EXPLAIN
  (ANALYZE, BUFFERS)`를 걸어 재검증했다(질의 임베딩 1회, 20토큰, 비용 사실상 0).

  **결과: 이번엔 아무 강제 옵션 없이 플래너가 자연스럽게 `Index Scan using
  idx_chunks_embedding_hnsw`를 선택했다** - 실행 시간 **12.590ms**. 같은
  173,035건 데이터에 `enable_indexscan=off`/`enable_bitmapscan=off`로 Seq
  Scan을 강제해 비교하니 **738.863ms**(병렬 Seq Scan, worker 2개) - **HNSW가
  약 59배 빠르다.** 첫날 "데이터가 적어 안 쓰이지만 커지면 쓰일 것"이라던
  진단이 실측으로 정확히 증명됐고, IVFFlat 대신 HNSW를 고른 ERD 단계의 판단도
  결과적으로 유효했음이 확인됐다(둘 다 approximate 인덱스라 IVFFlat이었어도
  이 규모에서 인덱스가 잡혔을 가능성은 있지만, "빈 테이블에서 배치로 채워지는
  구조"라는 원래 근거 자체는 바뀌지 않았다는 의미).

  **pg_trgm과의 대조 — 같은 "미리 넣어둔 인덱스"인데 결과가 갈렸다**: 바로
  위(corpCode.xml 항목, dart-findings.md 2-12)에서 `pg_trgm` 인덱스는 실측
  결과 무효로 판명나 제거했다. 두 인덱스 모두 "나중에 필요할 것 같아 미리
  추가"한 점은 같은데 결과가 정반대인 이유는 세 가지가 다르기 때문이다:
  ①**쿼리 형태** - HNSW는 `<=>` 연산자(설계 의도)와 실제 쿼리가 정확히
  일치했지만, `pg_trgm`은 JPA `ContainingIgnoreCase`가 생성한
  `upper(corp_name) LIKE upper(?)`가 인덱스 predicate와 애초에 안 맞았다.
  ②**데이터 규모** - 173,035건 벡터 검색은 Seq Scan이 59배 느려질 만큼
  충분히 컸지만, 118,535건 `corp_name` `ILIKE`는 Seq Scan 자체가 이미
  27~42ms로 충분히 빨라 인덱스가 필요 없었다. ③**데이터/질의 특성** - 벡터
  1536차원 근사 검색은 선형 스캔이 본질적으로 느리지만, "삼성" 같은 한글
  2글자는 trigram 선택도가 낮아 인덱스를 강제해도 오히려 7배 더 느렸다.
  **교훈**: "인덱스를 미리 넣어두는 게 안전하다"는 직관은 정답이 아니다 -
  인덱스가 유효한지는 쿼리 형태·데이터 규모·데이터 특성 세 가지에 달려
  있고, 셋 다 실측(`EXPLAIN ANALYZE`)으로 확인해야만 안다는 게 이 프로젝트
  안에서 성공(HNSW)과 실패(pg_trgm) 두 사례로 대조 확인됐다.

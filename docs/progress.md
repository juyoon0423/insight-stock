# 진행 상황 (Progress)

마지막 업데이트: 2026-07-23

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
│       ├── domain/        # Company, Disclosure, Chunk, DocumentStatus, ChunkType, ChunkSource, support/VectorType
│       ├── repository/    # CompanyRepository, DisclosureRepository, ChunkRepository
│       ├── dart/          # DartListClient, DartDocumentClient, XmlTextExtractor, DartApiException, dto/
│       ├── collector/     # DisclosureCollectorService(+Scheduler), DocumentCollectorService(+Scheduler)
│       ├── chunking/      # ChunkingService(+Scheduler)
│       ├── indexing/      # ChunkDocument, ChunkDocumentRepository, IndexingService(+Scheduler)
│       ├── embedding/     # EmbeddingClient/OpenAiEmbeddingClient, TokenCounter, EmbeddingService(+Scheduler), dto/
│       ├── search/        # KeywordSearchService, VectorSearchService, RrfFuser, HybridSearchService, dto/
│       └── rag/           # PromptBuilder, ChatCompletionClient/OpenAiChatCompletionClient,
│                          # CitationParser, AnswerService, dto/
│   └── src/main/resources/
│       ├── application.yml
│       ├── elasticsearch/chunk-index-settings.json  # Nori 분석기 + 사용자 사전
│       ├── prompts/rag-system-prompt.txt  # RAG system prompt (코드와 분리, 튜닝 시 재컴파일 불필요)
│       └── db/migration/  # V1__init_schema.sql, V2__add_chunk_source.sql
├── tests/exploration/     # DART API 탐색용 파이썬 스크립트 (참고용, 자동화 테스트 아님)
├── .env / .env.example    # DART_API_KEY, POSTGRES_USER, POSTGRES_PASSWORD
└── requirements.txt       # tests/exploration/ 실행용 파이썬 의존성
```

`frontend/`는 아직 없음 (나중에 추가 예정). Controller도 아직 없음(프론트엔드 연동
시점에 필요).

### 현재 데이터 상태 (2026-07-22 기준, 임베딩 완료 후)

- `companies`: 558건, `disclosures`: 1960건, `chunks`: 5,201건 — ElasticSearch
  `chunks` 인덱스에도 5,201건 색인 완료(Postgres/ES 건수 일치 확인)
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
6. **[다음 세션] Controller/API 계층** — `AnswerService.answer(query)`를 HTTP로
   노출하는 REST 엔드포인트(예: `POST /api/answers`)를 추가하는 단계. 이후
   Next.js 프론트엔드(`frontend/`, 아직 미착수)에서 이 API를 호출하는 흐름으로
   이어질 예정.

## 4. 알려진 미해결 / 주의사항 (반드시 기록)

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
- **다음 작업은 Controller/API 계층**(3절 6번 참고) - `AnswerService`를 REST
  엔드포인트로 노출한 뒤 Next.js 프론트엔드 연동으로 이어간다. OpenAI 크레딧
  잔액은 이번 세션 검증 비용($0.0017)을 반영해 약 $4.93 그대로 유효.

# 진행 상황 (Progress)

마지막 업데이트: 2026-07-22

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
  `LazyInitializationException`이 나는 버그가 있었고, `chunkPending()`에
  `@Transactional`을 추가해 해결(배치 전체를 하나의 트랜잭션으로 묶되, 문서별
  실패는 내부 try/catch로 흡수해 다른 문서 처리에 영향 없음).

Repository/Service 계층 중 임베딩, ElasticSearch 색인은 아직 없음 — 다음 단계.

## 2. 현재 프로젝트 상태 요약

### 기술 스택

| 영역 | 선택 |
|---|---|
| 언어/런타임 | Java 17 |
| 백엔드 프레임워크 | Spring Boot 3.5.16 (Web, Data JPA, Validation, Actuator) |
| 빌드 도구 | Gradle 8.11.1 (wrapper 포함) |
| DB | PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`) |
| 검색엔진 | Elasticsearch 8.15.3 + Nori(한글 형태소 분석기) |
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
│       ├── repository/    # CompanyRepository, DisclosureRepository
│       ├── dart/          # DartListClient, DartDocumentClient, XmlTextExtractor, DartApiException, dto/
│       ├── collector/     # DisclosureCollectorService(+Scheduler), DocumentCollectorService(+Scheduler)
│       └── chunking/      # ChunkingService(+Scheduler)
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/  # V1__init_schema.sql, V2__add_chunk_source.sql
├── tests/exploration/     # DART API 탐색용 파이썬 스크립트 (참고용, 자동화 테스트 아님)
├── .env / .env.example    # DART_API_KEY, POSTGRES_USER, POSTGRES_PASSWORD
└── requirements.txt       # tests/exploration/ 실행용 파이썬 의존성
```

`frontend/`는 아직 없음 (나중에 추가 예정). Controller도 아직 없음(프론트엔드 연동
시점에 필요).

### 현재 데이터 상태 (2026-07-22 기준)

- `companies`: 357건, `disclosures`: 1480건, `chunks`: 1,337건
- `document_status` 분포: `PENDING` 1405 / `PARSED` 39 / `CHUNKED` 20 / `NO_DOCUMENT` 15 / `FAILED` 1
  (`FAILED` 1건은 document API가 ZIP 안에 `{rcept_no}.xml`을 안 준 개별 케이스 —
  재시도 로직은 아직 없음, 필요하면 나중에 추가)
- `chunks.chunk_source` 분포: `SLIDING_WINDOW` 1,221 / `SECTION` 105 / `WHOLE_DOCUMENT` 11
  (`SLIDING_WINDOW`가 큰 건 문서 하나(투자설명서, 정규식 폴백 경로, 786,390자)가
  1,210개 청크를 만들어낸 영향 — dart-findings.md 2-5 참고)
- 다음 세션 시작 시 백엔드를 띄우면 list/document/chunking 스케줄러가 모두 즉시 1회
  더 실행되므로, 켜두는 시간에 비례해 `PARSED`→`CHUNKED` 비율이 계속 올라간다.

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

list API 수집, document 원문 수집, 청킹까지 완료. 순서는 아래 우선순위대로.

1. **[우선] 청크 폭증 문서 대응** — 정규식 폴백을 탄 문서는 표가 압축되지 않고
   셀이 한 줄씩 풀려서 텍스트가 부풀어난다(dart-findings.md 2-5, 실측:
   786,390자 문서 하나가 1,210개 청크). 임베딩 비용이 청크 개수에 비례하므로,
   **임베딩을 붙이기 전에 반드시 처리**해야 한다. 검토할 옵션:
   - 정규식 폴백에서도 표를 압축 (예: 연속된 짧은 줄들을 휴리스틱으로 묶어
     `"셀1 | 셀2"` 형태로 재구성 — 원본 구조 정보가 없어 완벽할 수 없음)
   - 문서당 청크 수 상한 설정 (초과 시 뒷부분을 버릴지, 다른 방식으로 압축할지
     결정 필요)
   - 저품질 청크 필터(`chunking.empty-content-filter`)를 강화해 정보량 낮은
     청크를 더 적극적으로 제거
   - 세 옵션을 조합할 수도 있음 — 착수 전에 `SLIDING_WINDOW` 소스 청크들을
     몇 개 더 열어보고 실제로 어떤 게 부풀어나는지 확인부터 할 것
2. **[그다음] 임베딩** — OpenAI 크레딧 충전 후 착수. `chunks.embedding IS NULL`인
   청크를 배치로 찾아 `text-embedding-3-small`(1536차원)로 임베딩.
   `ChunkingService`가 임베딩 코드를 전혀 참조하지 않게 분리해뒀으므로, 별도
   `EmbeddingService` + 임베딩 클라이언트 인터페이스(예:
   `EmbeddingClient.embed(String text): float[]`)를 새 패키지(예: `embedding/`)에
   추가하는 형태가 될 것. `document_status`에 별도 `EMBEDDED` 상태는 두지 않고
   `chunks.embedding IS NULL` 자체를 "임베딩 대기" 표시로 쓴다.
   `chunks.embedding_model`에 사용한 모델명 기록(모델 교체 시 재임베딩 대상 식별용).
3. **[그다음] ElasticSearch 색인** — 비용 0, 크레딧 없이 바로 가능. `chunks.content`를
   ElasticSearch에 색인(`chunks.embedding`은 pgvector 색인용 — RRF 융합을 위해 두
   검색의 단위를 청크로 일치시키는 설계, [docs/erd.md](./erd.md) 참고). 청크 폭증
   문제를 먼저 해결해야 색인 개수도 합리적인 수준이 되므로 1번 이후에 진행.

## 4. 알려진 미해결 / 주의사항 (반드시 기록)

- **정규식 폴백 경로가 실제로 ~30% 발동, 청킹 구조 정보를 통째로 잃음** — 이전에는
  "미검증"이었지만 실제 배치(20건)로 확인: 6건이 폴백을 탔고, 이 경로는
  `[[SECTION]]` 마커도 표 라벨-값 구조(`" | "` 조합)도 만들지 않는다. 청킹은 마커
  유무로 전략을 분기(`chunk_source`)해 대응했지만, 폴백 경로 자체(`lxml recover`
  같은 관대한 파서 도입)는 아직 고치지 않았다 — 검색 품질을 측정해 `SLIDING_WINDOW`
  경로 성능이 눈에 띄게 나쁘면 그때 재검토. 상세: dart-findings.md 2-5.
- **`SLIDING_WINDOW` 경로가 극단적으로 큰 문서를 만들 수 있음** — 786,390자
  문서 하나가 청크 1,210개. 3절 1번(우선순위)에서 착수 예정이라 여기서는
  중복 설명하지 않음.
- **스케줄러 10분 간격이 개발 중엔 잦게 느껴질 수 있음** — list/document/chunking
  스케줄러 모두 `@Scheduled(fixedDelay)`라 앱을 띄울 때마다 즉시 1회 실행된다.
  개발 프로파일에서 주기를 늘리거나, 로컬 개발 시 스케줄러를 끄고 수동 트리거로
  대체하는 방안을 나중에 검토.
- **정형 서식 공시(소유상황보고서 등)는 빈 표가 많음 — 청킹에서 필터링으로 해결**
  `TABLE`은 있지만 실제 기재된 값이 없는 셀(빈 문자열)이 많아서 `raw_content`에
  `" | "`만 있는 줄이 섞여 들어가는 문제. `ChunkingService`가 청크 생성 후 "의미
  있는 문자(한글/영문/숫자) 비율"이 낮은 청크를 걸러내는 걸로 대응했고, 임계치는
  `application.yml`의 `chunking.empty-content-filter`(`min-length`,
  `min-meaningful-ratio`)로 설정값화해뒀다 — 너무 공격적으로 걸러진다 싶으면
  코드 변경 없이 이 값만 조정하면 됨.

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

- `.env`에 `DART_API_KEY`, `POSTGRES_USER`, `POSTGRES_PASSWORD`가 이미 채워져 있어야
  백엔드가 뜬다 (레포에는 `.env.example`만 있음, `.env`는 로컬에만 존재). 임베딩
  작업을 시작하면 `OPENAI_API_KEY`도 `.env`에 추가해야 함 (아직 없음).
- pgvector 매핑(`Chunk.embedding`)은 이미 검증 완료된 상태이니, 임베딩 관련 작업을
  다시 처음부터 조사할 필요 없음 — `VectorType`을 그대로 재사용.
- list/document/chunking 파이프라인(`repository/`, `dart/`, `collector/`,
  `chunking/` 패키지)은 이미 완성되어 백엔드를 띄워두면 계속 돌아간다. 다음
  작업(임베딩)은 이 위에 새 서비스(예: `embedding/` 패키지의 `EmbeddingService`)를
  추가하는 형태가 될 것 — `ChunkRepository`는 아직 없음(Disclosure가 cascade로
  chunks를 관리하는 애그리게이트 루트라 지금까지는 필요 없었음), 임베딩 배치가
  `chunks WHERE embedding IS NULL`을 직접 조회하려면 추가해야 함.

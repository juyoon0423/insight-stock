# 진행 상황 (Progress)

마지막 업데이트: 2026-07-21

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

Repository/Service 계층 중 청킹, 임베딩, ElasticSearch 색인은 아직 없음 — 다음 단계.

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
│       ├── domain/        # Company, Disclosure, Chunk, DocumentStatus, ChunkType, support/VectorType
│       ├── repository/    # CompanyRepository, DisclosureRepository
│       ├── dart/          # DartListClient, DartDocumentClient, XmlTextExtractor, DartApiException, dto/
│       └── collector/     # DisclosureCollectorService(+Scheduler), DocumentCollectorService(+Scheduler)
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__init_schema.sql
├── tests/exploration/     # DART API 탐색용 파이썬 스크립트 (참고용, 자동화 테스트 아님)
├── .env / .env.example    # DART_API_KEY, POSTGRES_USER, POSTGRES_PASSWORD
└── requirements.txt       # tests/exploration/ 실행용 파이썬 의존성
```

`frontend/`는 아직 없음 (나중에 추가 예정). Controller도 아직 없음(프론트엔드 연동
시점에 필요).

### 현재 데이터 상태 (2026-07-21 기준)

- `companies`: 268건, `disclosures`: 1204건 (오늘 자 DART 공시 전체)
- `document_status` 분포: 대부분 `PENDING`(아직 원문 미수집), 그중 일부(수십 건)만
  검증 목적으로 처리해 `PARSED`/`NO_DOCUMENT`로 전환된 상태. 나머지 `PENDING`은
  스케줄러가 계속 뜨면 배치 크기(20건)씩 자동으로 처리됨.
- 다음 세션 시작 시 백엔드를 띄우면 스케줄러가 즉시 1회 더 실행되므로, 켜두는
  시간에 비례해 `PARSED`/`NO_DOCUMENT` 비율이 계속 올라간다.

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

## 3. 다음에 할 일 — 청킹 / 임베딩 / 인덱싱 (검색 재료 만들기)

list API 수집과 document 원문 수집은 완료. 다음은 `raw_content`를 실제 검색 가능한
형태(청크 + 임베딩 + 색인)로 만드는 단계다.

1. **`raw_content`를 청킹 전략대로 분할**
   - `report_nm` 기준으로 문서 유형별 분기 (소유상황보고서류처럼 작은 문서는 통째로
     1개 청크, 투자설명서·사업보고서류처럼 큰 문서는 구조 기반 1차 분할)
   - 구조 기반 1차 분할은 `TITLE`(법정 서식 섹션 경계) 기준. 단, `raw_content`는
     이미 태그가 제거된 순수 텍스트이므로, 섹션 경계를 유지하려면 정제 단계에서
     섹션 제목을 마커로 남겨둘지, 아니면 청킹 시점에 원문 XML을 다시 파싱할지
     결정이 필요함 (현재 `XmlTextExtractor`는 섹션 마커를 남기지 않음 — 설계 시 확인)
   - 섹션이 너무 크면 500~1,000자(또는 토큰) + 100~150자 오버랩으로 슬라이딩 윈도우
   - 표는 이미 `raw_content`에 `"셀1 | 셀2"` 형태로 직렬화돼 있으므로 통째로 유지
   - 상세 근거는 [docs/dart-findings.md](./dart-findings.md) 3절 참고
2. **각 청크를 OpenAI `text-embedding-3-small`(1536차원)로 임베딩**
3. **`chunks` 테이블에 저장 (pgvector) + 동일 청크를 ElasticSearch에도 색인**
   - `chunks.content`는 ElasticSearch 색인용, `chunks.embedding`은 pgvector 색인용
     (RRF 융합을 위해 두 검색의 단위를 청크로 일치시키는 설계, [docs/erd.md](./erd.md) 참고)
4. **`document_status`를 `CHUNKED`로 전이**
   - 청킹 대상은 `document_status = PARSED`인 disclosure만 (`NO_DOCUMENT`는 원문이
     없으므로 청킹 대상에서 자연히 제외됨)

## 4. 알려진 미해결 / 주의사항 (반드시 기록)

- **정규식 폴백 경로 미검증** — `XmlTextExtractor`의 "엄격 파싱 실패 시 정규식 태그
  제거로 폴백" 경로는 로직만 구현돼 있고, 오늘 처리된 문서가 전부 DOM 파싱에
  성공해서 실제로는 한 번도 타지 않았다. dart-findings.md 2-2에서 확인된 것 같은
  비표준 XML(이스케이프 안 된 `<` 문자 등) 문서를 나중에 실제로 만나면, 폴백이
  제대로 동작하는지(빈 텍스트나 깨진 결과를 내지 않는지) 반드시 확인할 것.
- **스케줄러 10분 간격이 개발 중엔 잦게 느껴질 수 있음** — list/document 수집
  스케줄러 둘 다 `@Scheduled(fixedDelay)`라 앱을 띄울 때마다 즉시 1회 실행된다.
  개발 프로파일에서 주기를 늘리거나, 로컬 개발 시 스케줄러를 끄고 수동 트리거로
  대체하는 방안을 나중에 검토.
- **정형 서식 공시(소유상황보고서 등)는 빈 표가 많음** — `TABLE`은 있지만 실제
  기재된 값이 없는 셀(빈 문자열)이 많아서, `raw_content`에 `" | "`만 있는 줄이나
  빈 값이 섞여 들어갈 수 있다. 청킹/임베딩 설계 시 이런 "정보량이 거의 없는 텍스트"를
  어떻게 다룰지(그대로 청크화 vs. 필터링) 고려 필요.

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
- list/document 수집 파이프라인(`repository/`, `dart/`, `collector/` 패키지)은 이미
  완성되어 백엔드를 띄워두면 계속 돌아간다. 청킹 작업은 이 위에 새 서비스
  (예: `ChunkingService`)를 추가하는 형태가 될 것.
- 청킹 로직을 짜기 전에, 실제 `raw_content` 몇 건(작은 문서/큰 문서/표 많은 문서
  각각)을 DBeaver나 psql로 직접 열어보고 어떤 모양인지 확인부터 할 것 — 상상으로
  설계하지 않는다는 프로젝트 원칙(CLAUDE.md) 그대로 적용.

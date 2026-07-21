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

Service 계층 중 document 원문 수집(ZIP→XML 파싱, `raw_content` 저장), 청킹, 임베딩은
아직 없음 — 다음 단계.

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
│       └── domain/        # Company, Disclosure, Chunk, DocumentStatus, ChunkType, support/VectorType
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__init_schema.sql
├── tests/exploration/     # DART API 탐색용 파이썬 스크립트 (참고용, 자동화 테스트 아님)
├── .env / .env.example    # DART_API_KEY, POSTGRES_USER, POSTGRES_PASSWORD
└── requirements.txt       # tests/exploration/ 실행용 파이썬 의존성
```

`frontend/`는 아직 없음 (나중에 추가 예정).

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

## 3. 다음에 할 일 — document API 원문 수집

list API 수집(2번 항목)은 완료. 다음은 `PENDING` 상태 disclosure의 원문을 받아와
정제해 `raw_content`에 저장하는 단계다.

1. **`PENDING` 상태 disclosure를 골라 document API 호출**
   - `DisclosureRepository`에 `findByDocumentStatus(PENDING)` 같은 조회 추가
   - 건별로 document API(`rcept_no` 기준) 호출
2. **ZIP 안 XML을 받아 정제 후 `raw_content`에 저장**
   - 응답이 ZIP인지 먼저 확인(`PK\x03\x04` 시그니처) → ZIP이면 내부
     `{rcept_no}.xml` 추출
   - 정제(태그 제거)한 텍스트를 `raw_content`에 저장, `char_length` 기록
   - `document_status`를 `FETCHED` → `PARSED`로 갱신
3. **오늘 dart-findings.md에서 실제로 확인한 함정을 반영**
   - `status=014`(원문 없음): ZIP이 아니라 XML 에러 응답이 오므로 `zipfile.is_zipfile`
     류의 판별이 필요. `has_document=false`로 표시하고 원문 없이 스킵 — 이때
     `document_status`를 어떤 값으로 둘지(예: `PARSED`로 두고 빈 컨텐츠 처리 vs.
     전용 상태 추가)는 구현 시점에 결정
   - well-formed하지 않은 XML(이스케이프 안 된 `<` 문자 등): 엄격한 파서 단독 사용 금지.
     `lxml recover=True`급 관대한 파서 또는 파싱 실패 시 정규식(`re.sub(r"<[^>]+>", ...)`)
     폴백 준비. 자바 쪽에서는 관대한 XML 파서 라이브러리 선정이 먼저 필요
   - 문서 크기 편차(1,591자~123,413자)는 이 단계(정제/저장)에서는 문제되지 않고,
     다음 단계인 청킹에서 `report_nm` 기준 분기로 대응할 예정

## 4. (그 다음 단계, 이번 범위 아님) 청킹/임베딩

- `report_nm` 기준으로 청킹 전략 분기, `chunks` 테이블에 저장
- 임베딩 생성 후 `chunks.embedding`에 저장, ElasticSearch에도 색인

## 5. 나중에 검토할 것

- **스케줄러 주기(현재 10분 고정, `dart.collector.fixed-delay-ms`)** — 개발 중에는
  실행마다 DART API를 반복 호출하고 로그도 쌓여 다소 잦게 느껴질 수 있다. 개발
  프로파일에서 주기를 늘리거나, 로컬 개발 시 스케줄러를 끄고 수동 트리거(테스트 코드나
  임시 엔드포인트)로 대체하는 방안을 나중에 검토.

## 6. 다음 세션 시작 시 참고할 컨텍스트

- **가장 먼저 읽을 문서**: [docs/dart-findings.md](./dart-findings.md) (API 응답 구조와
  함정), [docs/erd.md](./erd.md) (테이블 설계와 그 이유). document API 원문 수집 코드를
  짤 때 이 두 문서에 있는 함정(원문 없는 공시 `status=014`, non-well-formed XML, 문서
  크기 편차)을 그대로 반영해야 한다.
- Docker 컨테이너가 안 떠 있으면 `docker compose up -d --build`부터 실행할 것 (Postgres에
  `companies`/`disclosures`/`chunks` 테이블이 이미 Flyway로 만들어져 있음).
- `.env`에 `DART_API_KEY`, `POSTGRES_USER`, `POSTGRES_PASSWORD`가 이미 채워져 있어야
  백엔드가 뜬다 (레포에는 `.env.example`만 있음, `.env`는 로컬에만 존재).
- pgvector 매핑(`Chunk.embedding`)은 이미 검증 완료된 상태이니, 임베딩 관련 작업을
  다시 처음부터 조사할 필요 없음 — `VectorType`을 그대로 재사용.
- `CompanyRepository`/`DisclosureRepository`/`DartListClient`/`DisclosureCollectorService`/
  `DisclosureCollectorScheduler`는 list API 수집용으로 이미 있음. document API 원문
  수집은 이 위에 새 클라이언트/서비스를 추가하는 형태가 될 것 — Controller는 아직
  하나도 없음(프론트엔드 연동 시점에 필요).
- 백엔드를 띄우면 스케줄러가 시작 즉시 1회 실행되어 DART API를 호출한다 — 세션 시작
  직후 `./gradlew bootRun`을 켜두면 곧바로 실제 API 호출이 나간다는 점 인지할 것.

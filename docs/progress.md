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

Repository/Service 계층, 데이터 수집 배치, 청킹, 임베딩은 아직 없음 — 다음 단계.

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

## 3. 다음에 할 일 — DART 데이터 수집 파이프라인

아직 시작 안 함. 순서대로 진행 예정:

1. **Spring 스케줄러로 list API 주기 호출 → `companies`/`disclosures` 저장**
   - `@Scheduled` 배치로 DART list API를 주기 호출
   - 신규 회사면 `companies`에 upsert, 공시는 `disclosures`에 저장 (rcept_no 기준 UK로
     중복 방지)
   - 이 시점의 `document_status`는 `PENDING`
2. **document API로 원문 받아 정제 → `raw_content` 저장**
   - `PENDING` 상태 disclosure를 골라 document API 호출
   - `status=014`(원문 없음) 대응 — `has_document=false`로 표시하고 스킵
   - ZIP 안 XML을 태그 제거(well-formed 실패 시 정규식 폴백, dart-findings.md 참고)해서
     정제된 텍스트를 `raw_content`에 저장, `char_length` 기록, `document_status`를
     `FETCHED` → `PARSED`로 갱신
3. **(그 다음 단계, 이번 범위 아님) 청킹/임베딩**
   - `report_nm` 기준으로 청킹 전략 분기, `chunks` 테이블에 저장
   - 임베딩 생성 후 `chunks.embedding`에 저장, ElasticSearch에도 색인

아직 Repository/Service 계층이 전혀 없으므로, 1번 작업을 시작하려면 먼저
`CompanyRepository`/`DisclosureRepository`(Spring Data JPA)부터 만들어야 한다.

## 4. 다음 세션 시작 시 참고할 컨텍스트

- **가장 먼저 읽을 문서**: [docs/dart-findings.md](./dart-findings.md) (API 응답 구조와
  함정), [docs/erd.md](./erd.md) (테이블 설계와 그 이유). 파이프라인 코드를 짤 때 이
  두 문서에 있는 함정(원문 없는 공시, non-well-formed XML, 문서 크기 편차)을 그대로
  반영해야 한다.
- Docker 컨테이너가 안 떠 있으면 `docker compose up -d --build`부터 실행할 것 (Postgres에
  `companies`/`disclosures`/`chunks` 테이블이 이미 Flyway로 만들어져 있음).
- `.env`에 `DART_API_KEY`, `POSTGRES_USER`, `POSTGRES_PASSWORD`가 이미 채워져 있어야
  백엔드가 뜬다 (레포에는 `.env.example`만 있음, `.env`는 로컬에만 존재).
- pgvector 매핑(`Chunk.embedding`)은 이미 검증 완료된 상태이니, 임베딩 관련 작업을
  다시 처음부터 조사할 필요 없음 — `VectorType`을 그대로 재사용.
- 아직 Controller/Repository/Service가 하나도 없다 — 다음 세션은 이 계층을 만드는
  것부터 시작하게 된다.

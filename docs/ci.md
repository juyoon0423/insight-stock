# CI (GitHub Actions) — 테스트 자동화

**목적**: `main` push와 PR마다 백엔드 테스트(`./gradlew test`, 기본 스코프)가 자동으로 돌아
회귀를 조기에 잡는다.

**이번 범위**: CI(테스트 자동화)까지만. CD(자동 배포)는 범위 밖 — EC2를 평소 꺼두는 운영
방침([docs/deploy.md](./deploy.md), [progress.md](./progress.md) 참고)과 맞지 않아서
당분간 배포는 수동으로 유지한다.

> **2026-10-12 갱신**: `frontend` job을 추가했다(Node 22, `npm ci` → lint → `tsc --noEmit` →
> `npm test` → `npm run build`). 백엔드 job과 병렬로 돌고 Secrets가 필요 없다. Vercel 배포가
> 자동이 아니라서 깨진 프론트가 그대로 배포되는 것을 막는 최소한의 장치다. 또 백엔드 기본 test에
> 컨텍스트를 띄우는 테스트가 하나 늘었다(`SecurityAccessTest` - 유료 API 호출 없음, DB 조회만).
> 아래 본문은 백엔드 job에 대한 기존 설명이다.

## 워크플로우 파일

`.github/workflows/ci.yml` — 트리거는 `main` 브랜치 push와 모든 PR.

단계:

1. `actions/checkout@v4`
2. JDK 17 설치(`actions/setup-java@v4`, temurin)
3. Gradle 설정(`gradle/actions/setup-gradle@v4` — 캐싱 포함)
4. GitHub Secrets로 프로젝트 루트 `.env` 생성
5. `docker compose up -d --build`로 Postgres + Elasticsearch 기동
6. 두 컨테이너가 `healthy`가 될 때까지 대기(`docker inspect`로 헬스체크 상태 폴링)
7. `cd backend && ./gradlew test` (기본 태스크 — `@Tag("manual")` 자동 제외)
8. 실패 시에만 Gradle 테스트 리포트를 아티팩트로 업로드
9. `docker compose down` (항상 실행, `if: always()`)

## 로컬과 다른 점 — `services:` 대신 `docker-compose.yml` 재사용

GitHub Actions는 보통 `jobs.<job>.services:` 블록으로 컨테이너 서비스(Postgres 등)를
정의한다. 이번 CI는 그 방식을 쓰지 않고 **이미 로컬에서 검증된 루트 `docker-compose.yml`을
CI 러너 안에서 그대로 실행**한다.

이유:

- Elasticsearch는 공식 이미지가 아니라 `docker/elasticsearch/Dockerfile`로 Nori
  형태소 분석기 플러그인을 추가 설치한 **커스텀 이미지**다. `services:` 블록은 기존
  이미지를 그대로 받아쓰는 용도라 커스텀 빌드를 넣으려면 별도 스텝이 더 필요해진다.
- `services:`로 새로 옮겨 적으면 로컬 설정(포트, 헬스체크, 볼륨 등)과 CI 설정을
  이중으로 관리하게 되고, 둘이 미묘하게 어긋날 여지가 생긴다. 이미 로컬에서
  `docker compose up -d --build`로 검증된 구성을 그대로 재사용하면 이 문제가 사라진다.
- 헬스체크도 `docker-compose.yml`에 이미 정의돼 있는 것(`pg_isready`, ES
  `_cluster/health`)을 그대로 쓰고, CI 워크플로우에서는 그 상태(`healthy`)가 될 때까지
  기다리기만 한다.

## 환경변수 / GitHub Secrets

기본 test 스코프(`@Tag("manual")` 제외)가 실제로 필요로 하는 값을 코드로 직접 확인하고
등록했다(추측으로 먼저 만들지 않음):

- `application-test.yml`은 없다 — 테스트도 `src/main/resources/application.yml`을 그대로
  쓴다.
- `@Tag("manual")`이 없는 `@SpringBootTest`는 3개뿐이다: `InsightStockApplicationTests`
  (contextLoads), `NotificationServiceTest`, `NotificationDetectionServiceTest`(둘 다
  실제 로컬 DB에 데이터를 넣고 `@Transactional`로 롤백 — 유료 API 호출 없음, 재확인
  완료). 나머지는 전부 컨텍스트를 띄우지 않는 단위 테스트다(순수 로직 테스트, JDK 내장
  `HttpServer`로 OpenAI를 대신하는 클라이언트 테스트, standalone MockMvc 테스트 - 2026-10-12
  기준 15개 클래스, 유료 API 호출 없음).
- 이 3개가 컨텍스트를 띄우려면 아래 값들이 필요하다(`@Value`가 생성자 파라미터로 박혀
  있어 값이 없으면 플레이스홀더 해석 실패로 기동 자체가 안 됨):

| Secret | 왜 필요한가 | CI에서 실제 값 |
|---|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | datasource 연결에 실제로 사용 | CI 전용 임시 DB 계정(운영 값과 무관) |
| `DART_API_KEY` | `DartListClient`/`DartDocumentClient`/`DartCorpCodeClient` 생성자 주입 | 더미 문자열 — 이 3개 테스트는 빈만 생성하고 실제 호출은 안 함 |
| `OPENAI_API_KEY` | `OpenAiEmbeddingClient`/`OpenAiChatCompletionClient` 생성자 주입 | 더미 문자열 — 위와 동일 이유로 실제 호출 없음 |
| `JWT_SECRET` | `JwtTokenProvider`가 즉시 `Keys.hmacShaKeyFor(...)`로 HS256 키 생성 | CI 전용 랜덤 값(32바이트 이상 — 짧으면 `WeakKeyException`으로 기동 실패) |

`CORS_ALLOWED_ORIGINS`는 `application.yml`에 기본값(`http://localhost:3000`)이 있어
Secret 등록이 필요 없다.

이 값들이 실제로는 전혀 민감하지 않음에도(DART/OpenAI는 호출되지 않고, DB 계정도
CI 러너 안에서만 사는 임시 컨테이너용) GitHub Secrets로 등록한 이유는, 워크플로우
파일에 평문으로 값을 남기지 않는 관례를 그대로 지키기 위해서다(포트폴리오 관점에서도
실무 관례를 보여주는 편이 낫다는 판단).

## 실측 검증 결과

- **성공 케이스**: 워크플로우를 실제로 push해 Actions에서 통과 확인
  (run `30783475745`, job 3m18s, `./gradlew test` 자체는 `BUILD SUCCESSFUL in 1m 31s`,
  전체 워크플로우 소요 3m22s).
- **실패 케이스**: `RrfFuserTest`의 assertion을 일부러 깨서 push한 뒤(run `30783663714`,
  2m15s) "Run tests" 스텝이 `X`로 표시되고 워크플로우 conclusion이 `failure`로 남는 것,
  그리고 `if: failure()`로 걸어둔 테스트 리포트 업로드 스텝이 그때만 실행되는 것까지
  확인했다. 이후 즉시 원복 커밋을 push해 다시 성공(2m26s)으로 돌아오는 것까지 확인.
- 매번 `docker compose up -d --build`로 인프라를 새로 띄우므로(레이어 캐시 없음),
  전체 실행 시간은 약 3분 20초 안팎 — 대부분(1m31s)은 실제 Gradle 테스트, 나머지는
  인프라 기동/헬스체크 대기.

## 알려진 제약 / 다음에 고려할 것

- Docker 레이어 캐싱을 안 하므로 Elasticsearch 커스텀 이미지를 매번 새로 빌드한다.
  지금은 3분대로 충분히 빠르지만, 실행 빈도가 늘거나 이미지가 무거워지면 캐싱을
  고려할 수 있다(우선순위 낮음 — 지금은 과도한 최적화로 판단해 보류).
- CD(자동 배포)는 의도적으로 이번 범위에서 제외했다. EC2를 평소 꺼두는 운영 방침과
  안 맞고, 무중단 배포(ShedLock + Blue-Green) 자동화도 아직 수동 절차 단계라
  ([docs/deploy.md](./deploy.md) 13~17절) 먼저 자동 배포부터 붙이는 건 순서가 안 맞는다는
  판단.

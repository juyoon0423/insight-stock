# 부하테스트 (k6) — 검색 스레드풀/DB 커넥션풀 병목 발견 및 수정

**목적**: 로드맵 다음 단계로 하이브리드 검색(`/api/v1/search`)과 RAG 답변(`/api/v1/answers`)에
실제 동시 부하를 걸어 처리량/레이턴시 수치를 확보하고, 처리량이 꺾이는 지점을 실측한다.

**결과 요약**: 애초에 인프라(Postgres/Elasticsearch) 한계를 찾으려던 테스트였는데, 실제로는
**애플리케이션 자체의 스레드풀/커넥션풀 설정값**이 병목이었다는 게 밝혀졌다 — 고쳐서 재검증한
결과 동일 조건에서 성공률이 크게 개선됐다(아래 Before/After 참고).

## 도구/구조

- `backend/loadtest/search-load-test.js` — `GET /api/v1/search` 부하테스트. LLM 호출이
  없어 비용이 사실상 0이라 동시성을 자유롭게 올려서 검색 경로 자체의 처리량/레이턴시를 본다.
- `backend/loadtest/answers-load-test.js` — `POST /api/v1/answers` 부하테스트. 요청마다
  실제 gpt-4o-mini 호출이 발생해 비용이 들어서, VU가 아니라 `shared-iterations`로 총
  요청 수를 50건으로 못박는다.
- `backend/loadtest/data/queries.json` — 기존 검색 품질 측정용 질의 세트
  (`backend/src/test/resources/evaluation/search-quality-queries*.json`, 정형 12개 +
  서술형 6개 = 18개)에서 `query` 필드만 뽑아 재사용. 새로 만들 필요 없었음.
- `backend/loadtest/results/*.json` — 각 실행의 `--summary-export` 결과.

두 엔드포인트 다 `SecurityConfig.java:61-62`에서 `permitAll()`이라 JWT 없이 바로 호출 가능.

## 1차 실행 (Before) — 예상보다 훨씬 이른 병목 발견

**옵션 A(기준선, 30 VU ramp, 3분)**: 성공률 79.7%(287/360), 처리량 1.74 req/s, 성공한
요청만 봐도 p95=2.14s인데 실패한 요청은 30~60초를 기다리다 타임아웃.

**옵션 C(스트레스, 20→100 VU ramp, 9분)**: 성공률 15.96%(216/1353), 처리량 2.42 req/s —
**VU를 30에서 100으로 늘려도 처리량이 거의 그대로**였고, 성공한 요청의 지연시간도
p95=2.19s로 A와 거의 동일했다. 이게 결정적 단서였다 — DB/ES 자체가 느려진 게 아니라
대부분의 요청이 자원을 못 얻어 큐에서 대기만 하다 실패한다는 뜻.

### 근본 원인 — 코드 두 곳

1. **`SearchExecutorConfig.java:16`** — `Executors.newFixedThreadPool(4, ...)`. 검색
   1건당 키워드(ES)+벡터(pgvector) 태스크 2개가 이 고정 4개 스레드를 전체 요청이 공유.
2. **HikariCP 기본값에 암묵 의존**(`application.yml`에 설정이 아예 없어 Spring Boot
   기본 `maximum-pool-size=10`, `connection-timeout=30000ms` 그대로 적용). 백엔드 로그에서
   실측:
   ```
   HikariPool-1 - Connection is not available, request timed out after 30002ms
   (total=10, active=10, idle=0, waiting=100)
   ```
   옵션 C 피크에서 **커넥션 10개를 두고 최대 100개 요청이 동시 대기**했고, 관찰된 30초
   타임아웃 벽은 Hikari의 기본 `connection-timeout`과 정확히 일치했다.

즉 "처리량이 꺾이는 지점"은 인프라 한계가 아니라 동시 요청 2~4건 수준에서 이미 시작되고
있었다 — 애플리케이션 설정값이 원인이라는 게 실측으로 확인됐다.

### `/answers` (5 VU, 50건 캡)

47/50 성공, 3건 실패(502). **서버 로그로 원인을 특정**: 3건 전부
`HttpClientErrorException$TooManyRequests: 429` — "Rate limit reached for gpt-4o-mini
... Limit 200000, Used ~200000"(OpenAI 조직 단위 TPM 한도). **우리 서버 버그가 아니라
OpenAI 쪽 rate limit**이었다. 단, `GlobalExceptionHandler.handleRagApi()`
(`api/GlobalExceptionHandler.java:55-63`)가 원인과 무관하게 항상 HTTP 502로 뭉뚱그리기
때문에, 클라이언트가 받는 상태 코드만으로는 이 구분이 원천적으로 불가능하고 서버 로그
대조가 필수라는 것도 함께 확인됐다.

## 수정 내용

`OpenAiChatCompletionClient`의 재시도/백오프 부재나 `GlobalExceptionHandler`의 502
뭉개기는 **이번에 고치지 않고** `docs/progress.md` "다음 세션 논의거리"에 남겼다(별도
resilience 개선 작업으로 분리). 이번에 고친 건 위 두 가지 스레드풀/커넥션풀 설정뿐이다.

### Hikari `maximum-pool-size`: 10(암묵적 기본값) → 50(명시)

`application.yml`(`spring.datasource.hikari.maximum-pool-size: 50`)에 명시적으로
externalize했다. 산정 근거:
- Postgres `max_connections=100`(실측, `SHOW max_connections;`) — 이 DB는 백엔드
  하나만 쓰므로 다른 앱과의 경쟁이 없다.
- 목표 동시 사용자(현실적 로컬 부하 시나리오로 제안했던 10~50명)를 커넥션 큐잉 없이
  받아내면서, 100 한도 안에 headroom(Flyway/ShedLock/수동 psql 세션 등)을 남기는 값.

### `hybrid-search.executor.pool-size`(SearchExecutorConfig): 4(하드코딩) → 100(설정화)

기존에는 `Executors.newFixedThreadPool(4, ...)`로 하드코딩돼 있던 걸
`application.yml`의 `hybrid-search.executor.pool-size`로 옮기고 `SearchExecutorConfig`가
`@Value`로 주입받게 바꿨다(이 프로젝트의 다른 설정값과 같은 패턴). 산정 근거:
- 검색 1건당 태스크 2개(키워드→ES, 벡터→Postgres) 중 **벡터 태스크만 Hikari 커넥션을
  점유**한다. 풀을 Hikari와 같은 50으로만 두면, DB와 무관한 키워드(ES) 태스크까지
  스레드풀 단에서 또 한 번 직렬화되는 낭비가 생긴다.
- "벡터용 50개 + 키워드용 50개"가 동시에 돌 수 있도록 **Hikari 풀의 2배(100)**로 잡았다.
- 두 태스크 다 네트워크 I/O 대기가 대부분이라(ES/Postgres 응답 대기, CPU 연산은 미미)
  로컬 머신 코어 수(8)보다 스레드 수가 많아도 문제없다 — 블로킹 중인 스레드는 CPU를
  점유하지 않고, 스택 메모리도 100개 기준 약 100MB로 16GB RAM에 무리 없음.

## 재검증 (After) — 동일 조건으로 재실행

| | 옵션 A (30 VU, 3분) Before | 옵션 A After | 옵션 C (20→100 VU, 9분) Before | 옵션 C After |
|---|---|---|---|---|
| 성공률 | 79.7% (287/360) | **100% (1079/1079)** | 15.96% (216/1353) | **94.82% (2419/2551)** |
| 처리량 | 1.74 req/s | **5.98 req/s (3.4배)** | 2.42 req/s | **4.72 req/s (1.95배)** |
| 실제 성공 요청 수(3분/9분 동안) | 287건 | **1079건(3.8배)** | 216건 | **2419건(11.2배)** |
| 지연시간(성공 요청) p95 | 2.14s | 5.08s | 2.19s | 38.31s(80~100 VU 구간 포함) |
| Hikari "Connection not available" 로그 | - | - | 3,584건, peak waiting=100(풀 10) | 324건(11배 감소), peak waiting=101(풀 50) |

**해석**:
- 옵션 A는 완전히 해소됐다 — 실패 0건, 처리량 3.4배.
- 옵션 C(의도적 스트레스, 100 VU까지)는 여전히 최상단 구간(80~100 VU)에서 일부 큐잉이
  남아있다(94.82%는 100%가 아님, p95가 38초로 높음) — **이건 버그가 아니라 설계대로다.**
  Hikari 풀을 딱 50으로 잡았으니 동시 요청이 그보다 훨씬 많아지는 극단적 구간에서는
  대기가 발생하는 게 당연하다. 다만 Before와 달리 **대부분(94.82%)이 결국 성공**했다는
  점이 다르다 — Before는 못 버티고 실패(84% 실패)했지만, After는 느려지더라도 대부분
  완료된다(graceful degradation). "처리량이 꺾이는 지점"이 동시 2~4건 수준에서
  80~100 VU 수준으로 크게 올라간 것.
- 성공 요청 p95가 A는 2.14→5.08s, C는 2.19→38.31s로 오른 것은 "느려졌다"는 뜻이 아니라
  "이전엔 빨리 실패했던 요청들까지 이제 다 끝까지 처리되면서 그 대기시간이 지연시간에
  포함됐다"는 뜻 — Before의 p95는 (실패한 대다수를 제외한) 운 좋은 소수만의 수치였다.

## 실행 방법

```bash
cd backend/loadtest

# /search — 옵션 A(기준선)
k6 run --stage 30s:10,2m:30,30s:0 -e BASE_URL=http://localhost:8080 search-load-test.js

# /search — 옵션 C(스트레스, 20→100 VU ramp)
k6 run --stage 1m:20,1m:40,1m:60,1m:80,2m:100,2m:100,1m:0 \
  -e BASE_URL=http://localhost:8080 search-load-test.js

# /answers — 50건 캡(비용 약 1~2원/건, 총 50~100원대)
k6 run -e BASE_URL=http://localhost:8080 answers-load-test.js
```

사전 조건: `docker compose up -d`(Postgres/ES healthy) → `cd backend && ./gradlew bootRun`.

## 남은 이슈 (이번에 고치지 않음, `docs/progress.md` "다음 세션 논의거리" 참고)

- `OpenAiChatCompletionClient`에 재시도/백오프가 없어 OpenAI 429가 그대로 사용자에게
  전파된다 — Retry/Circuit breaker 도입 후보.
- `GlobalExceptionHandler.handleRagApi()`가 원인(429 rate limit vs 실제 장애)과 무관하게
  항상 502로 뭉뚱그려서, 클라이언트가 상태 코드만으로 둘을 구분할 수 없다 — 에러 코드를
  세분화하는 개선 후보(예: 429는 429로 그대로 전달, 그 외만 502).

> **후속(2026-10-12): 위 두 이슈 모두 해결됨.** `OpenAiChatCompletionClient`에 429 전용
> 지수 백오프 재시도(2회, 500ms 시작)를 넣었고, 재시도 소진 시 502가 아니라
> `429 / LLM_RATE_LIMITED`로 응답하도록 분리했다. 같이 connect 5초 / read 30초 타임아웃도
> 넣었다(read 30초의 근거: `backend/loadtest/results/answers-summary.json`의 `/answers`
> p95 3.49초, 최대 3.87초). 설계 근거와 의도적으로 하지 않은 것은
> `docs/progress.md` "다음 세션 논의거리"의 해당 항목 참고.

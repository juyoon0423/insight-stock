# 진행 상황 (Progress)

마지막 업데이트: 2026-08-03

**상세 히스토리는 [docs/progress-log.md](./progress-log.md) 참고** — 각 단계를
왜/어떻게 했는지, 실측 검증 결과, 트러블슈팅 전부 그 문서에 있다. 이 문서는
새 세션을 빠르게 시작하기 위한 요약만 남긴다.

## 현재 상태 요약

- **데이터 규모**: `companies` 118,535건 / `disclosures` 4,485건 / `chunks`
  173,035건(전량 임베딩 완료, `text-embedding-3-small`) / `users` 4명 /
  `watchlist` 3건 / `notification` 4건.
- **완성된 기능**: DART 공시 수집 → 원문 파싱 → 청킹 → ES 색인 → 임베딩 →
  하이브리드 검색(BM25+벡터+RRF) → RAG 답변 생성 → Controller/API(백엔드
  MVP), 프론트엔드 1~2단계 화면(질문답변 / RRF 시각화), 3단계 인증(JWT)+
  WatchList(백엔드+프론트엔드), corpCode.xml 전체 회사 목록 확대, 검색 품질
  정식 측정(정형+서술형 두 벤치마크, 결론까지 완료), 4단계 알림 — 백엔드+
  프론트엔드 전체 완성(스키마 / 감지 서비스+스케줄러 / 조회·읽음 API /
  헤더 배지+드롭다운 목록, Playwright 실제 브라우저 검증까지 완료),
  **AWS 배포(EC2 + Nginx) + Vercel 프론트엔드 배포 — 실물 브라우저 검증까지
  완료**(EC2 t3.small에 docker-compose+jar 직접 실행, Nginx로 80→8080
  리버스 프록시, Vercel에 프론트 배포, HTTPS 프론트→HTTP 백엔드 간 Mixed
  Content 차단을 발견해 Next.js rewrite 프록시로 해결, 회원가입→로그인→
  질문답변→관심회사 등록→알림 배지까지 실제 브라우저로 끝까지 확인),
  **무중단 배포(ShedLock + Blue-Green) — 수동 절차 기준 실물 검증까지
  완료**(4개 스케줄러에 ShedLock 적용, Nginx를 upstream 블록 구조로 전환,
  실제 8081→8082 전환을 curl 무중단 로그+ShedLock 중복방지 로그로 증명 -
  상세는 [docs/deploy.md](./deploy.md) 13~17절, progress-log.md 10절),
  **GitHub Actions CI(테스트 자동화) — 실물 성공/실패 케이스 검증까지
  완료**(`main` push/PR 트리거, 로컬에서 검증된 `docker-compose.yml`을
  CI 러너 안에서 그대로 재사용해 Postgres+Elasticsearch 기동 후
  `./gradlew test` 실행, 기본 스코프에 필요한 5개 값만 GitHub Secrets로
  등록, 정상 실행(3m18s)/의도적 실패→failure 표시/원복 후 재확인(2m26s)
  전부 실제 Actions 실행으로 검증 - 상세는 [docs/ci.md](./ci.md),
  progress-log.md 11절). **CD(자동 배포)는 EC2를 평소 꺼두는 운영
  방침과 안 맞아 이번 범위에서 의도적으로 제외** — 계속 수동 배포 유지.
- **인스턴스 운영 방침**: 평소엔 EC2 인스턴스를 꺼둔다(비용 관리 — 유료 API
  최소화 원칙과 같은 맥락). **t3.small은 blue-green(두 인스턴스 동시 실행)을
  못 버틴다는 게 2026-07-31 세션에서 실측으로 확정됐다** - 8082를 띄우는
  순간 SSH 배너 교환 단계부터 응답 불가(메모리 압박 추정, `docs/deploy.md`
  17절)가 발생해 `t3.medium`으로 강제 리사이즈해야 했다. 11월 시연 때
  t3.medium으로 키운다는 기존 계획이 추측이 아니라 사고로 뒷받침됨.
  검증 종료 후 t3.small로 되돌리고 정지했다(2026-07-31 세션 종료 시점
  기준 - 평소 단일 인스턴스 운영에는 t3.small로 충분하므로 비용 절감
  우선. 다음에 blue-green을 또 검증하려면 `modify-instance-attribute`로
  다시 t3.medium으로 올려야 한다, 17절 참고).
  **검증/작업이 끝나면 인스턴스를 켜둔 채로 세션을 끝내지 말고, 항상
  사용자에게 "계속 켜둘까/끌까" 먼저 확인할 것** - 이미 세 세션 연속으로
  지켜온 규칙이라 다음 세션에서도 잊지 않아야 한다.

## 다음 세션 시작점

**4단계(알림) → AWS 배포(EC2+Nginx+Vercel) → 무중단 배포(ShedLock+
Blue-Green, 수동 절차 검증 완료) → CI(GitHub Actions 테스트 자동화)까지
순서대로 끝났다.** 다음 우선순위는 **부하테스트(k6 또는 Gatling)** —
하이브리드 검색 API(`/api/v1/search` 또는 `/api/v1/answers`)에 동시 부하를
걸어 실제 처리량(throughput)/레이턴시 수치를 확보하는 게 목표다. 지금까지
HNSW 인덱스 속도(59배)나 무중단 curl 응답시간처럼 "실측 후 결론"을 내온
것과 같은 결로, 부하 상황에서의 실제 수치 없이 성능을 짐작하지 않기
위함이다. **관측성(모니터링/로깅)**과 **무중단 배포 자동화**(15절 수동
절차를 스크립트로 묶기)는 여전히 미착수 상태로 남아있는 후보이고, 이번에
부하테스트를 먼저 하기로 정했다.

- 알림 기능(백엔드+프론트엔드)은 완전히 끝났고 다시 조사할 필요 없음:
  `notification/`(`NotificationDetectionService`+`Scheduler`,
  `NotificationService`), `api/NotificationController`(`GET
  /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH
  /api/v1/notifications/{id}/read`), 프론트 `components/NotificationBell.tsx`
  (헤더 배지+드롭다운, `AuthStatusBar` 옆에 배치) 전부 실 DB/실 서버/실
  브라우저(Playwright) 검증 완료. `notification.collector.enabled: false`
  (기본 비활성화, 다른 collector와 동일 관례) 유지 중.
- **AWS 배포(1차)도 완전히 끝났고 다시 조사할 필요 없음**: EC2
  `i-00bdd2e8b984f5d0f`(현재 t3.small, stopped — blue-green 검증 종료 후
  2026-07-31 세션에서 되돌려놓은 상태)에 docker-compose(Postgres+
  Elasticsearch)+jar 직접 실행, Nginx가 80→(현재 라이브 포트) 리버스 프록시
  (14절 upstream 구조로 전환됨, 8080/8081/8082 직접 접근은 보안 그룹에서
  막혀 있어 외부 비공개), Vercel `https://frontend-steel-omega-
  0zye89ut4w.vercel.app`에 프론트 배포, Next.js rewrite로 Vercel 서버가
  백엔드를 대신 호출하는 프록시(Mixed Content 우회) 적용. 상세 절차/근거는
  [docs/deploy.md](./deploy.md) 전체와 progress-log.md 8~9절 참고. **인스턴스는
  기본적으로 꺼져 있다** - 다시 켜서 검증하려면 `docs/deploy.md` 7절(재기동)
  → `BACKEND_ORIGIN`/Vercel 재배포(12절) 순서 그대로 따를 것. 재기동 시
  라이브 포트가 8080이 아닐 수 있으니(14절 주의사항) nginx 설정을 먼저
  확인할 것.
- **무중단 배포(ShedLock + Blue-Green)도 수동 절차 기준으로 완전히 끝났고
  다시 설계할 필요 없음**: `docs/deploy.md` 13~17절 + progress-log.md 10절에
  ShedLock 도입 근거, Nginx upstream 구조, 정확한 전환 명령어 순서, 실측
  검증 로그(무중단 curl 400/400, ShedLock 중복방지 로그)가 전부 있다.
  **자동화(15절 수동 절차를 스크립트 하나로 묶기)는 의도적으로 다음
  단계로 미뤘다** - 필요해지면 이걸 먼저 고려.
- **CI(GitHub Actions 테스트 자동화)도 완전히 끝났고 다시 설계할 필요
  없음**: `.github/workflows/ci.yml`이 `main` push/PR에서 로컬과 동일한
  `docker-compose.yml`로 Postgres+Elasticsearch를 띄우고 `./gradlew
  test`(기본 스코프, `@Tag("manual")` 제외)를 돌린다. 필요한 Secrets 5개
  (`POSTGRES_USER`/`POSTGRES_PASSWORD`/`DART_API_KEY`/`OPENAI_API_KEY`/
  `JWT_SECRET`)와 그 근거, 정상/실패/원복 세 가지 실물 Actions 실행
  결과가 [docs/ci.md](./ci.md)와 progress-log.md 11절에 전부 있다.
  **CD(자동 배포)는 EC2 상시 운영 방침이 정해지기 전까지 의도적으로
  범위 밖** - 아래 "다음 세션 논의거리" 참고.
- **관측성**: 아직 설계 자체를 시작 안 함. 로그
  수집(현재는 EC2 로컬 `app.log` 파일뿐), 헬스체크/알람, APM 등 범위를
  어디까지 잡을지부터 옵션을 비교해 제안할 것 — 부하테스트 다음 후보.
- 시작 순서(로컬 개발): `docker compose up -d`(postgres/elasticsearch
  healthy 확인) → `cd backend && ./gradlew bootRun` → `cd frontend && npm
  run dev`. `.env`에 `DART_API_KEY`/`POSTGRES_USER`/`POSTGRES_PASSWORD`/
  `OPENAI_API_KEY`/`JWT_SECRET` 전부 채워져 있어야 백엔드가 뜬다.

## 다음 세션 논의거리 (우선순위 미정)

- **`GlobalExceptionHandler`가 `/answers`의 모든 실패를 원인 불문하고 502로
  뭉뚱그림 — 별도 개선 후보(재시도/백오프와는 별개)** — 부하테스트(k6)
  중 실제로 발생한 502 3건은 서버 로그 대조로 전부 OpenAI 429(rate
  limit)임을 확인했지만, `handleRagApi()`(`api/GlobalExceptionHandler.java:55-63`)가
  원인과 무관하게 항상 502/`LLM_UNAVAILABLE`만 반환하므로 클라이언트는
  "OpenAI가 일시적으로 막은 것"과 "실제 서버 장애"를 상태 코드만으로
  구분할 방법이 없다. 개선 방향 예시: 429는 429로 그대로 전달하거나
  별도 errorCode(예: `LLM_RATE_LIMITED`)로 분리. 이번 부하테스트
  세션에서는 의도적으로 고치지 않고 발견만 기록(자세한 재현/로그는
  docs/loadtest.md 참고).
- **`OpenAiChatCompletionClient`에 재시도/백오프가 없음 — resilience 개선
  후보(Retry/Circuit breaker 등)** — 부하테스트(k6, `backend/loadtest/`)
  중 `/answers`에서 502(`LLM_UNAVAILABLE`) 3건이 실제로 발생했고, 서버
  로그로 원인을 추적한 결과 전부 OpenAI 쪽 TPM(분당 토큰) rate limit
  (`HttpClientErrorException$TooManyRequests: 429`, "Rate limit reached
  for gpt-4o-mini ... Limit 200000")이었다 — 우리 버그는 아니었음.
  `OpenAiEmbeddingClient`(배치 인덱싱용)와 달리 `OpenAiChatCompletionClient`는
  429를 받아도 즉시 `RagApiException`으로 던지고 끝(재시도 없음,
  `OpenAiChatCompletionClient.java:42-50`) - 게다가
  `GlobalExceptionHandler.handleRagApi()`가 원인과 무관하게 항상 502로
  뭉뚱그려서, 클라이언트 입장에서는 "OpenAI가 잠깐 막은 것"과 "실제
  장애"를 상태 코드만으로 구분할 수 없다(서버 로그 대조가 필수). 트래픽이
  더 늘면 이런 일시적 429가 사용자에게 그대로 실패로 노출된다 - 지수
  백오프 재시도(임베딩 클라이언트와 같은 패턴) 또는 Resilience4j 같은
  Circuit Breaker 도입을 다음 후보로 남긴다. 이번 세션에서는 의도적으로
  고치지 않고 발견만 기록.
- **CI 실행 시간 편차(3m18s vs 2m15s vs 2m26s) — Docker layer cache/Gradle
  cache 도입 여지, 지금은 문제 아니라 우선순위 낮음** — 매번
  `docker compose up -d --build`로 Elasticsearch 커스텀 이미지(Nori
  플러그인)를 처음부터 다시 빌드하기 때문에 실행마다 소요 시간이
  달라진다(docs/ci.md, progress-log.md 11절). 3분대로 충분히 빠르다고
  판단해 원인 규명이나 캐싱 도입은 지금 하지 않았음 - 실행 빈도가 늘거나
  더 느려지면 Docker layer cache(`actions/cache` 등) 도입을 고려.
- **CD(자동 배포)는 EC2 상시 운영 여부가 정해지면 재검토** — 이번 CI
  세션에서 CD를 의도적으로 범위 밖에 뒀다(EC2를 평소 꺼두는 운영 방침과
  "push마다 자동 배포"가 안 맞음). 만약 나중에 EC2를 상시 켜두는 쪽으로
  운영 방침이 바뀌거나 Elastic IP/도메인·TLS가 갖춰지면, 그때 CD 도입을
  다시 논의할 것.
- **청킹/임베딩/corpCode 동기화 스케줄러를 나중에 활성화할 때 `@SchedulerLock`
  추가를 빠뜨리지 말 것** — 무중단 배포 도입(2026-07-31)으로 공시 수집/원문
  수집/색인/알림 감지 4개 스케줄러에는 ShedLock을 적용했지만, 현재
  비활성화(`enabled: false`) 상태인 `ChunkingScheduler`/`EmbeddingScheduler`/
  `CorpCodeSyncScheduler`는 이번 범위 밖이라 그대로 뒀다. 무중단 배포 중
  인스턴스가 잠깐 2개 뜨는 구간의 중복 실행 위험은 활성화되는 모든
  스케줄러에 동일하게 적용되므로, 이 셋 중 하나라도 나중에 `enabled: true`로
  켜기 전에 `@SchedulerLock`부터 추가해야 한다.
- **notification 리스트 페이지네이션 미검토** — `GET /notifications`가
  현재 전체를 한 번에 반환한다. 알림이 많아지면(watchlist 회사 수 × 공시
  빈도) 필요할 수 있음, 아직 실측 안 함.
- **notification unread-count 인덱스(`idx_notification_user_unread`) 실측
  미검증** — `(user_id, is_read, created_at DESC)` 복합 인덱스를 스키마
  단계에서 만들어뒀지만, 실제 조회 쿼리(`findByUserIdWithDisclosureAndCompany`,
  WHERE user_id만 / is_read 미지정)가 이 인덱스를 실제로 타는지 `EXPLAIN
  ANALYZE`로 확인 안 함 - pg_trgm 사례(만든 인덱스가 실제 쿼리와 안 맞을
  수 있음)를 감안하면 데이터가 늘기 전에 한 번 확인할 가치 있음.
- **`notification_id_seq` 갭 특성 — 문서화만, 조치 불필요** — Postgres
  시퀀스는 ROLLBACK/`ON CONFLICT` 충돌과 무관하게 항상 전진한다(실측 확인,
  로그 참고). `id`는 내부 PK라 기능적 영향 없음 - 논의거리라기보다 "다음에
  비슷한 걸 보면 당황하지 않기 위한" 기록.
- **감사보고서/연결감사보고서 ZIP 내부 파일명 불일치 미해결** — `FAILED`
  37건(데이터 확대 후), `DocumentCollectorService.extractXmlFromZip`에서
  발생. 배치 전체를 막지는 않아 블로커 아님, 원인 파악에는 그 유형의
  document API 응답을 직접 열어보는 별도 조사가 필요.
- **`SLIDING_WINDOW`(표 나열형) 잔여 21개 청크** — 정제로도 복구 안 되는
  극소수 사례, 표 압축 별도 구현은 보류하기로 결정했음.
- **재무 수치가 조밀한 서술형 청크는 BM25/벡터 둘 다 놓칠 수 있음** —
  청킹은 멀쩡한데 임베딩/질의가 수치 조밀 텍스트를 잘 못 담는 현상. 향후
  검토 옵션(수치 정규화 / 청크 요약문 / 질의 확장) 미결정.
- **HNSW recall(정확도) 미측정** — "인덱스가 쓰이는지"/"얼마나 빠른지"는
  59배 차이까지 실측했지만, 근사(approximate) 인덱스라 Seq Scan 대비
  결과가 얼마나 겹치는지(recall)는 아직 측정 안 함.
- **top-K 중복 콘텐츠 비율 173,035건 기준 재측정 안 함** — 5,201건 기준
  19.3%라는 수치만 있음, 데이터가 27배 확대된 뒤로는 재측정 안 함.
- **매치 패턴 배지 임계값(강함 5위 / 약함 20위) 재검토 여지** — 8개 질의
  소규모 조사로 정한 값, 이후 검색 품질 측정 12개 결과로 재검토 가능.
- **report_nm 유형 다양성별 품질 분포 미측정** — 검색 품질 측정 12개가
  report_nm 10종만 다뤄 전체 유형을 커버하지 못함.
- **검색 라우팅(문서 유형별 검색 전략 분기) — 검토 후 미구현으로 결정,
  재논의 트리거만 존재** — 표본 부족 / 분기 기준 불명확 / 질의 시점에 답
  문서 유형을 모르는 근본 난제 때문에 지금은 구현 안 하기로 함. 더 큰
  표본으로 재확인되거나 `/answers` 실사용에서 품질 문제가 관찰되면 재논의.
- **관측성** — 아직 착수 안 함(무중단 배포는 2026-07-31 세션에서 완료).
  위 "다음 세션 시작점" 참고.
- **백엔드 jar가 EC2 재부팅 시 자동 기동 안 됨** — `nohup java -jar
  app.jar ...`로 직접 띄우는 방식이라, 인스턴스를 재시작(reboot)하거나
  중지 후 재기동할 때마다 SSH로 접속해 수동으로 다시 실행해야 한다(실제로
  세 세션 모두 재기동할 때마다 반복함, progress-log.md 9절/10절). systemd
  서비스로 등록하거나 Docker 이미지화하면 해결됨 - 우선순위는 관측성 이후.
- **Elastic IP 미사용 — 켤 때마다 IP가 바뀌어 Vercel도 매번 재배포해야
  함** — 인스턴스를 stop→start할 때마다 퍼블릭 IP가 새로 할당돼(현재
  `43.201.85.100` → `3.36.124.56`으로 이미 한 번 바뀜), Vercel의
  `BACKEND_ORIGIN` 환경변수를 갱신하고 `vercel --prod`로 재배포해야만
  프론트-백엔드 연동이 유지된다(docs/deploy.md 12절). Elastic IP를 붙이면
  이 갱신 자체가 사라지지만 월 비용이 붙는다(인스턴스가 실행 중이 아닐 때
  Elastic IP는 과금됨) - 평소 꺼두는 운영 방침과 상충하는 면이 있어
  비용 대비 효과를 판단해야 함, 아직 결론 안 남.
- **도메인/TLS 미적용 — 시연 직전 진행 예정** — 현재 백엔드는 HTTP(IP
  기반)만 서비스한다. 도메인을 구매하고 Let's Encrypt로 TLS를 붙이는
  작업은 의도적으로 미뤄뒀다(지금 사도 시연 전까지 미리 켜둘 필요는 없고,
  프론트는 아래 Vercel 프록시로 이미 우회돼 있어 급하지 않음).
- **Vercel rewrite 프록시는 임시 우회 — TLS 적용 시 제거 예정** — Vercel
  프론트(HTTPS)가 EC2 백엔드(HTTP)를 직접 호출하면 브라우저 Mixed Content
  정책에 무조건 차단당한다는 걸 실물 브라우저 검증에서 발견해서(위 "완성된
  기능" 참고, 상세는 progress-log.md 9절), `frontend/next.config.ts`의
  `rewrites()`로 Vercel 서버가 대신 백엔드를 호출하도록 우회해뒀다. 위
  도메인/TLS 항목이 처리되면 이 rewrite를 제거하고
  `NEXT_PUBLIC_API_BASE_URL`을 백엔드 도메인으로 직접 가리키는 원래
  방식으로 되돌리면 된다.
- **`WebConfig` CORS 허용 메서드 수동 관리 구조 — 같은 종류의 버그가 두 번
  재발함** — 3단계 WatchList 때 `DELETE` 누락, 이번 4단계 알림 때 `PATCH`
  누락, 둘 다 새 HTTP 메서드를 쓰는 API를 추가할 때 `setAllowedMethods(...)`
  갱신을 깜빡해서 생겼고 curl로는 안 잡히고 브라우저로만 드러났다(상세는
  progress-log.md 3절/7절). 근본 해결(예: 컨트롤러에서 실제 사용 중인 HTTP
  메서드를 스캔해 CORS 설정에 자동 반영하는 구조, 혹은 최소한 "새 메서드
  추가 시 CORS 갱신" 체크리스트화)을 고려해볼 만하지만, 우선순위는 낮음 -
  지금까지 두 번 다 발견 즉시 한 줄 수정으로 해결됐고 피해도 없었으니
  재발할 때마다 대응해도 무방하다는 게 현재 판단.
- **로컬 개발 환경 메모 — IntelliJ Run Configuration은
  `SPRINGDOTENV_DIRECTORY`를 직접 챙겨야 함** — `backend/build.gradle`의
  `test`/`manualTest`/`bootRun` 태스크는 전부 `environment
  "SPRINGDOTENV_DIRECTORY", ".."`가 박혀 있어(`.env`가 `backend/`가 아니라
  프로젝트 루트에 있어서) Gradle로 실행하면 자동으로 `.env`를 찾지만,
  IntelliJ에서 `InsightStockApplication`을 "Application" Run Configuration
  으로 직접 실행하면(Gradle을 안 거치므로) 이 설정이 안 먹어 `.env`를 못
  찾는다 - Run Configuration의 Environment variables에
  `SPRINGDOTENV_DIRECTORY=..`를 수동으로 추가해야 한다. 새 개발 환경/팀원
  온보딩 시 참고(할 일이라기보다 참고용 메모).

## 개발 원칙 (요약, 상세는 CLAUDE.md 참고)

- 한 번에 한 걸음씩 — 요청하지 않은 범위를 미리 확장하지 않는다.
- 상상으로 설계하지 않는다 — 실제 데이터/동작을 먼저 확인하고 그것을 근거로
  만든다.
- 큰 기술 선택은 여러 옵션을 비교하고 근거와 함께 선택, 중요한 결정/발견은
  문서로 남긴다.
- 유료 외부 API를 실제로 호출하는 테스트는 `@Tag("manual")`로 분리 -
  `./gradlew test`(기본)는 항상 비용 0, 수동 검증은 `./gradlew manualTest`.
- 커밋은 의미 단위로 분리하고 Conventional Commits를 따른다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| 언어/런타임 | Java 17 |
| 백엔드 프레임워크 | Spring Boot 3.5.16 (Web, Data JPA, Validation, Security, Actuator) |
| 빌드 도구 | Gradle 8.11.1 (wrapper 포함) |
| DB | PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16`) |
| 검색엔진 | Elasticsearch 8.15.3 + Nori(한글 형태소 분석기) |
| 마이그레이션 | Flyway (V1~V7) |
| ORM | Hibernate 6.6.x (JPA), pgvector 매핑용 커스텀 UserType |
| 프론트엔드 | Next.js 16(App Router, TypeScript, Tailwind CSS) |
| 인증 | Spring Security + 자체 발급/검증 JWT(HS256, access 30분 / refresh 14일 Stateless) |
| 환경변수 | `.env`(spring-dotenv로 로드, 커밋 제외) |

## 실행 방법

```bash
# 1. 인프라 (Postgres + Elasticsearch)
docker compose up -d
docker compose ps   # 둘 다 healthy인지 확인

# 2. 백엔드 (JDK 17 필요)
cd backend && ./gradlew bootRun
curl http://localhost:8080/actuator/health   # {"status":"UP"}

# 3. 프론트엔드
cd frontend && npm run dev   # http://localhost:3000
```

자세한 확인 명령(psql, Elasticsearch 플러그인 확인 등)은 루트
[README.md](../README.md) 참고. API 명세/폴더 구조/에러 코드 표 등 상세
레퍼런스는 [docs/progress-log.md](./progress-log.md) 2절 "현재 프로젝트
상태 요약"에 그대로 보존돼 있다.

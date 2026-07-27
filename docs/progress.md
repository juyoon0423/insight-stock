# 진행 상황 (Progress)

마지막 업데이트: 2026-07-27

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
  정식 측정(정형+서술형 두 벤치마크, 결론까지 완료), **4단계 알림 — 백엔드+
  프론트엔드 전체 완성**(스키마 / 감지 서비스+스케줄러 / 조회·읽음 API /
  헤더 배지+드롭다운 목록, Playwright 실제 브라우저 검증까지 완료).

## 다음 세션 시작점

**4단계(알림) 전체(백엔드+프론트엔드) 완료.** progress-log.md의 원래 추천
순서("데이터 규모 확대 → 검색 품질 측정 → 4단계 알림 → AWS 배포")를 보면
앞 세 개가 전부 끝났으니, **다음 우선순위는 AWS 배포 + 무중단 배포**로
판단된다 - 기능이 갖춰진 뒤 마지막에 배포로 마무리한다는 원래 계획 그대로.
다만 이건 이 세션의 판단일 뿐이니 시작 시 사용자와 다시 확인할 것.

- 알림 기능(백엔드+프론트엔드)은 완전히 끝났고 다시 조사할 필요 없음:
  `notification/`(`NotificationDetectionService`+`Scheduler`,
  `NotificationService`), `api/NotificationController`(`GET
  /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH
  /api/v1/notifications/{id}/read`), 프론트 `components/NotificationBell.tsx`
  (헤더 배지+드롭다운, `AuthStatusBar` 옆에 배치) 전부 실 DB/실 서버/실
  브라우저(Playwright) 검증 완료. `notification.collector.enabled: false`
  (기본 비활성화, 다른 collector와 동일 관례) 유지 중.
- **AWS 배포**를 다음으로 진행한다면: 인프라(RDS/Elasticsearch 서비스 또는
  자체 호스팅, 백엔드 컨테이너 배포), 프론트(Vercel 또는 별도 정적/서버
  호스팅), 환경변수/CORS(`cors.allowed-origins`)를 배포 도메인 기준으로
  교체, `WatchList` 백엔드 설계 논의에서 이미 짚었던 "프론트/백엔드가 다른
  도메인일 때 쿠키/CORS 함의"(위 progress-log.md 1절 토큰 저장 방식 논의
  참고)까지 고려 - 설계안부터 먼저 제안하고 사용자 확인 후 진행할 것(이
  프로젝트의 기존 작업 방식 그대로).
- 시작 순서: `docker compose up -d`(postgres/elasticsearch healthy 확인)
  → `cd backend && ./gradlew bootRun` → `cd frontend && npm run dev`.
  `.env`에 `DART_API_KEY`/`POSTGRES_USER`/`POSTGRES_PASSWORD`/
  `OPENAI_API_KEY`/`JWT_SECRET` 전부 채워져 있어야 백엔드가 뜬다.

## 다음 세션 논의거리 (우선순위 미정)

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
- **AWS 배포 + 무중단 배포** — 아직 착수 안 함. 위 "다음 세션 시작점" 참고,
  이 세션 판단으로는 다음 우선순위.
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
| 마이그레이션 | Flyway (V1~V6) |
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

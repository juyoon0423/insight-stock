# CLAUDE.md

이 파일은 Claude Code가 이 저장소에서 작업할 때 따라야 할 지침입니다.

## 프로젝트 개요

**InsightStock** — 하이브리드 검색과 RAG 기반의 AI 글로벌 공시·뉴스 통합 분석 플랫폼.

금융 도메인에서 LLM의 환각(Hallucination)을 제어하기 위해, DART 공시라는 명확한

사실(Fact)에 근거해 답변을 생성하는 것이 핵심 목표.

- **1차 목표(MVP)**: 국내 DART 공시 하나에 집중. SEC/뉴스 RSS/알림/인증은 확장 포인트로 설계만.

- **핵심 가치**: 하이브리드 검색(ElasticSearch BM25 + pgvector 의미검색) → RRF 융합 → 출처 명시 RAG 답변.

- 이 프로젝트는 취업 포트폴리오이기도 함. 각 기술 선택에는 **이유가 명확해야** 하고, 그 근거는 문서로 남긴다.

## 작업 시작 시 먼저 읽을 문서

새 세션에서 작업을 시작할 때는 아래 문서를 먼저 참고한다.

- `docs/progress.md` — 현재까지 진행 상황과 다음 할 일

- `docs/erd.md` — 데이터베이스 설계와 그 근거

- `docs/dart-findings.md` — DART API 탐색에서 발견한 함정들 (아래 요약 참고)

## 기술 스택 (확정)

- **백엔드**: Java 17, Spring Boot 3.x, Gradle (`backend/` 폴더)

- **프론트엔드**: Next.js (`frontend/` 폴더, 추후 추가) — 모노레포 구조

- **DB**: PostgreSQL 16 + pgvector — 정형 데이터 + 임베딩 벡터

- **검색엔진**: ElasticSearch 8.x + Nori(한글 형태소)

- **마이그레이션**: Flyway

- **인프라**: Docker Compose (로컬 개발)

- **임베딩 모델**: OpenAI `text-embedding-3-small` (1536차원) — 변경 시 벡터 전체 재생성 필요

## 아키텍처 핵심 결정 (바꾸려면 반드시 사용자와 상의)

- **청크를 ElasticSearch와 pgvector 양쪽에 저장**한다. RRF 순위 융합을 위해 두 검색의 단위가 청크로 일치해야 함.

- **스키마는 Flyway가 관리**한다. JPA는 `ddl-auto: validate`로 검증만 하고 스키마를 건드리지 않는다.

- **벡터 인덱스는 HNSW** 사용. 빈 테이블에서 시작해 배치로 채워지는 구조라 IVFFlat보다 적합.

- **엔티티 연관관계**: 회사↔공시는 단방향(대량 컬렉션 로딩 방지), 공시↔청크는 양방향+cascade(재청킹 흐름 지원).

- **Enum vs String**: 내부에서 통제하는 값(document_status, chunk_type)은 Enum, 외부(DART)가 정의하는 값(corp_cls)은 String.

## DART API 주의사항 (실제 탐색으로 확인됨)

- 일부 공시는 원문이 없고 `status=014` 에러를 반환한다 → `has_document`로 관리, 수집 시 스킵 처리.

- DART의 "XML"은 표준을 지키지 않는 경우가 있다(이스케이프 안 된 문자 등) → 엄격한 파서 대신 관대한 파서(lxml recover) 또는 정규식 fallback 사용.

- 문서 크기 편차가 매우 크다(약 1,500자 ~ 12만 자) → 청킹 전략을 `report_nm`(문서 유형)별로 분기.

- 공시 원문은 ZIP으로 오고 그 안에 `{rcept_no}.xml`이 들어있다.

## 개발 환경 실행

```bash

# 인프라 기동 (프로젝트 루트에서)

docker compose up -d

# 백엔드 실행

cd backend && ./gradlew bootRun

```

- 컨테이너 종료는 `docker compose down` (데이터는 볼륨에 보존됨).

- **주의: `docker compose down -v`는 볼륨까지 삭제하므로 사용 금지** (데이터 소실).

## 보안 / 안전 규칙 (엄수)

- **`.env`는 절대 커밋하지 않는다.** API 키(DART, OpenAI)와 DB 비밀번호가 들어있다. 커밋 전 `git check-ignore`로 확인.

- 새 비밀 정보가 생기면 `.env`에 넣고 `.env.example`에는 키 이름만 추가한다.

- **전역 시스템 변경(`brew install/uninstall`, `sudo`, 전역 npm 등)은 실행 전 반드시 사용자에게 확인**받는다. 과거 `brew`가 다른 도구를 삭제한 사고가 있었음. 가능하면 프로젝트 로컬 도구(예: gradlew)를 사용한다.

- **유료 외부 API(OpenAI 등)를 실제로 호출하는 테스트는 기본 `test`에서 제외한다.** `@Tag("manual")`로 표시하고 `./gradlew test`에서는 `excludeTags`로 걸러지도록 한다(`backend/build.gradle` 참고) — 수동 검증 전용 태스크(`./gradlew manualTest`)로만 실행한다. `./gradlew test`(기본 태스크)는 항상 비용 0이어야 한다 — 나중에 CI/CD가 붙으면 커밋마다 자동 실행되므로, 여기서 새는 유료 호출은 사고가 아니라 반복 청구가 된다. 새 테스트를 만들 때 실제 LLM/임베딩 API를 호출한다면 반드시 이 패턴을 따를 것(2026-07-23, `AnswerServiceManualIT`/`HybridSearchManualIT`가 태그 없이 기본 `test`에 섞여 있다가 전체 테스트 실행 중 의도치 않게 과금된 사고로 발견).

## 작업 방식

- **한 번에 한 걸음**씩 진행한다. 요청하지 않은 범위(엔티티만 요청했는데 Service까지 등)를 미리 만들지 않는다.

- 큰 기술 선택은 **여러 옵션을 비교하고 근거와 함께 선택**한다. 중요한 결정과 발견은 `docs/`에 문서로 남긴다.

- 커밋은 **의미 단위로 분리**하고 Conventional Commits(feat/fix/docs/chore/build 등)를 따른다.

- 상상으로 설계하지 않는다. **실제 데이터/동작을 먼저 확인**하고 그것을 근거로 만든다.

## 응답 및 코드 스타일

- 항상 **한국어**로 답변한다.

- 코드 주석은 한국어로 작성하되, 변수명·함수명은 영어를 사용한다.

- 설명은 초보자도 이해할 수 있도록 자세하게 작성한다.

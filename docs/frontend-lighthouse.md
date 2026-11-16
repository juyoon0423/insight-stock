# 프론트엔드 Lighthouse 측정 기록

프론트 화면 리팩토링(브랜치 `refactor/frontend-ui`) 전후의 성능, 접근성 수치를 비교하기 위한 기록이다.
지금까지 프론트 성능 수치는 기록된 적이 없어서, 리팩토링 전 측정값이 이 프로젝트의 첫 기준선이 된다.
**전후 비교는 아래 조건을 똑같이 맞췄을 때만 의미가 있다.** 조건을 바꿔야 하면 이 문서에 먼저 적고 전후를 둘 다 다시 잰다.

## 1. 측정 조건 (2026-11-16 확정, 리팩토링 전후 동일 적용)

### 빌드와 서버
- **프로덕션 빌드**로 잰다. 개발 서버(`next dev`)는 번들을 압축하지 않고 HMR 코드가 섞여 있어서 수치가 실제 배포와 다르다.
  ```bash
  cd frontend
  NEXT_PUBLIC_USE_MOCK_ANSWERS=false npm run build
  npm run start   # http://localhost:3000
  ```
- `NEXT_PUBLIC_USE_MOCK_ANSWERS=false`를 빌드 명령에 직접 지정한다. 로컬 `.env.local`은 화면 작업용으로 `true`인데, 이 값이 켜지면 첫 화면에 "목업 모드" 배지가 생겨 실제 배포 화면과 달라진다. Next.js는 셸에서 넘긴 환경변수를 `.env.local`보다 우선한다.
- `NEXT_PUBLIC_API_BASE_URL`은 `.env.local`의 `http://localhost:8080`(로컬 백엔드)을 그대로 쓴다.

### 측정 도구
- **Lighthouse CLI 13.6.0**(`npx lighthouse@13.6.0`, 전역 설치 없이 실행) + **Chrome 157**(headless).
- **시크릿 창과 같은 조건**: Lighthouse CLI는 측정할 때마다 임시 프로필로 새 Chrome을 띄운다. 확장 프로그램, 캐시, 쿠키, localStorage가 모두 빈 상태라 시크릿 창에서 재는 것과 효과가 같다. 일반 Chrome 창의 DevTools에서 재면 확장 프로그램이 점수를 깎을 수 있어서 쓰지 않는다.
  - localStorage가 비어 있으니 refresh token도 없다. `bootstrapSession`은 토큰이 없으면 네트워크 요청 없이 바로 비로그인 상태로 정리하므로, 랜딩과 로그인 화면은 **백엔드가 없어도 실제와 같은 화면**으로 측정된다(`lib/api.ts`의 `bootstrapSession` 확인).
- **모바일**: Lighthouse 기본값(Moto G Power 화면 에뮬레이션, 느린 4G와 CPU 4배 감속을 시뮬레이션).
- **데스크톱**: `--preset=desktop`(1350×940, 감속 약하게).
- 카테고리: Performance, Accessibility, Best Practices, SEO.
- **페이지마다, 모바일·데스크톱 각각 3회씩 재서 중앙값**을 기록한다. Lighthouse 점수는 실행할 때마다 몇 점씩 흔들리기 때문이다(Lighthouse 공식 문서도 여러 번 잰 중앙값을 권장).

### 측정 명령
```bash
npx lighthouse@13.6.0 http://localhost:3000/ \
  --only-categories=performance,accessibility,best-practices,seo \
  --chrome-flags="--headless=new" \
  --output=json --output=html --output-path=<저장경로>/<페이지>-mobile-1
# 데스크톱은 같은 명령에 --preset=desktop 추가
```

### 측정 환경
- 기기: Apple M3, 메모리 16GB, macOS
- Node 26.5.0, Next.js 16.2.11, React 19.2.4
- 측정 중에는 빌드, 테스트 등 무거운 작업을 동시에 돌리지 않는다.

### 측정 페이지
| 페이지 | URL | 백엔드 필요 | 비고 |
|---|---|---|---|
| 랜딩(질문 화면 첫 상태) | http://localhost:3000/ | 아니오 | 질문하기 전 첫 화면 |
| 로그인 | http://localhost:3000/login | 아니오 | |
| 하이브리드 검색 | http://localhost:3000/search | 첫 화면은 아니오 | 결과 화면은 측정하지 않음 (아래 참고) |

- 질문 결과 화면, 관심 기업(`/watchlist`, 로그인 필요)은 Lighthouse가 로그인 상태나 질문 입력을 재현하지 못한다. 그래서 점수 대신 **캡처와 브라우저 확인**으로 비교한다.

### 원본 리포트 보관
- 원본 JSON, HTML 리포트는 저장소에 넣지 않는다(파일 하나에 수백 KB). 로컬 `lighthouse-reports/`(gitignore 대상)에 둔다.
- 이 문서에는 중앙값만 옮겨 적는다.

### 캡처 조건 (README용 전후 비교 캡처)
- `npx playwright@1.65.1 screenshot --channel=chrome`로 설치된 Chrome을 쓴다(Playwright 브라우저를 따로 내려받지 않음). 매번 새 프로필이라 로그인 기록이 없다.
- 데스크톱 `--viewport-size=1440,900`, 모바일 `--device="Pixel 7"`(412×915, 배율 2.625). 페이지가 그려지도록 `--wait-for-timeout=1500`을 준다.
  - headless Chrome의 `--window-size=390,...`는 창 너비 최소값 때문에 모바일 레이아웃이 잘려서 쓰지 않았다.
- 로그인 화면이 필요한 캡처에는 **테스트 계정(`@example.com`)만** 쓴다.
- 저장 위치: `docs/images/frontend/before/`, `docs/images/frontend/after/`
- **저장소에는 README와 이 문서들에서 파일 이름으로 가리키는 캡처만 둔다**(PNG 11장, GIF 1개). 나머지 34장(정적 화면, q2~q5 답변, 모바일 등)은 용량을 줄이려고 저장소 밖 로컬 백업 폴더로 옮겼다. PNG는 무손실 재압축(deflate 9, 적응형 필터)도 시도했지만 Chrome이 저장한 원본보다 커져서 원본을 그대로 뒀다(픽셀 동일 확인).

## 2. 리팩토링 전 (기준선, 2026-11-16 측정)

조건: 위 1절 그대로. 백엔드는 띄우지 않았다(랜딩, 로그인, 검색 첫 화면은 백엔드 호출이 없음). 3회 측정 중앙값.

| 페이지 | 기기 | Performance | Accessibility | Best Practices | SEO | FCP | LCP | TBT | CLS |
|---|---|---|---|---|---|---|---|---|---|
| `/` | 모바일 | 100 | 100 | 100 | 100 | 0.76초 | 0.76초 | 5ms | 0 |
| `/` | 데스크톱 | 100 | 100 | 100 | 100 | 0.21초 | 0.21초 | 0ms | 0 |
| `/login` | 모바일 | 100 | 96 | 100 | 100 | 0.75초 | 0.75초 | 4ms | 0 |
| `/login` | 데스크톱 | 100 | 96 | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 |
| `/search` | 모바일 | 100 | 95 | 100 | 100 | 0.75초 | 0.75초 | 5ms | 0 |
| `/search` | 데스크톱 | 100 | 95 | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 |

Performance 점수 3회 값은 모든 페이지·기기에서 100/100/100으로 흔들림이 없었다.

전송량(Lighthouse `total-byte-weight` 중앙값): `/` 258KB, `/login` 257KB, `/search` 257KB. 이 중 52KB는 쓰이지 않는 Geist 글꼴 2개(23KB, 29KB)다.

**감점 원인 (Accessibility)**: 모두 `color-contrast` 한 항목이다.
- `/login`, `/search`의 "메인으로 돌아가기" 링크: `text-zinc-400`(#9f9fa9)이 배경 #fafafa 위에서 대비 2.51:1. 기준 4.5:1에 못 미친다.
- `/search`의 빈 상태 안내 문구(`text-zinc-400`): 같은 이유로 2.51:1.

**해석**: 정적 페이지라 성능은 이미 상한이다. 리팩토링의 목표는 점수를 올리는 것이 아니라 **글꼴, 색 등을 바꾸면서 성능을 떨어뜨리지 않는 것**(특히 웹폰트를 넣으면 LCP, CLS가 나빠질 수 있다)과 **대비 문제를 고쳐 Accessibility를 100으로 만드는 것**이다.

Lighthouse가 잡지 못한 점도 함께 적어 둔다. `<html lang="en">`인데 화면은 한국어다. 화면 읽기 프로그램이 한국어를 영어 발음으로 읽을 수 있으므로 리팩토링 때 같이 고친다.

### 질문 화면 캡처 (리팩토링 전, 2026-11-16)
- 조건: 로컬 백엔드를 `--scheduling.enabled=false`로 띄웠다(수집 배치 전부 꺼짐). Elasticsearch, 실제 OpenAI 호출을 썼다. 캡처 후 백엔드와 Elasticsearch를 껐고, 공시 건수가 6,188건 그대로인 것을 확인했다.
- 방법: 시연 질문 4개를 예시 버튼으로 하나씩 물었다. 데스크톱(1440×900, 전체 페이지)은 실제 `/answers`를 호출했다. 모바일(Pixel 7)은 같은 응답을 Playwright로 가로채 재생했다. 그래서 유료 호출은 질문당 1회, 모두 4회다. 원본 응답은 `lighthouse-reports/answers-before/`(저장소 밖)에 있다.
- 저장소에 남긴 파일: `docs/images/frontend/before/answer-q1-desktop.png`(빈 상태), `answer-q6-desktop.png`(출처 3건). 나머지는 로컬 백업(1절 "캡처 조건" 참고).

**아래 결과는 모두 로컬 데이터 기준이다.** 운영 서버에서는 결과가 다르다(q1, q3은 운영에서 답이 나온다).

| 질문 | 구분 | 로컬 결과 |
|---|---|---|
| q1 현대로템 대만 공급계약 | 예시 칩 | 찾지 못함(출처 0건). **빈 상태 화면 비교용**으로 남긴다 |
| q2 신한지주 주식 소각 | 예시 칩 | 답변 + 출처 1건. 본문의 `[4]`가 출처 배지 "1"로 표시됨 |
| q3 한화에어로스페이스 KF-21 정정 | 예시 칩 | 찾지 못함(출처 0건). **빈 상태 화면 비교용**으로 남긴다 |
| q4 삼성전자 주가 예측 | 예시 칩 | 고정 문구로 거절(의도대로) |
| q5 "코아스가 정정공시한 전환사채의 전환가액과 정정 후 만기일은?" | 캡처 전용 | 답변 + 출처 2건(본문 `[6]`, `[8]` → 배지 1, 2) |
| q6 "하이드로리튬이 작년보다 돈을 얼마나 벌었는지, 사업부문별로 어떻게 다른가?" | 캡처 전용 | 답변 + 출처 3건(배지 1, 2, 3) |

- q5, q6은 **출처가 여러 건 나오는 화면**을 남기려고 캡처할 때만 입력창에 직접 쳐서 물었다. 예시 칩 4개는 바꾸지 않았다. 두 질문은 7월 공시로 답하는 질문이고, `docs/answer-quality-eval-raw-2026-10-13-topk10.md`의 Q9, N4와 같은 문장이다(당시 인용 2건, 3건). 유료 호출은 질문당 1회, 모두 2회다.

- **q1, q3이 로컬에서 답하지 못한 이유**: 두 질문은 10월 접수 공시로 답하는 질문이다(`docs/demo-runbook.md`). 그런데 로컬 DB의 10월 공시 534건은 모두 `PENDING`(원문 미수집) 상태라 검색 대상에 없다. 운영 서버와 로컬의 데이터 차이이고, 화면 문제는 아니다.
- **범위 밖 번호**: 6개 응답 모두 본문 `[N]`이 전부 `citedNumbers` 안에 있었다. 범위 밖 번호가 일반 텍스트로 남는 장면은 이번 캡처에 없다.
- 리팩토링 후에도 같은 6개 질문을 같은 방법으로 다시 묻는다. LLM 답변은 실행할 때마다 문장이 조금 달라질 수 있다.

## 3. 리팩토링 후 (2026-11-16 측정)

조건: 1절 그대로(프로덕션 빌드, `NEXT_PUBLIC_USE_MOCK_ANSWERS=false`, Lighthouse 13.6.0, 3회 중앙값). 백엔드는 띄우지 않았다.

### 3-1. 최종 결과 (OS 한글 글꼴 스택)

| 페이지 | 기기 | Performance | Accessibility | Best Practices | SEO | FCP | LCP | TBT | CLS | 전송량 |
|---|---|---|---|---|---|---|---|---|---|---|
| `/` | 모바일 | 100 | 100 | 100 | 100 | 0.76초 | 0.76초 | 5ms | 0 | 206KB |
| `/` | 데스크톱 | 100 | 100 | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 | 206KB |
| `/login` | 모바일 | 100 | **100** | 100 | 100 | 0.75초 | 0.75초 | 4ms | 0 | 203KB |
| `/login` | 데스크톱 | 100 | **100** | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 | 203KB |
| `/search` | 모바일 | 100 | **100** | 100 | 100 | 0.75초 | 0.75초 | 5ms | 0 | 204KB |
| `/search` | 데스크톱 | 100 | **100** | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 | 204KB |

### 3-2. 전후 비교

| 항목 | 전 | 후 | 변화 |
|---|---|---|---|
| Accessibility | `/` 100, `/login` 96, `/search` 95 | 모두 100 | `color-contrast` 감점 해소(대비값: [frontend-design.md](frontend-design.md) 5절) |
| Performance | 모두 100 | 모두 100 | 유지 |
| LCP(모바일 `/`) | 0.76초 | 0.76초 | 유지(되돌림 기준 +0.2초 이내) |
| CLS | 0 | 0 | 유지 |
| 전송량(`/`) | 258KB | 206KB | **-52KB**: 쓰이지 않던 Geist 글꼴 2개 제거 |

홈 첫 화면을 고친 뒤 `/`를 다시 쟀다(3-5절). 점수는 모두 100 그대로이고, 전송량은 미리보기 카드 코드만큼 늘어 **258KB → 208KB(-50KB)**가 최종 값이다.

### 3-3. 기록: IBM Plex Sans KR을 넣었을 때 (채택하지 않음)

디자인 계획(3-3절)대로 IBM Plex Sans KR 400/600을 `next/font/google`로 넣고 같은 조건으로 잰 결과다. 되돌림 기준에 걸려 빼고 위 3-1로 다시 쟀다. 원본: `lighthouse-reports/after-plex-kr/`

| 페이지 | 기기 | Performance(3회) | Accessibility | LCP 중앙값(3회) | 전송량 | 글꼴 파일 |
|---|---|---|---|---|---|---|
| `/` | 모바일 | 75 / 100 / 75 | 100 | **8.95초** (8.95 / 0.90 / 8.95) | 1,154KB | 103개, 909KB |
| `/` | 데스크톱 | 100 | 100 | 0.24초 | 1,154KB | 103개, 909KB |
| `/login` | 모바일 | 100 | 100 | 0.90초 | 1,117KB | 99개, 876KB |
| `/search` | 모바일 | 100 | 100 | 0.90초 | 1,157KB | 104개, 915KB |

- 원인: 한글 웹폰트는 글자 구간별로 쪼개져 있어서 화면 하나에 굵기마다 구간 수십 개를 받는다. LCP 요소(제목 아래 설명 문단)가 `font-display: swap`으로 먼저 그려진 뒤, 웹폰트가 도착하면 다시 그려지면서 LCP 시점이 늦게 기록됐다.
- FCP도 모바일 0.75초 → 0.90초, 데스크톱 0.20초 → 0.24초로 모든 페이지에서 늘었다.

### 3-4. 질문 화면 캡처 (리팩토링 후)
- 조건과 방법은 2절 "질문 화면 캡처"와 같다(로컬 데이터 기준, 수집 배치 끔, 질문당 실제 호출 1회, 모두 6회). 캡처 후 백엔드와 Elasticsearch를 껐고, 공시 건수 6,188건이 그대로인 것을 확인했다.
- 결과는 전과 같았다: q1, q3 찾지 못함 / q2 출처 1건 / q4 거절 / q5 출처 2건 / q6 출처 3건. 범위 밖 번호는 이번에도 없었다. q6 본문에는 `[3]`이 두 번 나와 배지 3이 두 번 보인다.
- 리팩토링 후에만 있는 캡처(같은 응답을 재생해서 추가 비용 없음):
  - `answer-q5-target-desktop.png`, `answer-q6-target-desktop.png`: 본문의 출처 번호 2를 누른 직후. 근거 공시 2가 강조되고 주소가 `#source-2`로 바뀐다.
  - `answer-q6-dark-desktop.png`: 다크 모드
- 저장소에 남긴 파일(`docs/images/frontend/after/`): `answer-q1-desktop.png`, `answer-q6-desktop.png`, `answer-q6-mobile.png`, `answer-q5-target-desktop.png`, `answer-q6-target-desktop.png`, `answer-q6-dark-desktop.png`, `search-q5-desktop.png`, `watchlist-desktop.png`, `notification-desktop.png`

### 3-5. 홈 첫 화면 수정 후 재측정 (2026-11-16)
홈을 2단(질문 + 답변 미리보기)으로 바꾸고, 주요 버튼을 늘 채운 모양으로, 예시 질문에 hover 표시를 넣은 뒤([frontend-design.md](frontend-design.md) 8절) `/`만 다시 쟀다. 조건은 1절과 같다. 원본: `lighthouse-reports/after-home-v2/`

| 페이지 | 기기 | Performance(3회) | Accessibility | Best Practices | SEO | FCP | LCP | TBT | CLS | 전송량 |
|---|---|---|---|---|---|---|---|---|---|---|
| `/` | 모바일 | 100/100/100 | 100 | 100 | 100 | 0.75초 | 0.75초 | 4ms | 0 | 208KB |
| `/` | 데스크톱 | 100/100/100 | 100 | 100 | 100 | 0.20초 | 0.20초 | 0ms | 0 | 208KB |

- 3-1 대비: 전송량 +2KB(미리보기 컴포넌트), LCP는 같다(모바일 0.76초 → 0.75초, 측정 오차 범위).
- 다시 찍은 캡처(모두 녹화한 응답 재생, 유료 호출 0회): 홈 화면이 위에 보이는 `answer-q1-desktop.png`, `answer-q6-desktop.png`, `answer-q6-mobile.png`, `answer-q5-target-desktop.png`, `answer-q6-target-desktop.png`, `answer-q6-dark-desktop.png`와 README GIF(`answer-flow.gif`, 695KB). 새로 추가: `home-desktop.png`, `home-mobile.png`, `home-dark-desktop.png`, `home-hover-desktop.png`(예시 질문 hover).
- 출처 번호 클릭 동작은 다시 확인했다: q5, q6 모두 번호 2를 누르면 `location.hash`가 `#source-2`가 된다.
- 같은 날 한글 줄바꿈(`keep-all`)과 배지 줄 간격을 고친 뒤([frontend-design.md](frontend-design.md) 8절) 위 캡처 중 배지·본문이 보이는 것(`answer-q1-desktop.png` 제외)과 `home-*`, GIF를 다시 재생으로 찍었다. 같은 조건으로 `/`를 다시 쟀고(원본 `lighthouse-reports/after-home-v3/`) 결과는 위 표와 같다: 모바일·데스크톱 모두 4개 점수 100(3회 모두), LCP 모바일 0.75초·데스크톱 0.20초, CLS 0, 전송량 208KB.

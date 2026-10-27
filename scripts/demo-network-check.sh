#!/bin/bash
# InsightStock 시연 네트워크 점검 스크립트 (읽기 전용)
#
# 지금 접속한 네트워크(학교 와이파이, 핫스팟 등)에서 시연이 될지 판단한다.
# 서버 상태를 바꾸는 요청은 보내지 않는다. 보내는 요청은 다음뿐이다.
#   - 공인 IP 확인(checkip.amazonaws.com), 캡티브 포털 확인(generate_204)
#   - 프론트 정적 페이지 GET, API의 /actuator/health GET
#   - API의 /api/v1/answers에 대한 CORS 사전요청(OPTIONS)
#     사전요청은 Spring CorsFilter가 컨트롤러 전에 응답하므로 OpenAI 호출이 일어나지 않는다.
#     /search, /answers 본 요청은 보내지 않는다(유료 API 호출 방지).
#
# 사용법:
#   scripts/demo-network-check.sh
#   FRONT_URL=https://example.vercel.app API_URL=https://insightstock.site scripts/demo-network-check.sh
#
# 환경변수:
#   FRONT_URL  프론트 주소 (기본값: README의 Live Demo 주소)
#   API_URL    백엔드 주소 (기본값: https://insightstock.site)
#
# 종료 코드: 0 = 시연 가능, 1 = 주의, 2 = 불가
#
# macOS 기본 환경(bash 3.2, BSD 도구)에서 동작하도록 작성했다.

set -u

FRONT_URL="${FRONT_URL:-https://frontend-steel-omega-0zye89ut4w.vercel.app}"
API_URL="${API_URL:-https://insightstock.site}"
FRONT_URL="${FRONT_URL%/}"
API_URL="${API_URL%/}"

# 요청 1건의 시간 제한(초)
CONNECT_TIMEOUT=5
MAX_TIME=10
# HTTPS 측정 횟수와 느림 기준(초)
ATTEMPTS=5
SLOW_SECONDS=3
# CORS 사전요청을 보낼 경로 - 프론트(frontend/lib/api.ts)가 JSON 본문으로 POST하는 경로.
# Content-Type: application/json이 붙으므로 브라우저가 실제로 사전요청을 보낸다.
CORS_PATH="/api/v1/answers"

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf '  [PASS] %s\n' "$1"; }
warn() { WARN_COUNT=$((WARN_COUNT + 1)); printf '  [WARN] %s\n' "$1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); printf '  [FAIL] %s\n' "$1"; }
info() { printf '  [INFO] %s\n' "$1"; }
section() { printf '\n== %s\n' "$1"; }

# URL에서 호스트만 꺼낸다 (https://a.b.c/path -> a.b.c)
host_of() {
  local rest="${1#*://}"
  rest="${rest%%/*}"
  printf '%s' "${rest%%:*}"
}

# URL에서 origin만 꺼낸다 (http://a.b.c:3000/path -> http://a.b.c:3000). 포트는 origin의 일부라 남긴다.
origin_of() {
  local scheme="${1%%://*}" rest="${1#*://}"
  printf '%s://%s' "$scheme" "${rest%%/*}"
}

# curl 종료 코드를 한국어 설명으로 바꾼다
curl_reason() {
  case "$1" in
    6) printf '이름 풀이 실패(DNS)' ;;
    7) printf '서버에 연결할 수 없음(서버가 꺼져 있거나 네트워크가 막음)' ;;
    28) printf '시간 초과(응답 없음)' ;;
    35) printf 'TLS 연결 실패(네트워크가 HTTPS를 가로채거나 막고 있을 수 있음)' ;;
    51 | 60) printf 'TLS 인증서 검증 실패(인증 페이지나 보안 장비가 가로채고 있을 수 있음)' ;;
    52) printf '서버가 빈 응답을 보냄' ;;
    56) printf '연결이 중간에 끊김' ;;
    *) printf 'curl 오류 코드 %s' "$1" ;;
  esac
}

if ! command -v curl >/dev/null 2>&1; then
  echo "curl이 필요합니다." >&2
  exit 2
fi

FRONT_HOST="$(host_of "$FRONT_URL")"
API_HOST="$(host_of "$API_URL")"
FRONT_ORIGIN="$(origin_of "$FRONT_URL")"
HEALTH_URL="$API_URL/actuator/health"

printf 'InsightStock 시연 네트워크 점검\n'
printf '  프론트: %s\n  API:   %s\n' "$FRONT_URL" "$API_URL"

# ---------------------------------------------------------------------------
section "1. 현재 네트워크 정보"
PUBLIC_IP="$(curl -sS --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]')"
if [ -n "$PUBLIC_IP" ]; then
  info "현재 공인 IP: $PUBLIC_IP (SSH 보안 그룹은 IP 단위라, 이 네트워크에서 SSH가 필요하면 규칙 갱신이 필요하다)"
else
  warn "공인 IP를 확인하지 못함 - 인터넷 연결 자체를 먼저 확인할 것"
fi

# ---------------------------------------------------------------------------
section "2. 로그인 페이지(캡티브 포털) 감지"
PORTAL_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
  http://connectivitycheck.gstatic.com/generate_204 2>/dev/null)"
if [ "$PORTAL_CODE" = "204" ]; then
  pass "인증 페이지 없이 인터넷에 바로 나감 (204)"
else
  fail "응답 코드 ${PORTAL_CODE:-없음} - 와이파이 인증 페이지가 가로채고 있을 수 있음. 브라우저로 아무 http:// 사이트를 열어 로그인부터 할 것"
fi

# ---------------------------------------------------------------------------
section "3. DNS"
# curl의 time_namelookup으로 시스템 리졸버(브라우저와 같은 경로)의 이름 풀이를 잰다.
# 요청 대상은 허용된 URL(프론트 정적 페이지, health)이다.
check_dns() {
  local label="$1" url="$2" host="$3" out code t
  out="$(curl -sS -o /dev/null -w '%{time_namelookup}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" "$url" 2>/dev/null)"
  code=$?
  t="${out:-0}"
  if [ "$code" -eq 6 ]; then
    fail "$label($host) 이름 풀이 실패 - 이 네트워크의 DNS가 막고 있을 수 있음"
  elif awk -v t="$t" 'BEGIN { exit !(t > 0) }'; then
    pass "$label($host) 이름 풀이 성공 ($(awk -v t="$t" 'BEGIN { printf "%.3f", t }')초)"
  else
    warn "$label($host) 이름 풀이 시간을 재지 못함 ($(curl_reason "$code"))"
  fi
}
check_dns "프론트" "$FRONT_URL" "$FRONT_HOST"
check_dns "API" "$HEALTH_URL" "$API_HOST"

# ---------------------------------------------------------------------------
section "4. HTTPS 연결 (${ATTEMPTS}회 측정)"
# 결과를 "상태코드 connect total" 줄로 모아 평균과 최댓값을 낸다.
measure() {
  local label="$1" url="$2" expect="$3"
  local i out rc samples="" ok=0 last_code="" last_rc=0
  for i in $(seq 1 "$ATTEMPTS"); do
    out="$(curl -sS -o /dev/null -w '%{http_code} %{time_connect} %{time_total}' \
      --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" "$url" 2>/dev/null)"
    rc=$?
    last_rc=$rc
    last_code="${out%% *}"
    if [ "$rc" -eq 0 ] && [ "$last_code" = "$expect" ]; then
      ok=$((ok + 1))
      samples="$samples$out
"
    elif [ "$rc" -ne 0 ] && [ "$i" -eq 1 ]; then
      # 첫 시도부터 연결이 안 되면 나머지는 같은 결과라 시간을 아끼려 건너뛴다
      break
    fi
  done

  if [ "$ok" -eq 0 ]; then
    if [ "$last_rc" -ne 0 ]; then
      fail "$label: $(curl_reason "$last_rc")"
    else
      fail "$label: 기대한 상태 코드 $expect 대신 ${last_code:-없음}"
    fi
    return
  fi

  local stats
  stats="$(printf '%s' "$samples" | awk '
    NF == 3 { n++; c += $2; t += $3; if ($2 > mc) mc = $2; if ($3 > mt) mt = $3 }
    END { printf "%.3f %.3f %.3f %.3f", c / n, mc, t / n, mt }')"
  # shellcheck disable=SC2086
  set -- $stats
  local msg="$label: $ok/$ATTEMPTS 성공(상태 $expect, TLS 정상) connect 평균 ${1}s/최대 ${2}s, total 평균 ${3}s/최대 ${4}s"
  if [ "$ok" -lt "$ATTEMPTS" ]; then
    warn "$msg - 일부 요청 실패($(curl_reason "$last_rc"))"
  elif awk -v t="$4" -v s="$SLOW_SECONDS" 'BEGIN { exit !(t > s) }'; then
    warn "$msg - ${SLOW_SECONDS}초를 넘는 요청이 있음(느린 네트워크)"
  else
    pass "$msg"
  fi
}
measure "프론트 페이지" "$FRONT_URL" "200"
measure "API health" "$HEALTH_URL" "200"

# ---------------------------------------------------------------------------
section "5. CORS 사전요청"
HEADERS="$(curl -sS -o /dev/null -D - -X OPTIONS \
  -H "Origin: $FRONT_ORIGIN" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type" \
  --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" "$API_URL$CORS_PATH" 2>/dev/null)"
CORS_RC=$?
if [ "$CORS_RC" -ne 0 ]; then
  fail "OPTIONS $CORS_PATH: $(curl_reason "$CORS_RC")"
else
  STATUS_LINE="$(printf '%s' "$HEADERS" | tr -d '\r' | grep -E '^HTTP/' | tail -1)"
  ALLOW_ORIGIN="$(printf '%s' "$HEADERS" | tr -d '\r' | grep -i '^access-control-allow-origin:' | tail -1 | sed -E 's/^[^:]*:[[:space:]]*//')"
  if [ "$ALLOW_ORIGIN" = "$FRONT_ORIGIN" ]; then
    pass "OPTIONS $CORS_PATH 허용 - Access-Control-Allow-Origin이 $FRONT_ORIGIN 과 일치 (${STATUS_LINE:-상태 없음})"
  elif [ -z "$ALLOW_ORIGIN" ]; then
    fail "OPTIONS $CORS_PATH 응답에 Access-Control-Allow-Origin이 없음 (${STATUS_LINE:-상태 없음}) - 서버 CORS_ALLOWED_ORIGINS에 프론트 주소가 없거나 서버 오류"
  else
    fail "Access-Control-Allow-Origin이 '$ALLOW_ORIGIN'로 프론트($FRONT_ORIGIN)와 다름"
  fi
fi

# ---------------------------------------------------------------------------
section "6. 판정"
printf '  PASS %d / WARN %d / FAIL %d\n\n' "$PASS_COUNT" "$WARN_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "판정: 이 네트워크에서 시연 불가"
  echo "다음 행동:"
  echo "  1) 인증 페이지 FAIL이면 브라우저에서 와이파이 로그인 후 다시 실행"
  echo "  2) 서버(API) FAIL이면 scripts/demo-server.sh status 로 서버가 켜져 있는지 확인"
  echo "  3) 서버는 켜져 있는데 이 네트워크에서만 실패하면 휴대폰 핫스팟으로 바꾼 뒤 다시 실행"
  echo "  4) 핫스팟에서도 실패하면 노트북에 저장한 시연 영상으로 전환"
  exit 2
elif [ "$WARN_COUNT" -gt 0 ]; then
  echo "판정: 시연 가능하지만 주의 (느리거나 일부 요청 실패 - 핫스팟을 대기시켜 둘 것)"
  exit 1
else
  echo "판정: 이 네트워크에서 시연 가능"
  exit 0
fi

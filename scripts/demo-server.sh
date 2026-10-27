#!/bin/bash
# InsightStock 시연 서버(EC2) 제어 스크립트
#
# 사용법:
#   export INSIGHTSTOCK_INSTANCE_ID=<INSTANCE_ID>
#   export AWS_REGION=ap-northeast-2
#   scripts/demo-server.sh status   # 상태, 인스턴스 타입, 공인 IP 조회 (읽기 전용)
#   scripts/demo-server.sh start    # 인스턴스 시작 -> running 대기 -> health 대기(wait)
#   scripts/demo-server.sh wait     # /actuator/health가 200이 될 때까지 최대 5분 대기
#   scripts/demo-server.sh stop     # 확인 후 인스턴스 중지 (전시 후 비용 절감용)
#
# 환경변수:
#   INSIGHTSTOCK_INSTANCE_ID  대상 인스턴스 ID (필수)
#   AWS_REGION                리전 (필수)
#   API_URL                   health 확인 주소 (기본값: https://insightstock.site)
#   DEMO_INSTANCE_TYPE        시연할 때 쓸 타입 (기본값: t3.medium)
#   IDLE_INSTANCE_TYPE        꺼 둘 때 되돌릴 타입 (기본값: t3.small)
#
# AWS 인증은 기본 프로필을 쓴다. 인스턴스 ID, IP, 키 경로는 이 파일에 적지 않는다.
#
# 운영 방침(docs/deploy.md 19절, docs/runbook-bg-2026-10-14.md 0단계):
#   t3.small로 켜면 백엔드가 자동 기동하면서 메모리 부족으로 인스턴스가 멈출 수 있다.
#   그래서 켤 때는 t3.medium으로 올리고, 끈 뒤에는 t3.small로 되돌린다.
#   start/stop은 이 변경을 할지 매번 물어본다(타입 변경은 인스턴스가 stopped일 때만 가능).
#
# 재부팅 후 자동 기동(docs/deploy.md 22절): docker(enable) -> Postgres/ES 컨테이너
# (restart: unless-stopped) -> systemd insightstock-backend(healthy 대기 후 jar 기동),
# Nginx(enable). 그래서 start 뒤에 SSH로 들어가 수동으로 띄울 것은 없다.

set -u

API_URL="${API_URL:-https://insightstock.site}"
API_URL="${API_URL%/}"
DEMO_INSTANCE_TYPE="${DEMO_INSTANCE_TYPE:-t3.medium}"
IDLE_INSTANCE_TYPE="${IDLE_INSTANCE_TYPE:-t3.small}"
WAIT_LIMIT_SECONDS=300
WAIT_INTERVAL_SECONDS=10

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

# y/Y만 승낙으로 본다. 입력이 없거나 터미널이 아니면 N.
confirm() {
  local answer=""
  printf '%s (y/N) ' "$1"
  read -r answer || answer=""
  case "$answer" in
    y | Y) return 0 ;;
    *) return 1 ;;
  esac
}

ec2() {
  aws ec2 "$@" --region "$AWS_REGION"
}

# "상태 타입 공인IP"를 한 줄로 출력한다
describe() {
  ec2 describe-instances --instance-ids "$INSIGHTSTOCK_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].[State.Name,InstanceType,PublicIpAddress]' \
    --output text
}

cmd_status() {
  local line state type ip
  if ! line="$(describe)"; then
    echo "[FAIL] 인스턴스 조회 실패 - AWS 인증(기본 프로필), 리전, 인스턴스 ID를 확인할 것" >&2
    return 1
  fi
  # shellcheck disable=SC2086
  set -- $line
  state="${1:-?}" type="${2:-?}" ip="${3:-None}"
  echo "상태: $state"
  echo "인스턴스 타입: $type"
  echo "공인 IP: $ip"
  if [ "$state" = "running" ] && [ "$type" != "$DEMO_INSTANCE_TYPE" ]; then
    echo "[WARN] $type 로 실행 중 - 시연 타입($DEMO_INSTANCE_TYPE)이 아니라 검색 중 멈출 수 있다"
  fi
}

cmd_wait() {
  local start=$SECONDS elapsed code
  echo "health 확인: $API_URL/actuator/health (최대 ${WAIT_LIMIT_SECONDS}초, ${WAIT_INTERVAL_SECONDS}초 간격)"
  while :; do
    elapsed=$((SECONDS - start))
    code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 8 "$API_URL/actuator/health" 2>/dev/null)"
    if [ "$code" = "200" ]; then
      echo "[PASS] health 200 (경과 ${elapsed}초)"
      return 0
    fi
    echo "  경과 ${elapsed}초 - 응답 ${code:-없음}, 다시 시도"
    if [ "$elapsed" -ge "$WAIT_LIMIT_SECONDS" ]; then
      break
    fi
    sleep "$WAIT_INTERVAL_SECONDS"
  done
  cat <<'EOF'
[FAIL] 5분 안에 health가 200이 되지 않았다. 확인할 것:
  1) scripts/demo-server.sh status 로 running인지, 타입이 t3.medium인지
  2) SSH 접속 (보안 그룹 22번은 등록한 IP만 허용 - 새 네트워크면 규칙부터 갱신, docs/deploy.md 1절)
       ssh -i "<PEM_PATH>" ec2-user@insightstock.site
  3) 서버에서:
       sudo systemctl status insightstock-backend
       journalctl -u insightstock-backend -n 50
       tail -50 ~/insight-stock/app.log        # 'Application run failed' 확인
       cd ~/insight-stock && docker compose ps  # postgres/elasticsearch가 healthy인지
       sudo systemctl status nginx
  4) 그래도 안 되면 시연 영상으로 전환한다 (docs/demo-runbook.md)
EOF
  return 1
}

cmd_start() {
  local line state type
  line="$(describe)" || { echo "[FAIL] 인스턴스 조회 실패" >&2; return 1; }
  # shellcheck disable=SC2086
  set -- $line
  state="${1:-?}" type="${2:-?}"
  echo "현재: $state / $type"

  case "$state" in
    running)
      echo "이미 실행 중이다. health 대기로 넘어간다."
      cmd_wait
      return $?
      ;;
    pending)
      echo "시작하는 중이다. running을 기다린다."
      ;;
    stopped)
      if [ "$type" != "$DEMO_INSTANCE_TYPE" ]; then
        echo "$type 로 켜면 백엔드 자동 기동 중 인스턴스가 멈출 수 있다(docs/deploy.md 19절)."
        if confirm "인스턴스 타입을 $DEMO_INSTANCE_TYPE 로 바꾼 뒤 시작할까요?"; then
          ec2 modify-instance-attribute --instance-id "$INSIGHTSTOCK_INSTANCE_ID" \
            --instance-type "{\"Value\":\"$DEMO_INSTANCE_TYPE\"}" || return 1
          echo "타입 변경 완료: $DEMO_INSTANCE_TYPE"
        else
          echo "중단했다. 타입을 바꾸지 않고 켜려면 AWS 콘솔이나 CLI로 직접 시작할 것."
          return 1
        fi
      fi
      ec2 start-instances --instance-ids "$INSIGHTSTOCK_INSTANCE_ID" >/dev/null || return 1
      echo "시작 요청 완료."
      ;;
    stopping)
      echo "[FAIL] 아직 중지하는 중이다. stopped가 된 뒤 다시 실행할 것." >&2
      return 1
      ;;
    *)
      echo "[FAIL] 시작할 수 없는 상태: $state" >&2
      return 1
      ;;
  esac

  echo "running 대기..."
  ec2 wait instance-running --instance-ids "$INSIGHTSTOCK_INSTANCE_ID" || return 1
  cmd_status
  cat <<'EOF'
안내: 재부팅 후 DB/검색엔진/백엔드/Nginx는 자동으로 올라온다(docs/deploy.md 22절, 실측 82초).
      SSH로 들어가 docker compose up 이나 java -jar 를 직접 실행하지 않는다(포트가 겹친다).
      오래 꺼 뒀다면 TLS 인증서 만료일을 확인한다(서버에서 sudo certbot renew, deploy.md 9절).
EOF
  cmd_wait
}

cmd_stop() {
  cmd_status || return 1
  if ! confirm "정말 중지할까요?"; then
    echo "취소했다."
    return 0
  fi
  ec2 stop-instances --instance-ids "$INSIGHTSTOCK_INSTANCE_ID" >/dev/null || return 1
  echo "중지 요청 완료. stopped 대기..."
  ec2 wait instance-stopped --instance-ids "$INSIGHTSTOCK_INSTANCE_ID" || return 1
  echo "stopped."
  if confirm "평소 운영 방침대로 인스턴스 타입을 $IDLE_INSTANCE_TYPE 로 되돌릴까요?"; then
    ec2 modify-instance-attribute --instance-id "$INSIGHTSTOCK_INSTANCE_ID" \
      --instance-type "{\"Value\":\"$IDLE_INSTANCE_TYPE\"}" || return 1
    echo "타입 변경 완료: $IDLE_INSTANCE_TYPE"
  fi
  echo "참고: 꺼 둔 동안에도 EBS와 Elastic IP 요금은 나간다(docs/deploy.md 12절, 19절)."
  cmd_status
}

[ $# -eq 1 ] || usage
case "$1" in
  status | start | stop | wait) ;;
  *) usage ;;
esac

# wait는 AWS를 쓰지 않으므로 인스턴스 정보 없이도 실행할 수 있다
if [ "$1" != "wait" ]; then
  if [ -z "${INSIGHTSTOCK_INSTANCE_ID:-}" ] || [ -z "${AWS_REGION:-}" ]; then
    echo "INSIGHTSTOCK_INSTANCE_ID와 AWS_REGION 환경변수가 필요하다." >&2
    echo >&2
    usage
  fi
  if ! command -v aws >/dev/null 2>&1; then
    echo "aws CLI가 필요하다." >&2
    exit 1
  fi
fi

case "$1" in
  status) cmd_status ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  wait) cmd_wait ;;
esac

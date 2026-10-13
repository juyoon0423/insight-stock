# Runbook — minimum-idle 수정 Blue-Green 배포와 커넥션 수 재검증 (2026-10-14)

> **상태: 2026-10-14 실행 완료.** 모든 단계가 "정상" 기준대로 끝났다. 실측값은 `docs/deploy.md` 23절.
> 실행 중 발견한 두 곳(`$R` 변수, ssh 한 줄의 `pkill -f` 패턴)은 이 문서에서 고쳤다.

## 목적

커밋 `8c06650`(Hikari `minimum-idle: 10` 명시)을 EC2에 Blue-Green으로 배포하면서, 전환 중
Postgres 연결 수가 한도 안에 머무는지 실측한다. 전환을 **두 번** 한다.

| 전환 | 이전 인스턴스 | 새 인스턴스 | 보려는 것 | 연결 수 예상 최댓값 |
|---|---|---|---|---|
| 1차 | 구 빌드(`6453405`), 8082, systemd | 새 빌드(`8c06650`), 8081, 직접 실행 | 수정 배포 + 무중단 | 약 60~61 (50 + 10~11) |
| 2차 | 새 빌드, 8081, 직접 실행 | 새 빌드, 8082, systemd | 양쪽 모두 `minimum-idle 10`일 때 | 약 21 (10~11 + 10~11) |

- 구 빌드는 `minimum-idle`이 없어 50개 고정이다. 그래서 1차의 최댓값은 21이 아니라 60 근처다.
- 예상값의 근거는 2026-10-14 로컬 실측이다(수정 전 1개 50 / 2개 100, 수정 후 1개 11 / 2개 21).
  수정 전 2개일 때는 `psql`이 `too many clients already`로 거부됐다.
- 2차가 끝나면 "새 빌드 / 8082 / systemd 관리 / Nginx 8082"가 된다. 시작할 때와 구성이 같아서
  systemd 유닛의 `SERVER_PORT`를 고칠 필요가 없고(deploy.md 22절의 미검증 조합을 피함), 저장소의
  `deploy/insightstock-backend.service`(8082)와도 어긋나지 않는다.

## 비용

- **OpenAI: 0원.** 이 절차는 `/actuator/health`만 호출한다(DB·OpenAI를 부르지 않고 Nginx 레이트
  리밋 대상도 아니다 - deploy.md 20절). `/search`와 `/answers`는 부르지 않는다.
- 기동 직후 OpenAI를 부르는 스케줄러는 없다. OpenAI를 쓰는 스케줄러는 `EmbeddingScheduler`
  하나이고 `embedding.collector.enabled: false`라 빈이 만들어지지 않는다. 기동 시 도는 것은 공시
  수집, 원문 수집, 색인, 알림 감지이며 DART(무료)와 EC2 안의 ES·DB만 쓴다.
- EC2 `.env`는 저장소에서 볼 수 없으므로 1단계에서 확인하고, 1차 전환의 기동 명령에는
  `--embedding.collector.enabled=false --chunking.collector.enabled=false`를 붙인다(실행 인자가
  `.env`보다 우선, 값은 기본값과 같음).
- **AWS**: t3.medium을 켜두는 시간만큼 과금된다. 전체 50~60분 예상.
- Elastic IP라 껐다 켜도 IP가 바뀌지 않는다(deploy.md 12절). 도메인과 Vercel은 건드릴 것이 없다.

## 터미널 구성과 공통 변수

- **터미널 L**: 로컬, 저장소 루트. 명령 실행용.
- **터미널 A**: 로컬, 저장소 루트. 외부 요청 400회 기록.
- **터미널 B**: EC2에 SSH로 접속해 둔 세션. DB 조회와 연결 수 기록.

터미널 L과 A에서 각각 실행한다. 인스턴스 ID는 gitignore된 `deploy/aws-commands-2026-07-28.log`에
있다.

```bash
export PEM="<PEM_PATH>"
export IID="<INSTANCE_ID>"
export HOST="ec2-user@insightstock.site"
```

## 0. EC2 켜기 (터미널 L, 약 5분)

t3.small로 켜면 백엔드가 자동 기동되면서 인스턴스가 멈출 수 있다(deploy.md 19절, 22절). 반드시
t3.medium으로 올린 뒤 켠다.

```bash
aws ec2 describe-instances --region ap-northeast-2 --instance-ids $IID \
  --query 'Reservations[0].Instances[0].[State.Name,InstanceType]' --output text
```

```bash
aws ec2 modify-instance-attribute --region ap-northeast-2 --instance-id $IID \
  --instance-type '{"Value":"t3.medium"}'
```

```bash
aws ec2 start-instances --region ap-northeast-2 --instance-ids $IID
aws ec2 wait instance-running --region ap-northeast-2 --instance-ids $IID
```

1~2분 뒤:

```bash
curl -s https://insightstock.site/actuator/health
```

- **정상**: 첫 명령 `stopped t3.small`, 마지막 명령 `{"status":"UP"}`. systemd가 자동으로 띄우므로
  수동으로 jar를 띄우지 않는다.
- **SSH가 안 되면**: 내 IP가 바뀐 것이다. 보안 그룹 22번 규칙을 갱신한다(deploy.md 1절).

## 1. 사전 확인 (약 5분)

**터미널 L**:

```bash
ssh -i "$PEM" $HOST "grep -o 'server 127.0.0.1:[0-9]*' /etc/nginx/conf.d/insightstock-backend.conf"
```

```bash
ssh -i "$PEM" $HOST "systemctl is-active insightstock-backend; ps -eo pid,args | grep '[j]ava -jar'"
```

```bash
ssh -i "$PEM" $HOST "free -h; grep -E 'collector\.enabled' ~/insight-stock/.env"
```

**터미널 B** (여기서 접속해 두고 끝까지 쓴다):

```bash
ssh -i "$PEM" $HOST
```

접속한 뒤, 연결 수를 세는 함수를 정의한다(이후 단계에서 계속 쓴다):

```bash
conn() {
  docker exec insight-stock-postgres sh -c 'psql -U "$POSTGRES_USER" -d insight_stock -Atc "SELECT count(*) FROM pg_stat_activity WHERE backend_type = '"'"'client backend'"'"' AND pid <> pg_backend_pid()"' 2>&1 | tail -1
}
```

```bash
docker exec insight-stock-postgres sh -c 'psql -U "$POSTGRES_USER" -d insight_stock -c "SHOW max_connections" -c "SHOW superuser_reserved_connections" -c "SELECT rolname, rolsuper FROM pg_roles WHERE rolname = current_user"'
```

```bash
conn
```

- **정상**: 라이브 포트 `server 127.0.0.1:8082`, `active`, java 프로세스 1개(`app.jar`), `free`의
  available 1GB 이상.
- **예상 값**: `max_connections` 100, `superuser_reserved_connections` 3, `rolsuper` t, `conn` 50
  (구 빌드가 50 고정이라는 증거).
- **라이브 포트가 8082가 아니면**: 멈춘다. 이 문서는 "라이브 8082, systemd 관리"를 전제로 한다.
- **`.env`에 `embedding.collector.enabled=true`나 `chunking.collector.enabled=true`가 보이면**:
  멈춘다. 2차 전환의 systemd 인스턴스가 OpenAI를 호출하게 된다.

## 2. 빌드와 전송 (터미널 L, 약 5분)

```bash
git log --oneline -1
```

```bash
(cd backend && ./gradlew clean build -x test)
```

```bash
scp -i "$PEM" backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar \
  $HOST:~/insight-stock/app-v2.jar
```

```bash
shasum -a 256 backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar
ssh -i "$PEM" $HOST "sha256sum ~/insight-stock/app-v2.jar"
```

- **정상**: 커밋이 `8c06650`(또는 그 이후), 두 체크섬이 같다.

---

# 1차 전환: 구 빌드 8082 → 새 빌드 8081

## 3. 1차 측정 시작

400회는 약 4~5분이다. 터미널 A를 시작한 뒤 4~6단계를 그 안에 끝낸다.

**터미널 B** (먼저 시작):

```bash
while true; do echo "$(date +%T) $(conn)"; sleep 0.5; done | tee ~/insight-stock/bg1-conn-1014.log
```

**터미널 A**:

```bash
for i in $(seq 1 400); do
  echo "$(date +%T) $(curl -s -o /dev/null -m 5 -w '%{http_code} %{time_total}' https://insightstock.site/actuator/health)"
  sleep 0.5
done | tee deploy/bg1-http-2026-10-14.log
```

- **정상**: B에 `50`이 계속 찍히고, A에 `200 0.x`가 계속 찍힌다.
- 7/31 검증과 같은 방법이다(로컬에서 0.5초 간격 헬스체크 curl 400회 - deploy.md 16절). 당시
  스크립트 파일은 저장소에 남아 있지 않다. 주소만 `http://<IP>`에서 `https://insightstock.site`로
  바뀌었다(TLS 적용 후 IP 직접 호출은 404).

## 4. 새 빌드를 8081로 기동 (터미널 L)

```bash
ssh -i "$PEM" $HOST "cd ~/insight-stock && (SERVER_PORT=8081 nohup java -jar app-v2.jar \
  --embedding.collector.enabled=false --chunking.collector.enabled=false \
  > app-8081.log 2>&1 < /dev/null &)"
```

20~30초 뒤:

```bash
ssh -i "$PEM" $HOST "curl -s http://localhost:8081/actuator/health; echo"
```

```bash
ssh -i "$PEM" $HOST "grep -c 'Application run failed' ~/insight-stock/app-8081.log; free -h | sed -n 2p"
```

- **정상**: `{"status":"UP"}`, `0`, 터미널 B가 50 → 60~61.
- **`UP`이 아니면**: 5단계로 넘어가지 않는다(되돌리기 1-a).
- **터미널 B가 100 근처이거나 `too many clients`가 찍히면**: 수정이 들어가지 않은 jar다. 중단하고
  되돌리기 1-a.

## 5. Nginx를 8081로 전환 (터미널 L)

```bash
ssh -i "$PEM" $HOST "sudo cp /etc/nginx/conf.d/insightstock-backend.conf \
  /etc/nginx/insightstock-backend.conf.pre-bg-2026-10-14"
```

```bash
ssh -i "$PEM" $HOST "sudo sed -i 's/server 127.0.0.1:8082;/server 127.0.0.1:8081;/' \
  /etc/nginx/conf.d/insightstock-backend.conf && \
  grep -o 'server 127.0.0.1:[0-9]*' /etc/nginx/conf.d/insightstock-backend.conf"
```

```bash
ssh -i "$PEM" $HOST "sudo nginx -t && sudo nginx -s reload"
```

```bash
curl -s https://insightstock.site/actuator/health
```

- **정상**: `server 127.0.0.1:8081`, `syntax is ok` / `test is successful`, `{"status":"UP"}`,
  터미널 A에 200이 끊기지 않는다.

## 6. 구 빌드(8082) 종료 (터미널 L)

구 인스턴스는 systemd가 띄운 것이라 `pkill`이 아니라 `systemctl`로 내린다.

```bash
ssh -i "$PEM" $HOST "sudo systemctl stop insightstock-backend; ps -eo pid,args | grep '[j]ava -jar'"
```

- **정상**: java 프로세스가 `app-v2.jar` 하나만 남는다. 터미널 B가 60~61 → 10~11. 터미널 A는 계속
  200.

터미널 A가 400회를 마칠 때까지 기다린 뒤, 터미널 B의 루프를 Ctrl-C로 멈춘다.

## 7. 1차 결과 집계

**터미널 L** (응답 코드별 개수 / 200 개수 / 최대 응답 시간):

```bash
awk '{print $2}' deploy/bg1-http-2026-10-14.log | sort | uniq -c
```

```bash
awk '$2==200' deploy/bg1-http-2026-10-14.log | wc -l
```

```bash
awk '{if($3>m)m=$3} END{print m}' deploy/bg1-http-2026-10-14.log
```

**터미널 B** (연결 수 최댓값 / 조회 실패 횟수 / 값 분포):

```bash
awk '$2 ~ /^[0-9]+$/ {if($2>m)m=$2} END{print m}' ~/insight-stock/bg1-conn-1014.log
```

```bash
grep -c -v -E ' [0-9]+$' ~/insight-stock/bg1-conn-1014.log
```

```bash
awk '{print $2}' ~/insight-stock/bg1-conn-1014.log | sort -n | uniq -c
```

- **정상**: 200이 400개, 연결 수 최댓값 60~61, 조회 실패 0회.

---

# 2차 전환: 새 빌드 8081 → 새 빌드 8082 (systemd)

## 8. jar 승격 (터미널 L)

`mv`가 아니라 `cp`를 쓴다 - 8081 프로세스가 `app-v2.jar`를 실행 중이므로 그 파일은 건드리지 않는다.
구 빌드는 롤백용으로 남긴다(deploy.md 15절의 `rm -f app.jar` 대신).

```bash
ssh -i "$PEM" $HOST "cd ~/insight-stock && mv app.jar app-prev-1014.jar && cp app-v2.jar app.jar && \
  sha256sum app.jar app-v2.jar"
```

- **정상**: 두 체크섬이 같다(2단계의 값과도 같다).

## 9. 2차 측정 시작

**터미널 B**:

```bash
while true; do echo "$(date +%T) $(conn)"; sleep 0.5; done | tee ~/insight-stock/bg2-conn-1014.log
```

**터미널 A**:

```bash
for i in $(seq 1 400); do
  echo "$(date +%T) $(curl -s -o /dev/null -m 5 -w '%{http_code} %{time_total}' https://insightstock.site/actuator/health)"
  sleep 0.5
done | tee deploy/bg2-http-2026-10-14.log
```

- **정상**: B에 `10` 또는 `11`, A에 `200`.

## 10. 새 빌드를 systemd로 8082에 기동 (터미널 L)

유닛은 고치지 않는다. `app.jar`(이제 새 빌드)를 `SERVER_PORT=8082`로 띄운다.

```bash
ssh -i "$PEM" $HOST "sudo systemctl start insightstock-backend && systemctl is-active insightstock-backend"
```

20~30초 뒤:

```bash
ssh -i "$PEM" $HOST "curl -s http://localhost:8082/actuator/health; echo"
```

```bash
ssh -i "$PEM" $HOST "ps -eo pid,args | grep '[j]ava -jar'; free -h | sed -n 2p"
```

- **정상**: `active`, `{"status":"UP"}`, java 프로세스 2개(`app-v2.jar`와 `app.jar`), 터미널 B가
  10~11 → 약 21.
- **`UP`이 아니면**: 11단계로 넘어가지 않는다(되돌리기 2-a). 유닛이 `Restart=on-failure`라 실패하면
  10초마다 다시 뜨려고 하므로 반드시 `systemctl stop`으로 멈춘다.
- 기동 로그는 `app.log`에 이어서 쌓인다.

## 11. Nginx를 8082로 전환 (터미널 L)

```bash
ssh -i "$PEM" $HOST "sudo sed -i 's/server 127.0.0.1:8081;/server 127.0.0.1:8082;/' \
  /etc/nginx/conf.d/insightstock-backend.conf && \
  grep -o 'server 127.0.0.1:[0-9]*' /etc/nginx/conf.d/insightstock-backend.conf"
```

```bash
ssh -i "$PEM" $HOST "sudo nginx -t && sudo nginx -s reload"
```

```bash
curl -s https://insightstock.site/actuator/health
```

- **정상**: `server 127.0.0.1:8082`, `{"status":"UP"}`, 터미널 A에 200이 끊기지 않는다.

## 12. 8081 프로세스 종료 (터미널 L)

```bash
ssh -i "$PEM" $HOST "pkill -f '[a]pp-v2.jar'; sleep 5; ps -eo pid,args | grep '[j]ava -jar'"
```

- **정상**: java 프로세스가 `app.jar` 하나만 남는다. 터미널 B가 약 21 → 10~11. 터미널 A는 계속 200.

터미널 A가 400회를 마칠 때까지 기다린 뒤, 터미널 B의 루프를 Ctrl-C로 멈춘다.

## 13. 2차 결과 집계

**터미널 L**:

```bash
awk '{print $2}' deploy/bg2-http-2026-10-14.log | sort | uniq -c
```

```bash
awk '$2==200' deploy/bg2-http-2026-10-14.log | wc -l
```

```bash
awk '{if($3>m)m=$3} END{print m}' deploy/bg2-http-2026-10-14.log
```

**터미널 B**:

```bash
awk '$2 ~ /^[0-9]+$/ {if($2>m)m=$2} END{print m}' ~/insight-stock/bg2-conn-1014.log
```

```bash
grep -c -v -E ' [0-9]+$' ~/insight-stock/bg2-conn-1014.log
```

```bash
awk '{print $2}' ~/insight-stock/bg2-conn-1014.log | sort -n | uniq -c
```

- **정상**: 200이 400개, 연결 수 최댓값 약 21, 조회 실패 0회.

## 14. 정리와 최종 상태 확인 (터미널 L)

```bash
ssh -i "$PEM" $HOST "rm ~/insight-stock/app-v2.jar"
```

```bash
ssh -i "$PEM" $HOST "systemctl is-active insightstock-backend; systemctl is-enabled insightstock-backend; \
  grep SERVER_PORT /etc/systemd/system/insightstock-backend.service; \
  grep -o 'server 127.0.0.1:[0-9]*' /etc/nginx/conf.d/insightstock-backend.conf"
```

EC2의 연결 수 로그를 로컬로 가져온다(`deploy/*.log`는 gitignore 대상):

```bash
scp -i "$PEM" $HOST:~/insight-stock/bg1-conn-1014.log deploy/bg1-conn-2026-10-14.log
scp -i "$PEM" $HOST:~/insight-stock/bg2-conn-1014.log deploy/bg2-conn-2026-10-14.log
```

- **정상**: `active`, `enabled`, `Environment=SERVER_PORT=8082`, `server 127.0.0.1:8082`.
  시작할 때와 같은 구성이고 jar만 새 빌드다.
- 남는 파일: `app-prev-1014.jar`(구 빌드, 롤백용), `app-8081.log`(1차 전환 인스턴스의 로그).

## 15. EC2 끄기 (터미널 L, 약 5분)

터미널 B는 `exit`로 닫는다.

```bash
aws ec2 stop-instances --region ap-northeast-2 --instance-ids $IID
aws ec2 wait instance-stopped --region ap-northeast-2 --instance-ids $IID
```

```bash
aws ec2 modify-instance-attribute --region ap-northeast-2 --instance-id $IID \
  --instance-type '{"Value":"t3.small"}'
```

```bash
aws ec2 describe-instances --region ap-northeast-2 --instance-ids $IID \
  --query 'Reservations[0].Instances[0].[State.Name,InstanceType]' --output text
```

- **정상**: `stopped t3.small`.

---

## 되돌리기

Nginx 설정 원본(8082)은 `/etc/nginx/insightstock-backend.conf.pre-bg-2026-10-14`에 남는다.
아래에서 "Nginx를 8082로" / "Nginx를 8081로"는 다음 명령이다.

```bash
# Nginx를 8082로
ssh -i "$PEM" $HOST "sudo sed -i 's/server 127.0.0.1:8081;/server 127.0.0.1:8082;/' \
  /etc/nginx/conf.d/insightstock-backend.conf && sudo nginx -t && sudo nginx -s reload"
```

```bash
# Nginx를 8081로
ssh -i "$PEM" $HOST "sudo sed -i 's/server 127.0.0.1:8082;/server 127.0.0.1:8081;/' \
  /etc/nginx/conf.d/insightstock-backend.conf && sudo nginx -t && sudo nginx -s reload"
```

### 1차 전환

| 번호 | 시점 | 방법 | 중단 |
|---|---|---|---|
| 1-a | 4단계: 8081이 `UP`이 안 됨 | 새 프로세스만 종료(`pkill -f '[a]pp-v2.jar'`). 라이브(8082)는 건드린 적이 없다 | 없음 |
| 1-b | 5단계 이후: Nginx 전환 후 이상, 8082는 아직 떠 있음 | Nginx를 8082로 → `pkill -f '[a]pp-v2.jar'` | 없음 |
| 1-c | 6단계 이후: 8082를 이미 내림 | `sudo systemctl start insightstock-backend`(구 빌드가 8082로 뜸) → `curl localhost:8082/actuator/health`가 `UP` → Nginx를 8082로 → `pkill -f '[a]pp-v2.jar'` | 없음(8081이 계속 응답) |

```bash
# 1-a, 1-b, 1-c 공통: 새 프로세스 종료
ssh -i "$PEM" $HOST "pkill -f '[a]pp-v2.jar'"
```

```bash
# 1-c: 구 빌드를 systemd로 다시 띄우고 확인
ssh -i "$PEM" $HOST "sudo systemctl start insightstock-backend; sleep 30; \
  curl -s http://localhost:8082/actuator/health"
```

### 2차 전환

| 번호 | 시점 | 방법 | 중단 |
|---|---|---|---|
| 2-a | 10단계: 8082가 `UP`이 안 됨 | `sudo systemctl stop insightstock-backend`. 라이브(8081)는 그대로다. 2차를 포기하려면 아래 "2차 포기" 참고 | 없음 |
| 2-b | 11단계 이후: Nginx 전환 후 이상, 8081은 아직 떠 있음 | Nginx를 8081로 → `sudo systemctl stop insightstock-backend` | 없음 |
| 2-c | 12단계 이후: 8081을 이미 내림 | 두 인스턴스가 같은 빌드이므로 문제는 빌드 자체다. 구 빌드로 복귀: `systemctl stop` → jar 되돌리기 → `systemctl start`. Nginx는 8082 그대로 | 약 30초(502) |

```bash
# 2-a, 2-b: systemd 인스턴스 내리기
ssh -i "$PEM" $HOST "sudo systemctl stop insightstock-backend"
```

```bash
# 2-c: 구 빌드로 복귀 (app-v2.jar를 이미 지웠어도 된다)
ssh -i "$PEM" $HOST "sudo systemctl stop insightstock-backend && cd ~/insight-stock && \
  mv app.jar app-bad-1014.jar && mv app-prev-1014.jar app.jar && \
  sudo systemctl start insightstock-backend"
```

**2차 포기(2-a 이후 8081을 라이브로 둔 채 끝내는 경우)**: 이 상태로 인스턴스를 끄면 다음 부팅 때
systemd가 `app.jar`를 8082로 띄우는데 Nginx는 8081을 가리켜 502가 된다. 끄기 전에 Nginx를 8082로
돌려 둔다(그 순간부터 정지할 때까지는 502). `app.jar`가 새 빌드인지 구 빌드인지는 8단계 실행 여부에
달려 있으니 `sha256sum app.jar`로 확인한다.

### 공통

| 상황 | 방법 |
|---|---|
| SSH까지 무응답(메모리 부족 - deploy.md 17절 증상) | `aws ec2 stop-instances --region ap-northeast-2 --instance-ids $IID --force` 후 다시 켠다. systemd가 `app.jar`를 8082로 띄우므로, Nginx가 8081을 가리키고 있으면 8082로 돌린다 |
| Nginx 설정이 꼬임 | 백업을 되돌린다: `sudo cp /etc/nginx/insightstock-backend.conf.pre-bg-2026-10-14 /etc/nginx/conf.d/insightstock-backend.conf && sudo nginx -t && sudo nginx -s reload` (8082를 가리킴) |

# AWS EC2 배포 절차 (검증용)

**목적**: 로컬 개발 환경(docker-compose + Spring Boot jar)을 AWS EC2 한 대에 그대로 옮겨
실제로 붙는지 검증한다. 평소엔 인스턴스를 꺼두고 시연(2026-11) 직전에만 켜서 쓰는
운영 방식을 전제로, "껐다 켜고 다시 배포"를 반복 가능하게 정리한 문서다.

**이번 범위**: 배포까지만. 무중단 배포(Blue-Green, 롤링 등)는 다음 단계에서 별도로 다룬다.

## 아키텍처 요약

- EC2 인스턴스 1대(t3.small, Amazon Linux 2023) 위에서:
  - `docker compose`로 PostgreSQL(pgvector) + Elasticsearch(Nori) 컨테이너 실행
  - Spring Boot는 컨테이너화하지 않고 `java -jar`로 호스트에서 직접 실행 (Docker 이미지화는 나중 단계)
  - 백엔드는 `localhost:5432`, `localhost:9200`으로 접속 (compose가 호스트 포트로 매핑해주므로 가능)
- DB(5432)/ES(9200) 포트는 보안 그룹에서 열지 않는다 — 호스트 내부에서만 접근.

## 사전 준비물

- AWS CLI 인증 완료 (`aws sts get-caller-identity`로 확인)
- 리전: `ap-northeast-2` (서울)
- SSH 키 페어: `insightstock-key` (AWS에 등록됨), `.pem` 파일은 본인 로컬 어딘가에 보관
  - 아래 명령어의 `<PEM_PATH>`는 본인 키 경로로 치환할 것 (예: 데스크톱 등 개인 경로 — 절대 문서/커밋에 실제 경로를 남기지 않는다)
  - 경로에 공백이 있을 수 있으니 항상 큰따옴표로 감쌀 것: `"<PEM_PATH>"`
- 프로젝트 루트의 `.env` (커밋 안 됨 — DART/OpenAI API 키, DB 비밀번호, JWT 시크릿 포함)

## 1. 보안 그룹 생성 (최초 1회)

```bash
VPC_ID=$(aws ec2 describe-vpcs --region ap-northeast-2 \
  --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)

MY_IP=$(curl -s https://checkip.amazonaws.com)/32

SG_ID=$(aws ec2 create-security-group --region ap-northeast-2 \
  --group-name insightstock-sg --description "InsightStock backend deploy SG" \
  --vpc-id "$VPC_ID" --query 'GroupId' --output text)

aws ec2 create-tags --region ap-northeast-2 --resources "$SG_ID" \
  --tags Key=Name,Value=insightstock-sg

# SSH: 내 IP만 허용 (0.0.0.0/0 금지)
aws ec2 authorize-security-group-ingress --region ap-northeast-2 \
  --group-id "$SG_ID" --protocol tcp --port 22 --cidr "$MY_IP"

# HTTP/HTTPS: 전체 허용
aws ec2 authorize-security-group-ingress --region ap-northeast-2 \
  --group-id "$SG_ID" --protocol tcp --port 80 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --region ap-northeast-2 \
  --group-id "$SG_ID" --protocol tcp --port 443 --cidr 0.0.0.0/0

# 8080: 백엔드 API 테스트용 임시 전체 허용 (나중에 Nginx 리버스 프록시 뒤로 감출 예정)
aws ec2 authorize-security-group-ingress --region ap-northeast-2 \
  --group-id "$SG_ID" --protocol tcp --port 8080 --cidr 0.0.0.0/0

# 주의: 5432(Postgres), 9200(ES)는 의도적으로 열지 않는다.
```

> 내 IP가 바뀌면(자택 회선 재할당 등) 22번 포트 규칙을 갱신해야 SSH가 다시 된다:
> `aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 --cidr <새 IP>/32`
> 그리고 기존 규칙은 `revoke-security-group-ingress`로 제거.

## 2. EC2 인스턴스 생성 (최초 1회)

`deploy/user-data.sh`가 최초 부팅 시 Docker + Docker Compose plugin + Java 17을 자동 설치한다.

```bash
AMI_ID=$(aws ec2 describe-images --region ap-northeast-2 --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
  --query 'sort_by(Images,&CreationDate)[-1].[ImageId]' --output text)

SUBNET_ID=<기본 VPC의 서브넷 ID 중 하나>   # 예: describe-subnets로 조회한 az 중 하나 선택

aws ec2 run-instances --region ap-northeast-2 \
  --image-id "$AMI_ID" \
  --instance-type t3.small \
  --key-name insightstock-key \
  --security-group-ids "$SG_ID" \
  --subnet-id "$SUBNET_ID" \
  --associate-public-ip-address \
  --user-data file://deploy/user-data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=insightstock-backend}]' \
  --count 1
```

인스턴스가 `running` 상태가 될 때까지 대기:

```bash
aws ec2 wait instance-running --region ap-northeast-2 --instance-ids <INSTANCE_ID>
```

퍼블릭 IP 조회 (2026-10-12부터 Elastic IP가 연결되어 있어 재시작해도 바뀌지 않는다 — 12절 참고.
인스턴스를 새로 만드는 경우에는 12절의 할당/연결 절차를 이 단계 뒤에 수행):

```bash
aws ec2 describe-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
```

user-data가 다 실행됐는지 확인 (SSH 접속 후):

```bash
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "sudo cloud-init status; docker --version; docker compose version; java -version"
```

## 3. Postgres + Elasticsearch 기동

로컬 `docker-compose.yml`과 `docker/` 폴더(ES Dockerfile, postgres init 스크립트)를 그대로 옮긴다.

```bash
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "mkdir -p ~/insight-stock"

scp -i "<PEM_PATH>" -r docker-compose.yml docker ec2-user@<PUBLIC_IP>:~/insight-stock/
```

`.env`도 같은 디렉터리로 전송한다 (docker compose가 `${POSTGRES_USER}` 등을 여기서 읽음).
**절대 로그/커밋에 내용을 남기지 말 것** — scp 자체는 SSH 암호화 채널이라 안전:

```bash
scp -i "<PEM_PATH>" .env ec2-user@<PUBLIC_IP>:~/insight-stock/.env
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "chmod 600 ~/insight-stock/.env"
```

컨테이너 기동 및 헬스체크:

```bash
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "cd ~/insight-stock && docker compose up -d --build"

# healthy 될 때까지 확인
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "docker ps; docker inspect --format='{{.State.Health.Status}}' insight-stock-postgres insight-stock-elasticsearch"
```

## 4. 백엔드 빌드 및 실행

이번 단계는 Docker 이미지화 없이 jar를 직접 옮겨 실행하는 방식으로 진행한다(다음 단계 후보).

```bash
# 로컬에서 빌드
cd backend && ./gradlew build -x test

# jar 전송
scp -i "<PEM_PATH>" backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar \
  ec2-user@<PUBLIC_IP>:~/insight-stock/app.jar

# EC2에서 실행 (nohup + disown으로 SSH 세션 종료 후에도 유지)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "cd ~/insight-stock && nohup java -jar app.jar > app.log 2>&1 < /dev/null & disown"
```

> `.env`가 jar와 같은 디렉터리(`~/insight-stock/`)에 있어야 `spring-dotenv`가 자동으로 읽는다.
> 로컬 `bootRun`/`test`에서 쓰는 `SPRINGDOTENV_DIRECTORY=..` 환경변수는 gradle 태스크 전용
> 설정이라 여기선 필요 없다 (jar와 `.env`가 같은 디렉터리에 있으면 기본 동작으로 충분).

프로세스가 살아있는지, 로그가 정상인지 확인:

```bash
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "ps aux | grep '[j]ava -jar'; tail -50 ~/insight-stock/app.log"
```

## 5. 검증

```bash
# 외부에서 헬스체크
curl http://<PUBLIC_IP>:8080/actuator/health   # {"status":"UP"} 기대

# EC2 내부에서 DB/ES 개별 확인
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "docker exec insight-stock-postgres pg_isready -U \$(grep POSTGRES_USER ~/insight-stock/.env | cut -d= -f2) -d insight_stock"
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "curl -s http://localhost:9200/_cluster/health?pretty"

# 실제 API 호출로 끝까지 동작 확인 (회원가입 -> 로그인)
curl -X POST http://<PUBLIC_IP>:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"<test-email>","password":"<8자 이상>","nickname":"<2~20자>"}'

curl -X POST http://<PUBLIC_IP>:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<test-email>","password":"<8자 이상>"}'
# accessToken/refreshToken이 담긴 JSON이 오면 DB 쓰기/읽기, JWT 발급까지 전부 정상.
```

## 6. 끌 때 (평소 운영)

인스턴스를 꺼도 퍼블릭 IP는 그대로 유지된다(Elastic IP 적용, 12절 — 단 꺼둔 동안에도
Elastic IP 요금은 계속 나간다). 다시 켤 때는 3단계
헬스체크부터 다시 하면 되고, 리소스(보안 그룹, 인스턴스 자체, jar, docker-compose 설정)는
그대로 남아있으니 1~2단계(보안 그룹/인스턴스 생성)를 반복할 필요는 없다.

```bash
# 컨테이너는 그대로 두고 인스턴스만 정지 (볼륨 데이터 보존)
aws ec2 stop-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
```

## 7. 다시 켤 때

```bash
aws ec2 start-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 wait instance-running --region ap-northeast-2 --instance-ids <INSTANCE_ID>

# 퍼블릭 IP는 Elastic IP라 그대로다(12절) - 확인만 한다
aws ec2 describe-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text

# 컨테이너 상태 확인 (2026-10-12 실측: 재기동 시 컨테이너는 자동으로 다시 떴다.
# 안 떠 있으면 up -d로 올린다 - 이미 떠 있어도 실행해서 해가 없다)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "cd ~/insight-stock && docker compose up -d"

# 중요: 둘 다 healthy가 된 뒤에 백엔드를 띄운다 (아래 주의 참고)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "docker inspect --format='{{.State.Health.Status}}' insight-stock-postgres insight-stock-elasticsearch"

# 백엔드도 다시 실행 (재부팅 시 프로세스가 사라지므로).
# 포트는 nginx upstream이 가리키는 값으로 맞춘다 (14절) - 2026-10-12 기준 8082
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "cd ~/insight-stock && (SERVER_PORT=8082 nohup java -jar app.jar > app.log 2>&1 < /dev/null &)"
```

> **주의 1 — Elasticsearch가 healthy가 되기 전에 jar를 띄우면 기동에 실패한다** (2026-10-12
> 실측). 재기동 직후 SSH가 되자마자 바로 jar를 띄웠더니 `chunkDocumentRepository` 초기화가
> `ConnectionClosedException: Connection is closed`로 실패하면서 애플리케이션이 종료됐고,
> 외부 헬스체크는 2분 넘게 502만 반환했다. 컨테이너가 `Running`으로 보여도 ES가 요청을
> 받을 준비가 된 것은 아니다 — 반드시 `healthy`를 확인한 뒤 띄울 것. 502가 계속되면
> `ps aux | grep '[j]ava -jar'`와 `app.log`의 `Application run failed`부터 확인한다.
>
> **주의 2 — 기동 명령은 괄호(서브셸)로 감싼다.** 기존의 `"cd ... && nohup java ... & disown"`
> 형태는 같은 날 실측에서 로컬 SSH 세션이 반환되지 않고 걸렸다(원격 프로세스 자체는 정상
> 기동됨). `( ... &)` 형태는 즉시 반환됐다.

> 참고: SSH 보안 그룹 규칙이 "내 IP"로 고정되어 있으므로, 새 위치(카페, 다른 네트워크 등)에서
> 접속하려면 1단계의 "내 IP가 바뀌면" 절차대로 규칙을 갱신해야 한다.

> **아래 목록은 Elastic IP 적용(2026-10-12, 12절) 이후로는 평소에 해당하지 않는다** — IP가
> 고정이라 재기동해도 프론트엔드를 건드릴 필요가 없다. Elastic IP를 해제하거나 인스턴스를
> 새로 만들어 IP가 실제로 바뀌는 경우에만 참고할 것.

**퍼블릭 IP가 바뀔 때마다 프론트엔드도 반드시 갱신해야 한다:**

- `frontend/.env.local`의 `NEXT_PUBLIC_API_BASE_URL`을 새 IP로 교체
  (`frontend/lib/api.ts`가 이 값으로 백엔드를 호출 — 값이 없으면 에러를 던지도록 되어있음)
- `NEXT_PUBLIC_*`는 Next.js가 빌드 타임에 정적으로 주입하므로, 이미 빌드된 프론트엔드를
  쓰고 있다면 `.env.local` 수정만으로는 반영되지 않는다 — 다음 중 하나 필요:
  - 로컬에서 `npm run dev`로 띄우는 경우: `.env.local` 수정 후 재시작하면 반영됨
  - 빌드된 결과물을 배포한 경우: `.env.local` 수정 후 `npm run build`로 다시 빌드해야 함
- 백엔드 `application.yml`의 `cors.allowed-origins`는 프론트엔드가 어디서 호출하는지(그
  origin)에 대한 설정이라 백엔드 IP가 바뀌는 것과는 무관 — 프론트엔드 자체 주소가 바뀔
  때만 여기를 갱신하면 된다.

## 8. 완전히 정리할 때 (더 이상 안 쓸 때)

```bash
aws ec2 terminate-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 wait instance-terminated --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 delete-security-group --region ap-northeast-2 --group-id <SG_ID>

# Elastic IP도 반드시 해제한다 - 인스턴스를 종료해도 Elastic IP는 자동으로 사라지지 않고,
# 해제하기 전까지 계속 과금된다 (12절)
aws ec2 describe-addresses --region ap-northeast-2 \
  --filters Name=tag:Name,Values=insightstock-eip --query 'Addresses[0].AllocationId' --output text
aws ec2 release-address --region ap-northeast-2 --allocation-id <ALLOCATION_ID>
```

## 9. Nginx 리버스 프록시 (2026-07-28 적용)

8080을 그대로 외부에 공개하지 않기 위해 EC2 내부에 Nginx를 두고 80 → 8080으로
프록시한다. 백엔드는 여전히 `localhost:8080`에서 그대로 실행되고, 외부에는 80번만 보인다.

**최초 1회 설치 및 설정** (Amazon Linux 2023, `dnf`로 설치):

```bash
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "sudo dnf install -y nginx"
```

`/etc/nginx/nginx.conf`에 기본으로 들어있는 인라인 `server { listen 80; ... }` 블록은
제거하고(우리 프록시 설정과 포트 80을 두고 충돌하므로), 대신 `/etc/nginx/conf.d/`에
별도 파일로 프록시 설정을 넣는다:

```nginx
# /etc/nginx/conf.d/insightstock-backend.conf
server {
    listen 80 default_server;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo nginx -t   # 문법 검증
sudo systemctl enable --now nginx   # 기동 + 재부팅 시 자동 시작 등록
```

**보안 그룹 정리**: 8080 전체 허용 규칙을 제거한다(더 이상 직접 노출할 필요 없음, 80만 유지).

```bash
aws ec2 revoke-security-group-ingress --region ap-northeast-2 \
  --group-id "$SG_ID" --protocol tcp --port 8080 --cidr 0.0.0.0/0
```

인스턴스를 다시 켤 때마다 반복할 필요는 없다 — Nginx는 `systemctl enable`로 등록되어
있어 EC2 재부팅 시 자동으로 뜬다. 다만 **백엔드 jar 프로세스 자체는 여전히 자동 기동되지
않으므로**(8절의 `nohup java -jar` 명령을 EC2 재부팅/재기동마다 다시 실행해야 함) 이 부분은
그대로 남아있는 제약이다.

검증 (아래 "HTTPS 적용" 이후로는 IP가 아니라 도메인으로 확인한다):

```bash
curl https://insightstock.site/actuator/health        # 443번, {"status":"UP"} 기대
curl -m 8 http://<PUBLIC_IP>:8082/actuator/health     # 백엔드 포트 직접, 타임아웃/거부 기대
```

### HTTPS 적용 — 도메인 + Let's Encrypt (2026-10-12 적용)

도메인 `insightstock.site`(Namecheap에서 구매)를 Elastic IP에 연결하고 Let's Encrypt
인증서를 붙였다. 이제 외부에서 보는 백엔드 주소는 `https://insightstock.site`다.

**1) DNS** — Namecheap은 Route53이 아니라 CLI로 자동화할 수 없어 웹 콘솔(Domain List →
Manage → Advanced DNS)에서 직접 등록한다. A 레코드 1개(Host `@`, Value = Elastic IP)면
충분하다. 새 도메인에 기본으로 들어있는 파킹용 레코드(`@` URL Redirect, `www` CNAME)는
지운다. `www`는 등록하지 않았다 — 이 도메인은 API 주소로만 쓰이고 사용자가 보는 프론트는
Vercel 주소라 `www`로 들어올 일이 없다.

```bash
dig +short @8.8.8.8 insightstock.site A    # Elastic IP가 나오면 전파 완료 (실측: 등록 후 수 분 내)
```

**2) certbot 설치 및 발급**

```bash
sudo dnf install -y certbot python3-certbot-nginx

# 중요: 발급 전에 server_name을 도메인으로 바꿔둔다. 기존 설정은 `server_name _;`라서
# certbot의 nginx 플러그인이 인증서를 설치할 server 블록을 찾지 못한다.
sudo sed -i "s/server_name _;/server_name insightstock.site;/" /etc/nginx/conf.d/insightstock-backend.conf
sudo nginx -t && sudo systemctl reload nginx

sudo certbot --nginx -d insightstock.site --non-interactive --agree-tos -m <EMAIL> --redirect
```

보안 그룹의 443은 1절에서 이미 열어둔 상태라 추가 작업이 없었다.

**3) 결과 설정** — certbot이 14절의 upstream 블록은 건드리지 않고 server 블록만 고쳤다.
blue-green 전환 때 바꾸는 곳은 여전히 `upstream backend` 안의 포트 하나뿐이다.

```nginx
# /etc/nginx/conf.d/insightstock-backend.conf (2026-10-12 기준)
upstream backend {
    server 127.0.0.1:8082;
}

server {
    server_name insightstock.site;

    location / {
        proxy_pass http://backend;
        # ... proxy_set_header 4줄은 이전과 동일
    }

    listen 443 ssl; # managed by Certbot
    ssl_certificate /etc/letsencrypt/live/insightstock.site/fullchain.pem; # managed by Certbot
    ssl_certificate_key /etc/letsencrypt/live/insightstock.site/privkey.pem; # managed by Certbot
    include /etc/letsencrypt/options-ssl-nginx.conf; # managed by Certbot
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem; # managed by Certbot
}

server {
    if ($host = insightstock.site) {
        return 301 https://$host$request_uri;
    } # managed by Certbot

    listen 80 default_server;
    server_name insightstock.site;
    return 404; # managed by Certbot
}
```

TLS 적용 직전 설정은 EC2의 `/etc/nginx/insightstock-backend.conf.pre-tls-2026-10-12`에
백업해뒀다.

**4) 자동 갱신** — Amazon Linux 2023의 certbot 패키지는 `certbot-renew.timer`를 설치만 하고
**켜지는 않는다**(설치 직후 `disabled`/`inactive`였다). 직접 켜야 한다:

```bash
sudo systemctl enable --now certbot-renew.timer
sudo certbot renew --dry-run     # 갱신 리허설 - 실측 결과 success
```

인증서 만료일은 2027-01-10(90일). 타이머가 켜져 있어도 **인스턴스가 꺼져 있으면 갱신이
돌지 않는다** — 평소 꺼두는 운영 방침에서는, 오래 꺼뒀다 켠 직후 `sudo certbot renew`를
한 번 직접 실행해 만료 여부를 확인할 것. 갱신에는 80번 포트와 DNS가 살아 있어야 한다.

**5) 검증 (2026-10-12 실측)**

- `curl https://insightstock.site/actuator/health` → `{"status":"UP"}`
- `curl -I http://insightstock.site/actuator/health` → `301`, `Location: https://insightstock.site/...`
- 인증서: `CN=insightstock.site`, 발급자 Let's Encrypt, 2026-10-12 ~ 2027-01-10

> **주의 — IP로 직접 호출하면 이제 404다.** certbot이 80번 블록을 "도메인이면 301,
> 그 외는 404"로 바꿨기 때문에 `curl http://<IP>/actuator/health`는 더 이상 동작하지
> 않는다. 실제로 적용 직후, 당시 `http://<IP>`로 프록시하던 Vercel 프론트의 API 호출이
> 전부 404가 됐다(프론트를 도메인 직접 호출로 바꿔 해소 — 10절). 이 문서의 다른 절에 남은
> `http://<PUBLIC_IP>/...` 형태의 헬스체크는 `https://insightstock.site/...`로 읽을 것.

## 10. 프론트엔드(Vercel) 배포 — Vercel rewrite 프록시는 제거됨(도메인+TLS 적용 완료, 2026-10-12)

### 현재 구성 (2026-10-12~)

브라우저가 백엔드를 **HTTPS로 직접** 호출한다. 프록시는 없다.

- `frontend/next.config.ts`: `rewrites()` 제거(빈 설정).
- Vercel 환경변수(Production): `NEXT_PUBLIC_API_BASE_URL` = `https://insightstock.site`
  하나뿐. `BACKEND_ORIGIN`은 쓰는 곳이 없어져 삭제했다.

```bash
npx vercel env rm NEXT_PUBLIC_API_BASE_URL production --yes
printf 'https://insightstock.site' | npx vercel env add NEXT_PUBLIC_API_BASE_URL production
npx vercel env rm BACKEND_ORIGIN production --yes
npx vercel --prod --yes
```

검증(2026-10-12, Playwright로 프로덕션 URL에서 실제 브라우저 실행):

- 회원가입(`POST /auth/signup` 201) → 로그인(`POST /auth/login` 200) → 검색
  (`GET /search` 200), 그 사이 `auth/refresh`·`notifications/unread-count`까지 **모든 API
  호출의 origin이 `https://insightstock.site`** 였고, 실패한 요청과 콘솔 에러는 0건.
- 예전 프록시 경로(`https://<Vercel URL>/api/v1/...`)는 이제 404 — 프록시를 거치지 않는다는
  반대 방향 증거.
- 단, 검색 결과 자체는 0건이었다. 연결 문제가 아니라 EC2 DB에 청크가 0건(ES 인덱스도
  비어 있음)이기 때문 — `docs/progress.md` "다음 세션 논의거리" 참고.

아래는 프록시를 쓰게 됐던 배경과 당시 구성의 기록이다(지금은 적용되지 않는다).

### 배포 절차

```bash
cd frontend
npx vercel link      # 최초 1회, 프로젝트 연결 (브라우저 로그인 필요)
npx vercel --prod    # 프로덕션 배포
```

Vercel CLI는 전역 설치 없이 `npx vercel`로 바로 쓸 수 있다. `vercel link`는 대화형
브라우저 OAuth 로그인이 필요하므로 사람이 한 번은 개입해야 한다.

### 발견한 문제: Mixed Content로 인한 API 호출 전면 차단

당초 계획은 "프론트(Vercel, HTTPS) → 백엔드(EC2 IP, HTTP)"를 `NEXT_PUBLIC_API_BASE_URL`에
백엔드의 `http://<IP>` 주소를 그대로 넣어 직접 호출하는 것이었다. 그런데 실제 배포 후
브라우저(Chrome)로 검증하니 **회원가입/로그인 등 모든 API 호출이 `TypeError: Failed to
fetch`로 실패**했다 — curl로는 같은 요청이 정상 응답(201)했는데 브라우저에서만 실패.

원인은 **Mixed Content 차단**이다: HTTPS 페이지(Vercel)에서 HTTP 리소스로의 능동적 요청
(`fetch`/`XHR`)은 브라우저가 CORS 설정과 무관하게 무조건 차단한다 — 실제로 요청이
네트워크로 나가기 전에 막혀서 CORS 프리플라이트(OPTIONS)조차 발생하지 않았다. 이건
Vercel이 HTTPS를 강제하는 이상 피할 수 없는 문제이고, "지금은 HTTP만, TLS는 시연 직전
도메인 구매 후"라는 원래 계획으로는 실제 방문자 브라우저에서 절대 동작하지 않는다.

### (과거) 임시 해결: Next.js rewrite를 이용한 서버사이드 프록시 — 2026-07-28 ~ 2026-10-12

브라우저가 아니라 **Vercel 서버 자신이 백엔드를 호출**하도록 바꿨다 — 브라우저 입장에서는
항상 같은 origin(HTTPS)만 보고, "브라우저 → HTTP 백엔드" 구간 자체가 사라진다.

`frontend/next.config.ts`:

```ts
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://<현재 EC2 IP>";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      { source: "/api/v1/:path*", destination: `${BACKEND_ORIGIN}/api/v1/:path*` },
    ];
  },
};
```

Vercel 환경변수:

- `NEXT_PUBLIC_API_BASE_URL` = Vercel 프로덕션 URL 자신
  (예: `https://frontend-steel-omega-0zye89ut4w.vercel.app`) — `frontend/lib/api.ts`가
  `${API_BASE_URL}/api/v1/...` 형태로 호출하므로, 이 값을 프론트 자신의 주소로 두면
  결과적으로 같은 origin(`/api/v1/...`)으로 요청이 나가고 위 rewrite가 이걸 가로채
  EC2로 프록시한다.
- `BACKEND_ORIGIN` (일반 환경변수, `NEXT_PUBLIC_` 아님 — 클라이언트 번들에 노출되지
  않고 Vercel 서버에서만 쓰임) = EC2의 Elastic IP. 2026-10-12부터 IP가 고정이라 평소에는
  갱신할 일이 없다 — IP가 실제로 바뀌는 예외 상황에서만 갱신 후 재배포한다(12절 참고).

```bash
npx vercel env rm NEXT_PUBLIC_API_BASE_URL production --yes
echo "https://<Vercel 프로덕션 URL>" | npx vercel env add NEXT_PUBLIC_API_BASE_URL production
echo "http://<EC2 IP>" | npx vercel env add BACKEND_ORIGIN production
npx vercel --prod
```

**타임아웃 관련 확인**: rewrite의 destination이 외부 URL이면 Vercel Serverless Function을
경유하게 되어 실행 시간 제한(Hobby 플랜 기본 10초)이 걸린다. 실측 결과 `/answers`(하이브리드
검색 + LLM 호출)는 웜업 상태 0.4~0.8초, 콜드스타트(백엔드 재시작 직후 첫 호출)도 4.1초로
10초에 충분히 여유가 있어 문제없음을 확인했다(2026-07-28 실측).

**도메인+TLS 적용으로 이 우회는 제거됐다(2026-10-12)**: 계획대로 `next.config.ts`의
`rewrites()`를 지우고 `NEXT_PUBLIC_API_BASE_URL`을 `https://insightstock.site`로 바꿨다.
진짜 HTTPS끼리 통신이라 Mixed Content 문제 자체가 없다. 프록시가 사라지면서 Vercel
Serverless Function의 실행 시간 제한(위 "타임아웃 관련 확인")도 더 이상 API 호출에 걸리지
않는다.

## 11. CORS 설정

`cors.allowed-origins`는 `application.yml`에서 `${CORS_ALLOWED_ORIGINS:http://localhost:3000}`
로 외부화되어 있다 — `.env`(로컬은 미설정 시 localhost:3000 기본값, 배포 EC2는
`.env`에 `CORS_ALLOWED_ORIGINS=https://<Vercel 프로덕션 URL>` 추가) 로 값을 넣는다.

**2026-10-12부터는 실제로 CORS 프리플라이트가 발생하므로 이 값이 검증 경로에 실제로
관여한다.** 프론트(`https://<Vercel URL>`)와 백엔드(`https://insightstock.site`)의 origin이
다르기 때문에, 브라우저는 `Content-Type: application/json`이나 `Authorization` 헤더가 붙은
요청 전에 `OPTIONS` 프리플라이트를 보내고, 백엔드가 이 origin을 허용해야만 본 요청이
나간다. (그 전까지는 Vercel rewrite 프록시 덕에 브라우저가 같은 origin으로만 호출해서
프리플라이트 자체가 없었다.)

따라서 **Vercel 프로덕션 URL이 바뀌거나 커스텀 프론트 도메인을 붙이면 EC2 `.env`의
`CORS_ALLOWED_ORIGINS`를 반드시 같이 고치고 백엔드를 재시작**해야 한다 — 안 그러면
curl로는 멀쩡한데 브라우저에서만 전부 실패한다. 새 HTTP 메서드를 쓰는 API를 추가할 때
`WebConfig`의 허용 메서드 갱신을 빠뜨리는 문제(progress.md 참고)도 이제 프로덕션에서
바로 드러난다.

실측(2026-10-12, EC2 `.env` 값은 Vercel 프로덕션 URL 그대로였고 수정 불필요):

```bash
curl -s -o /dev/null -D - -X OPTIONS https://insightstock.site/api/v1/auth/login \
  -H "Origin: https://<Vercel 프로덕션 URL>" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type,authorization"
# → 200, Access-Control-Allow-Origin: <그 origin>, Allow-Methods: GET,POST,DELETE,PATCH
#   (GET/PATCH/DELETE로 바꿔도 동일)
# 허용하지 않은 origin(예: https://evil.example.com)으로 보내면 → 403, 허용 헤더 없음
```

## 12. Elastic IP — 고정 IP 적용 (2026-10-12 적용)

> **같은 날 이어서 도메인+TLS를 적용했다(9절).** 그 뒤로 Vercel은 IP가 아니라
> `https://insightstock.site`를 가리키고 `BACKEND_ORIGIN` 환경변수는 삭제됐다. 아래에서
> `BACKEND_ORIGIN`을 다루는 부분은 Elastic IP 적용 시점의 기록이다. 지금 Elastic IP의
> 역할은 "DNS A 레코드가 가리키는 주소를 고정하는 것"이고, **IP가 실제로 바뀌게 되면
> 할 일은 Vercel 재배포가 아니라 Namecheap의 A 레코드 수정**이다.

**Elastic IP 적용 이후로는 IP가 고정되므로, 인스턴스를 껐다 켤 때마다 Vercel의
`BACKEND_ORIGIN`을 갱신하고 재배포하던 절차는 더 이상 필요 없다.**

### 왜 적용했나

Elastic IP가 없으면 인스턴스를 stop → start할 때마다 AWS가 퍼블릭 IP를 새로 배정한다.
실제로 재기동할 때마다 IP가 매번 달라졌고(2026-10-12에도 기동하자마자 또 새 IP가 붙었다),
그때마다 Vercel 환경변수 갱신 + `vercel --prod` 재배포가 필요했다. Elastic IP는 계정에
할당된 고정 주소라 인스턴스 상태와 무관하게 같은 값이 유지된다.

### 적용 절차 (실제로 실행한 순서)

```bash
# 1. Elastic IP는 running 인스턴스에 연결하므로 먼저 기동
aws ec2 start-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 wait instance-running --region ap-northeast-2 --instance-ids <INSTANCE_ID>

# 2. 할당 (Name 태그를 붙여 나중에 찾기 쉽게 한다)
aws ec2 allocate-address --region ap-northeast-2 --domain vpc \
  --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=insightstock-eip}]'
# → AllocationId, PublicIp 기록

# 3. 인스턴스에 연결
aws ec2 associate-address --region ap-northeast-2 \
  --instance-id <INSTANCE_ID> --allocation-id <ALLOCATION_ID>

# 4. 연결 확인 - InstanceId와 PublicIp가 기대값과 일치하는지
aws ec2 describe-addresses --region ap-northeast-2 --allocation-ids <ALLOCATION_ID>

# 5. Vercel의 BACKEND_ORIGIN을 Elastic IP로 교체하고 재배포 (이번이 마지막 갱신)
cd frontend
npx vercel env rm BACKEND_ORIGIN production --yes
printf 'http://<ELASTIC_IP>' | npx vercel env add BACKEND_ORIGIN production
npx vercel --prod --yes
```

현재 Elastic IP 값은 문서에 적지 않는다. 필요하면 아래로 조회한다:

```bash
aws ec2 describe-addresses --region ap-northeast-2 \
  --filters Name=tag:Name,Values=insightstock-eip \
  --query 'Addresses[0].[PublicIp,AllocationId,InstanceId]' --output text
```

### 검증 결과 (2026-10-12, 실측)

- 연결 직후 `describe-addresses`의 `InstanceId`가 대상 인스턴스와 일치, 인스턴스의
  `PublicIpAddress`도 기동 시 받았던 임시 IP에서 Elastic IP로 바뀜.
- `curl http://<ELASTIC_IP>/actuator/health` → `{"status":"UP"}` (80번, Nginx 경유).
- Vercel 프로덕션 URL의 `/api/v1/auth/login`(잘못된 계정)이 백엔드의 에러 JSON
  (`401`, `INVALID_CREDENTIALS`)을 반환했고, 같은 시각 EC2 Nginx access log에 Vercel 서버
  IP에서 온 동일 요청이 기록됨 — Vercel → Elastic IP → Nginx → 백엔드 경로가 끝까지 확인됨.
- **stop → start 후에도 IP 동일**: 정지 전 / `stopped` 상태 / 재기동 후 세 시점의
  `PublicIpAddress`가 전부 같은 값이었고, `stopped` 상태에서도 연결(`AssociationId`)이
  유지됐다.
- **재기동 후 Vercel을 전혀 건드리지 않고**(환경변수 갱신·재배포 없음) 같은 Vercel URL
  호출이 다시 정상 응답함 — 이것이 Elastic IP를 적용한 목적 그 자체다.
- 보안 그룹은 변경하지 않았다: 22(내 IP)/80/443만 열려 있고 5432/9200은 외부에서 닫힌 상태
  그대로임을 작업 전후로 확인.

### 비용과 트레이드오프

AWS는 2024-02-01부터 **모든 퍼블릭 IPv4 주소에 시간당 $0.005를 과금**한다. Elastic IP는
인스턴스가 running이든 stopped든, 심지어 어디에도 연결돼 있지 않아도 **보유하는 동안 계속**
부과된다 — 한 달 내내 보유하면 약 $3.65(730시간 × $0.005).

("running 인스턴스에 연결된 Elastic IP는 무료"라는 설명은 2024-02 이전 정책이다. 지금은
해당하지 않는다.)

적용 전후 비교:

| 인스턴스 상태 | Elastic IP 없음 (이전) | Elastic IP 있음 (현재) |
|---|---|---|
| running | 임시 퍼블릭 IP에 $0.005/시간 | Elastic IP에 $0.005/시간 (동일) |
| stopped | 퍼블릭 IP가 반환되어 **$0** | Elastic IP에 **$0.005/시간** |

즉 켜져 있는 동안은 차이가 없고, **꺼둔 시간만큼이 순수하게 늘어난 비용**이다. 이 프로젝트는
"평소엔 인스턴스를 꺼둔다"가 운영 방침이라 대부분의 시간이 `stopped`이고, 따라서 이 비용의
거의 전부(월 약 $3.6)가 새로 생긴 고정 지출이다. 비용 절감을 위해 꺼두는 방침과 정면으로
상충하는 지점이다.

그럼에도 적용한 이유: 켤 때마다 반복하던 "IP 조회 → Vercel 환경변수 교체 → 재배포 → 재검증"
수작업이 사라지고, 시연(2026-11) 당일 이 단계에서 실수할 여지가 없어진다. 월 $3.6 수준이면
그 값어치가 있다고 판단했다.

되돌리고 싶을 때(장기간 안 쓸 때):

```bash
aws ec2 disassociate-address --region ap-northeast-2 --association-id <ASSOCIATION_ID>
aws ec2 release-address --region ap-northeast-2 --allocation-id <ALLOCATION_ID>
```

해제하면 같은 IP를 다시 받을 수 없고, 이후로는 아래 "IP가 실제로 바뀌는 경우" 절차가 다시
필요해진다. 요금은 변동될 수 있으니 실제 청구액은 AWS Billing에서 확인할 것.

### IP가 실제로 바뀌는 경우에만 (Elastic IP 해제, 인스턴스 재생성 등)

1. Vercel의 `BACKEND_ORIGIN` 환경변수를 새 IP로 갱신 → `npx vercel --prod`로 재배포
   (`frontend/next.config.ts`의 기본값은 환경변수가 없을 때만 쓰이는 fallback이다)
2. EC2 쪽 `.env`의 `CORS_ALLOWED_ORIGINS`는 프론트엔드 주소가 바뀌지 않는 한 그대로 둬도 됨
   (Vercel 프로덕션 URL은 고정 alias라 IP가 바뀌어도 안 변함)
3. 로컬 개발용 `frontend/.env.local`의 `NEXT_PUBLIC_API_BASE_URL`은 이 배포 절차와 무관
   (로컬 개발은 `http://localhost:8080`을 그대로 씀)

## 13. ShedLock — 무중단 배포 중 스케줄러 중복 실행 방지 (2026-07-31 적용)

4단계 알림 설계 때 "무중단 배포로 인스턴스가 잠깐 2개 뜨면 같은 `@Scheduled` 배치가 양쪽에서
동시 실행될 수 있다"는 우려가 있었다(그래서 알림 감지 스케줄러를 처음부터 별도 클래스로
분리해뒀다). `net.javacrumbs.shedlock`(spring 통합, `shedlock-spring` +
`shedlock-provider-jdbc-template`, 7.7.0)으로 실제 해결했다. 이미 쓰고 있는 Postgres를
락 저장소로 재사용해 새 인프라(Redis 등)를 추가하지 않았다 — Flyway V7이 공식 스키마
그대로 `shedlock` 테이블을 만든다.

`@SchedulerLock`은 다음 4개 스케줄러에 붙였다(공시 수집/원문 수집/색인/알림 감지 — 전부
`fixed-delay-ms: 600000`, 즉 10분 간격으로 동일하다는 걸 `application.yml`에서 실제로
확인한 뒤 값을 정했다):

- `lockAtMostFor = "PT15M"` — fixed-delay(10분)보다 넉넉하게 15분. 인스턴스가 크래시해
  락을 못 놓아도 15분 뒤엔 다른 인스턴스가 다시 시도할 수 있게 하는 안전장치.
- `lockAtLeastFor = "PT5S"` — 0이 아니라 5초. blue-green 전환 중인 두 인스턴스 간 시계가
  살짝 어긋나도 락 해제 직후 즉시 재실행되는 걸 막기 위함.

청킹/임베딩/corpCode 동기화 스케줄러는 현재 `application.yml`에서 비활성화 상태라 이번
범위에서 제외했다(나중에 활성화할 때 `@SchedulerLock` 추가를 빠뜨리지 말 것 —
`docs/progress.md` "다음 세션 논의거리" 참고).

## 14. Nginx를 upstream 블록 구조로 변경

9절의 `proxy_pass http://127.0.0.1:8080;` 고정 방식은 포트를 바꾸려면 파일을 통째로
고쳐야 했다. blue-green 전환(포트만 바꿔서 트래픽을 옮기는 것)을 위해 `upstream` 블록으로
바꿨다:

```nginx
# /etc/nginx/conf.d/insightstock-backend.conf
upstream backend {
    server 127.0.0.1:8082;   # 현재 라이브 포트 - blue-green 전환마다 바뀐다
}

server {
    listen 80 default_server;
    server_name _;

    location / {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

> **주의**: 이 세션 이후 "라이브 포트"는 더 이상 8080이 아니다. 현재 EC2의
> `/etc/nginx/conf.d/insightstock-backend.conf`를 먼저 확인해 `upstream backend`가
> 가리키는 포트를 확인한 뒤 그 포트로 jar를 띄울 것 (4절의 "항상 8080" 가정은 이 절
> 적용 이후로는 유효하지 않다).

## 15. Blue-Green 수동 절차 (검증된 명령어 순서)

이번 세션엔 자동화 스크립트 없이 손으로 한 단계씩 실행하며 각 단계 결과를 확인했다.
아래는 실제로 실행해서 검증된 순서(포트는 8081→8082 전환 기준, 다음 번엔 8082→8081이 됨):

```bash
# 0. 로컬에서 최신 코드로 jar 빌드
cd backend && ./gradlew build -x test

# 1. 새 jar를 EC2로 전송 (기존 app.jar를 덮어쓰지 않고 별도 이름으로)
scp -i "<PEM_PATH>" backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar \
  ec2-user@<PUBLIC_IP>:~/insight-stock/app-v2.jar

# 2. 새 버전을 새 포트(예: 8082)로 기동 - SERVER_PORT 환경변수로 포트 지정(하드코딩 금지)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "cd ~/insight-stock && SERVER_PORT=8082 nohup java -jar app-v2.jar > app-8082.log 2>&1 < /dev/null & disown"

# 3. 헬스체크 통과를 반드시 먼저 확인 (여기서 실패하면 4번으로 넘어가지 않는다)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "curl -s http://localhost:8082/actuator/health"
# {"status":"UP"} 확인 후에만 다음 단계로

# 4. Nginx upstream을 새 포트로 바꾸고 reload (재시작 아님 - 이게 무중단의 핵심)
#    conf.d/insightstock-backend.conf의 upstream 블록 안 포트만 8082로 수정한 뒤:
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "sudo nginx -t && sudo nginx -s reload"

# 5. 외부에서 전환이 실제로 반영됐는지, 여전히 무중단인지 확인
curl -s http://<PUBLIC_IP>/actuator/health

# 6. 기존(8081) 프로세스 종료
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "pkill -f 'SERVER_PORT=8081'"   # 또는 ps로 PID 확인 후 kill

# 7. 정리 - 새 버전을 정식 app.jar로 승격 (다음 세션이 헷갈리지 않도록)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "cd ~/insight-stock && rm -f app.jar && mv app-v2.jar app.jar"
```

## 16. 검증 결과 (2026-07-31, 실측)

### 무중단 확인

전환 순간(12:22:00 KST) 전후로 로컬에서 0.5초 간격 헬스체크 curl 루프를 돌렸다.
t3.medium으로 리사이즈한 이후 세션 전체(약 3분 20초, 400회 요청 - 실제 8082 기동, nginx
reload 전환 순간, 이후 안정화 구간까지 전부 포함)에서 **400/400 전부 200**, 최대 응답
시간 0.86초(GC 등에 의한 정상 범위 편차, 타임아웃/502 없음). `nginx -s reload`가 실제로
무중단이라는 걸 실측으로 확인했다.

### ShedLock 중복 실행 방지 확인

8081/8082가 동시에 떠 있는 구간에서 fixed-delay 주기가 최소 1번은 겹치도록, 트래픽을 더
이상 받지 않는 8081만 별도로 짧은 주기(`--dart.document-collector.fixed-delay-ms=20000`
등, 명령줄 인자로만 임시 적용 - `application.yml`은 건드리지 않음)로 재시작해 8082(운영
설정 그대로, 건드리지 않음)의 자연스러운 다음 주기와 겹치게 만들었다. 실제 로그:

```
# 8082 (정상 10분 주기) - documentCollector 락 획득
2026-07-31T03:31:22.907Z DEBUG ... Locked 'documentCollector', lock will be held at most until 2026-07-31T03:46:22.904Z
2026-07-31T03:31:22.912Z  INFO ... c.i.i.c.DocumentCollectorService : 처리할 PENDING 공시가 없습니다.

# 8081 (20초 주기) - 같은 락을 시도했다가 명시적으로 거부됨
2026-07-31T03:31:26.023Z DEBUG ... Not executing 'documentCollector'. It's locked.
2026-07-31T03:31:26.028Z DEBUG ... Not executing 'indexing'. It's locked.

# 8082가 5초 뒤 정상적으로 락 반납
2026-07-31T03:31:27.904Z DEBUG ... Task finished, lock 'documentCollector' will be released at 2026-07-31T03:31:27.904Z
```

ShedLock 없이 두 프로세스가 각자 스케줄러를 돌렸다면 이 구간에서 `documentCollector`가
동시에 두 번 실행돼 같은 PENDING 공시를 경합 처리했을 것 - 실제로 한쪽만 실행되고
다른 쪽은 명시적으로 스킵됐음을 로그로 확인했다.

## 17. 예상치 못한 발견 — t3.small에서 blue-green 시도 시 메모리 부족으로 SSH까지 무응답

3단계(8082 기동) 도중 EC2가 **SSH 배너 교환 단계에서부터 타임아웃**되는 상황이 발생했다
(AWS `describe-instance-status`는 시스템/인스턴스 체크 모두 `ok`였는데도 SSH·HTTP(nginx
80번 포트 포함) 전부 무응답 - 로컬 헬스체크 curl 루프가 12:06:03 KST부터 계속 `000`
타임아웃을 기록했다). t3.small은 RAM 2GB인데, JVM 2개(각 500MB+ RSS) + Postgres +
Elasticsearch(보통 1GB+ 요구)를 동시에 띄우면 요구 메모리가 물리 메모리를 넘어서고,
t3.small엔 스왑도 없어서(`Swap: 0B`) 극심한 메모리 압박이 sshd 응답조차 막을 정도로
번진 것으로 보인다 — `free -h`로 정확한 수치를 직접 확인하지는 못했다(SSH 자체가
안 됐기 때문 - 이 사실 자체가 증거).

**대응**: `aws ec2 stop-instances --force`로 AWS 쪽에서 강제 종료 → `modify-instance-attribute`
로 `t3.medium`(4GB)으로 변경 → 재기동. t3.medium에서는 8081+8082 동시 실행 중에도
`free -h` 기준 `available` 1.4~1.9Gi를 유지했고, SSH/HTTP 모두 즉시 응답했다.

**결론**: `docs/progress.md`에 있던 "시연(11월) 때는 t3.medium으로 리사이즈해서 크게 켤
예정"이라는 기존 결정이 추측이 아니라 실측으로 뒷받침됐다 - **blue-green(두 인스턴스
동시 실행)을 실제로 쓰려면 t3.small로는 부족하고 t3.medium 이상이 필요하다**는 게 이번
세션에서 확정된 사실이다. 평소(단일 인스턴스만 실행) 운영에는 t3.small로 계속 충분하다.

> **2026-10-12 갱신**: 위 마지막 문장은 EC2 DB가 사실상 비어 있던 시점의 결론이다. 로컬
> 데이터(청크 173,035건 + 1.1GB HNSW 인덱스)를 옮긴 뒤로는 단일 인스턴스 운영에서도
> t3.small이 첫 검색 직후 무응답이 됐다 — 19절 참고.

## 18. 단순 재배포 절차와 재배포 기록

blue-green(15절)은 JVM 2개가 동시에 떠야 해서 t3.medium이 필요하다(17절). 평소 t3.small
에서는 "내리고 → 교체하고 → 올리는" 단순 재배포를 쓴다. 수십 초의 중단이 생긴다
(2026-10-12 실측: 종료부터 헬스체크 통과까지 약 30초, 그 사이 502).

```bash
# 0. 로컬에서 최신 main으로 빌드
cd backend && ./gradlew clean build -x test

# 1. 실행 중인 app.jar를 덮어쓰지 않도록 별도 이름으로 전송하고 체크섬을 대조
scp -i "<PEM_PATH>" backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar \
  ec2-user@<PUBLIC_IP>:~/insight-stock/app-new.jar
shasum -a 256 backend/build/libs/insight-stock-0.0.1-SNAPSHOT.jar          # 로컬
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "sha256sum ~/insight-stock/app-new.jar"   # EC2

# 2. 라이브 포트(14절)와 Postgres/ES healthy 여부(7절 주의 1)를 먼저 확인
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "grep -o 'server 127.0.0.1:[0-9]*' /etc/nginx/conf.d/insightstock-backend.conf; \
   docker inspect --format='{{.State.Health.Status}}' insight-stock-postgres insight-stock-elasticsearch"

# 3. 기존 프로세스 종료 → 이전 jar는 롤백용으로 남기고 교체 → 같은 포트로 기동
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "ps -eo pid,args | grep '[j]ava -jar'"   # PID 확인
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "kill <PID>"
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "cd ~/insight-stock && mv app.jar app-prev.jar && mv app-new.jar app.jar && \
   (SERVER_PORT=8082 nohup java -jar app.jar > app.log 2>&1 < /dev/null &)"

# 4. 검증
curl https://insightstock.site/actuator/health     # {"status":"UP"}
```

롤백: 새 프로세스를 내리고 `mv app.jar app-bad.jar && mv app-prev.jar app.jar` 후 3번의
기동 명령을 다시 실행한다.

### 재배포 기록

- **2026-10-12 재배포 완료** (커밋 `b8f36a3` 기준 빌드, 이전 배포본은 2026-07-31 빌드).
  포함된 변경사항: 검색 스레드풀/HikariCP 병목 수정(`193e7c5`), `OpenAiChatCompletionClient`
  429 재시도/백오프 + connect/read 타임아웃, `GlobalExceptionHandler` 429/502 응답 분리
  (`fe66bc3`), `OpenAiEmbeddingClient` 단일/배치 경로 분리 타임아웃(`622b762`).
  검증: Flyway 7개 마이그레이션 검증 통과, 헬스체크 `UP`, `/search`·`/answers` 200,
  CORS 프리플라이트 200, 기동 이후 로그에 WARN/ERROR 없음. 새 설정 키
  (`rag.chat.rate-limit.*`, `rag.chat.api.*-timeout-ms`, `embedding.api.*`)는 실행 중인
  jar의 `application.yml`에 들어 있고 EC2 `.env`가 덮어쓰지 않음을 확인했다(이 키들은
  기본값 없는 `@Value`라 값이 없으면 기동 자체가 실패한다 — Spring은 프로퍼티 값을
  로그로 남기지 않아 이것이 로딩의 증거다). **한계**: EC2 DB에 청크가 0건이라 `/search`는
  결과 0건, `/answers`는 "검색 결과 없음 - LLM 호출 없이 응답"으로 끝났다 — 질의 임베딩
  경로(새 타임아웃 클라이언트)는 실제로 호출돼 정상 동작했지만, 채팅 완성 경로(재시도/
  타임아웃/429 분리)는 배포 환경에서 한 번도 실행되지 않았다. 이전 jar는 EC2의
  `~/insight-stock/app-prev-0731.jar`에 남겨뒀다.

## 19. 로컬 데이터 → EC2 이전과 ES 재색인 (2026-10-12 실행)

EC2 DB에 청크가 0건이라 배포 환경의 검색/답변이 항상 빈 결과였다(18절 "한계"). 로컬의
`companies`/`disclosures`/`chunks`(임베딩 포함)를 EC2로 옮기고 Elasticsearch를 재색인했다.
임베딩을 그대로 옮기므로 **OpenAI 호출은 0건**이다(검증용 검색의 질의 임베딩만 발생).

### 결정과 근거

- **세 테이블을 id를 보존한 채 통째로 교체한다.** `chunks.disclosure_id` →
  `disclosures.company_id` FK로 묶여 있고 로컬과 EC2의 id가 서로 다르며, ES 문서 id도
  chunk id를 그대로 쓴다(`IndexingService.toDocument`). chunks만 옮길 수는 없다.
  `users`는 건드리지 않고, `watchlist`는 `corp_code` 기준으로 새 company id에 재매핑했다.
  EC2가 자체 수집한 disclosures(청크 없음)는 폐기했다.
- **데이터만 덤프한다**(`--data-only`). 스키마는 양쪽 다 Flyway V7로 같으므로 Flyway 이력을
  건드릴 이유가 없다.
- **HNSW 인덱스는 지우고 적재한 뒤 다시 만든다.** 인덱스가 있는 채로 17만 건을 넣으면 건마다
  그래프를 갱신해 훨씬 느리다. 로컬(arm64)과 EC2(x86_64)의 아키텍처가 달라 pgdata 볼륨을
  통째로 복사하는 방식은 쓸 수 없다.
- **재색인은 앱의 색인 경로를 그대로 쓴다.** `INDEXED` 공시를 `CHUNKED`로 되돌리면
  `IndexingService`가 다시 색인한다 — Nori 매핑이 로컬과 같다는 것이 보장되고 새 도구(ES
  스냅샷, elasticdump)가 필요 없다. 기본 설정(10분마다 20건)으로는 약 31시간이라 명령줄
  인자로만 배치/주기를 임시로 키웠다(`application.yml`은 수정하지 않음).

### 사전 실측

| 항목 | 로컬 | EC2 (이전 전) |
|---|---|---|
| companies / disclosures / chunks | 118,537 / 5,147 / 173,035 | 401 / 785 / 0 |
| DB 크기 | 2,941MB (chunks 2,828MB, 그중 HNSW 1,135MB) | 16MB |
| ES `chunks` 인덱스 | 약 200MB | 0건 |
| 디스크 | - | 8GB 중 여유 3.8GB |

복원 후 DB 2.9GB + 복원 중 WAL(최대 약 1GB) + ES + 덤프 파일 1.3GB ≈ 5.5~6GB가 필요해
8GB 디스크로는 불가능했다 → **EBS를 20GB로 확장**(gp3, 월 약 $1.1 추가, 축소 불가,
인스턴스를 꺼둬도 과금). Amazon Linux 2023은 재부팅 시 파티션/파일시스템을 자동으로
늘려줘서 `growpart`/`xfs_growfs`를 직접 실행할 필요가 없었다.

### 절차 (실제로 실행한 순서)

```bash
# 0. t3.medium으로 승격 + EBS 확장 (정지 상태에서) → 재기동. 백엔드 jar는 띄우지 않는다.
aws ec2 modify-instance-attribute --region ap-northeast-2 --instance-id <INSTANCE_ID> \
  --instance-type '{"Value":"t3.medium"}'
aws ec2 modify-volume --region ap-northeast-2 --volume-id <VOLUME_ID> --size 20

# 0-1. EC2 현재 DB 백업 (롤백용) - ~/insight-stock/backup/ec2-pre-migration-2026-10-12.dump
docker exec insight-stock-postgres pg_dump -U <USER> -d insight_stock -Fc > ec2-pre-migration-2026-10-12.dump

# 1. 로컬에서 덤프 → 전송 → 체크섬 대조
docker exec insight-stock-postgres pg_dump -U <USER> -d insight_stock -Fc --data-only \
  -t companies -t disclosures -t chunks > local-data.dump
scp -i "<PEM_PATH>" local-data.dump ec2-user@<PUBLIC_IP>:~/insight-stock/backup/
```

2단계(복원, 백엔드가 내려가 있는 상태에서 EC2의 psql로 실행):

```sql
-- 2-1. watchlist를 corp_code와 함께 대피시키고, HNSW를 지우고, 비운다 (한 트랜잭션)
BEGIN;
CREATE TABLE _watchlist_migration AS
  SELECT w.id, w.user_id, c.corp_code, w.created_at
  FROM watchlist w JOIN companies c ON c.id = w.company_id;
DROP INDEX idx_chunks_embedding_hnsw;
TRUNCATE chunks, disclosures, companies, watchlist, notification;  -- notification은 0건임을 먼저 확인
COMMIT;

-- 2-2. (셸) pg_restore --data-only --exit-on-error -d insight_stock < local-data.dump
--      owned 시퀀스의 SEQUENCE SET이 덤프에 포함돼 있어 시퀀스 값도 같이 맞춰진다.

-- 2-3. HNSW 재생성 - DDL은 V1과 동일해야 한다
SET maintenance_work_mem = '1500MB';
SET max_parallel_maintenance_workers = 0;  -- 컨테이너 /dev/shm(64MB)로는 병렬 빌드가 실패할 수 있다
CREATE INDEX idx_chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops);

-- 2-4. watchlist 재매핑, 통계 갱신
INSERT INTO watchlist (id, user_id, company_id, created_at)
  SELECT m.id, m.user_id, c.id, m.created_at
  FROM _watchlist_migration m JOIN companies c USING (corp_code);
DROP TABLE _watchlist_migration;
ANALYZE companies; ANALYZE disclosures; ANALYZE chunks; ANALYZE watchlist;
```

3단계(재색인):

```bash
# INDEXED → CHUNKED로 되돌린 뒤, 배치/주기를 임시로 키워 기동
#   UPDATE disclosures SET document_status='CHUNKED' WHERE document_status='INDEXED';
(SERVER_PORT=8082 nohup java -jar app.jar \
  --indexing.collector.batch-size=4000 --indexing.collector.fixed-delay-ms=60000 \
  > app.log 2>&1 < /dev/null &)
# CHUNKED가 0건이 되면 프로세스를 내리고 인자 없이(기본 설정) 다시 띄운다
```

### 결과 (실측)

| 단계 | 소요 | 비고 |
|---|---|---|
| 로컬 덤프 | 2분 6초 | 1.30GB (custom format) |
| 전송 | 29초 | sha256 일치 |
| 복원 전체 | 5분 10초 (15:46:11~15:51:21 KST) | 적재 1분 46초 + HNSW 2분 59초 + 나머지 |
| ES 재색인 | 약 8분 | 3,715건 전부 성공(실패 0), ES 문서 173,035건, 209MB |

- 건수: companies 118,537 / disclosures 5,147 / chunks 173,035 — 로컬과 일치. 임베딩 NULL 0건.
  청크 전체(id, disclosure_id, chunk_index, content)를 이어 붙인 md5도 로컬과 일치.
- `users` 3명 그대로, `watchlist` 1건은 같은 회사(`corp_code` 기준)의 새 company id로 재매핑.
- HNSW 인덱스 1,134MB, DB 2,879MB. 디스크는 20GB 중 8.3GB 사용.
- `EXPLAIN (ANALYZE)`: `Index Scan using idx_chunks_embedding_hnsw on chunks`, 304ms(캐시가
  비어 있던 첫 실행, shared read=550).
- **t3.medium**에서 `https://insightstock.site` 기준: `/search` 첫 호출 5.3초, 이후
  1.0~2.5초(전부 200, 10건). `/answers` 2.3~3.1초(200, 출처 포함). 배포 환경에서 채팅 완성
  경로(재시도/타임아웃, 18절)가 처음으로 실제 실행됐다.

### 발견 — t3.small은 이 데이터를 올린 상태로 검색을 감당하지 못한다

같은 날 두 번 인스턴스가 무응답(SSH 배너 교환 타임아웃, HTTP 전부 타임아웃, AWS 상태 체크는
ok — 17절과 같은 증상)이 됐다.

1. **이전 전, t3.small에서 백업용 `pg_dump` 실행 직후.** 당시 `free`의 available이 31MB였다
   (Postgres + Elasticsearch + JVM 1개만으로 이미 한계). 16MB짜리 DB를 덤프하는 것만으로
   넘어갔다. 정상 정지 요청은 4분 넘게 `stopping`에 머문 뒤 멈췄다.
2. **이전 후, t3.small로 되돌린 직후.** 백엔드 기동 직후 available 61MB. 첫 `/search`
   1건은 4.3초에 성공했지만 그 뒤의 모든 요청이 타임아웃됐고, 강제 정지로 복구했다.

스왑이 없어(`Swap: 0B`) 메모리가 모자라면 느려지는 게 아니라 통째로 멈춘다. 원인 분해
(1.1GB HNSW 인덱스의 페이지 캐시 요구, 같은 날 배포된 Hikari 풀 50/검색 스레드 100의
영향 등)는 **측정하지 못했다 — 추정이다.** 확정된 사실은 "t3.medium에서는 정상, t3.small에서는
첫 검색 후 무응답"뿐이다. 17절의 "평소 단일 인스턴스 운영에는 t3.small로 충분하다"는 결론은
이 데이터 이전 이후로는 유효하지 않다.

**대응(같은 날 결정)**: 기본은 t3.small로 정지해 두고, 실제로 쓸 때만 t3.medium으로 올린다.
t3.small에서는 백엔드를 띄우지 않는다.

```bash
# 켤 때 (정지 상태에서)
aws ec2 modify-instance-attribute --region ap-northeast-2 --instance-id <INSTANCE_ID> \
  --instance-type '{"Value":"t3.medium"}'
# → 이후 7절(재기동) 그대로

# 끌 때
aws ec2 stop-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 wait instance-stopped --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 modify-instance-attribute --region ap-northeast-2 --instance-id <INSTANCE_ID> \
  --instance-type '{"Value":"t3.small"}'
```

이 결정을 적용해 t3.medium으로 다시 켠 뒤 재확인했다(16:29 KST): companies 118,537 /
chunks 173,035 / 임베딩 NULL 0건 / ES 173,035건, `/search` 200(3.1초, 10건), `/answers`
200(3.3초, 출처 포함), 여유 메모리 1.7GB. disclosures는 수집 스케줄러가 돌아 5,479건으로
늘었다(정상 동작). t3.small에 스왑을 추가하면 버티는지는 검증하지 않았다.

### 작업 중 얻은 주의점

- **t3.small에서는 `pg_dump` 같은 추가 작업도 띄우지 말 것** — 위 1번. 무거운 작업은 먼저
  t3.medium으로 올린 뒤에 한다.
- **원격 프로세스를 패턴으로 찾아 종료할 때, 같은 SSH 명령 안에 그 패턴 문자열을 넣지 말 것.**
  `ssh ... 'kill $(ps ... | grep "[j]ava -jar"); ... nohup java -jar app.jar ...'`처럼 쓰면
  원격 셸 자신의 명령줄이 패턴에 걸려 셸까지 종료되고 뒤따르는 명령이 실행되지 않는다(이번에
  두 번 겪음). 종료와 기동을 별도의 SSH 호출로 나눈다(18절 3번처럼 PID를 먼저 확인).
- 재색인 중에는 다른 스케줄러도 평소대로 돈다(공시 수집으로 PENDING이 721 → 925건으로
  늘었다). DART 호출은 무료라 문제없지만, 건수 비교는 세 테이블 교체 직후에 할 것.
- 롤백: `~/insight-stock/backup/ec2-pre-migration-2026-10-12.dump`(이전 직전 EC2 DB 전체,
  4.9MB)를 `pg_restore --clean`으로 되돌린다. 같은 폴더의 `restore.sh`/`restore.log`는 이번에
  실제로 실행한 스크립트와 그 출력이다.

## 20. 레이트 리밋 — Nginx `limit_req` (2026-10-12 작성, **EC2에는 아직 미적용**)

`/api/v1/answers`와 `/api/v1/search`는 로그인 없이 열려 있다(포트폴리오 핵심 기능이라 의도적으로
공개 - `SecurityConfig`). 그런데 요청 횟수 제한이 어디에도 없어서, 누군가 반복 호출하면 OpenAI
TPM 한도(부하테스트에서 5 VU로도 닿았다 - `docs/loadtest.md`)가 소진돼 다른 사용자까지 전부
429를 받는다. 라이브 시연 중에 이 일이 생기면 대응할 방법이 없으므로 Nginx 단에서 IP별로 막는다.

**왜 앱(Spring)이 아니라 Nginx인가**: 막힌 요청이 JVM까지 오지 않아 스레드/커넥션을 쓰지 않고,
새 라이브러리(Bucket4j 등)나 코드 변경이 필요 없다. 단일 인스턴스라 분산 카운터도 필요 없다.
로그인 사용자 단위 제한은 못 하지만, 대상 API가 애초에 비로그인 공개라 IP 단위가 맞다.

**요청 "크기" 제한은 앱이 맡는다**(같은 날 적용): `/answers`의 `query` 500자 / `topK` 20 이하,
`/search`의 `q` 500자 이하. 횟수(Nginx) × 크기(앱) 두 축으로 TPM 소진을 막는 구조다.

### 설정

`/etc/nginx/conf.d/insightstock-backend.conf`를 아래처럼 고친다. 9절의 certbot이 관리하는 줄
(`listen 443 ssl`, `ssl_certificate` 등)과 80번 리다이렉트 server 블록은 건드리지 않는다.

**(1) 파일 맨 위, `upstream backend` 앞에 추가** (`limit_req_zone`은 server 블록 밖에 있어야 한다):

```nginx
# CORS 프리플라이트(OPTIONS)는 세지 않는다 - 키가 빈 문자열이면 limit_req가 그 요청을 건너뛴다.
# 프리플라이트까지 세면 브라우저의 POST 1번이 2번으로 계산된다.
map $request_method $ratelimit_key {
    OPTIONS "";
    default $binary_remote_addr;
}
limit_req_zone $ratelimit_key zone=answers_per_ip:10m rate=20r/m;
limit_req_zone $ratelimit_key zone=search_per_ip:10m rate=60r/m;
limit_req_status 429;    # 기본값은 503 - 앱의 rate limit 응답(429)과 맞춘다
```

**(2) 443 server 블록 안을 다음과 같이 고친다** (`proxy_set_header` 4줄을 `location /` 안에서
server 블록 바로 아래로 올린다 - 새 location 두 개가 같은 헤더를 물려받게 하기 위함):

```nginx
server {
    server_name insightstock.site;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    location / {
        proxy_pass http://backend;
    }

    location = /api/v1/answers {
        limit_req zone=answers_per_ip burst=10 nodelay;
        proxy_pass http://backend;
    }

    location = /api/v1/search {
        limit_req zone=search_per_ip burst=20 nodelay;
        proxy_pass http://backend;
    }

    # Nginx가 직접 거절할 때도 앱과 같은 형식(ProblemDetail + errorCode)으로 응답한다.
    # CORS 헤더가 없으면 브라우저가 응답을 읽지 못해 프론트에는 "서버에 연결할 수 없습니다"로만
    # 보인다 - 값은 EC2 .env의 CORS_ALLOWED_ORIGINS와 같은 프론트 주소로 넣는다.
    error_page 429 @rate_limited;
    location @rate_limited {
        default_type application/problem+json;
        add_header Access-Control-Allow-Origin "https://<Vercel 프로덕션 URL>" always;
        add_header Retry-After 5 always;
        return 429 '{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.","errorCode":"RATE_LIMITED"}';
    }

    # ... 이하 certbot이 관리하는 listen 443 ssl / ssl_certificate 줄은 그대로
}
```

### 값의 근거

| 경로 | 지속 속도 | burst | 의미 |
|---|---|---|---|
| `/api/v1/answers` | 분당 20회 (3초에 1회) | 10 | 한 IP가 연달아 11번까지는 즉시 통과, 이후 3초에 1번 |
| `/api/v1/search` | 분당 60회 (1초에 1회) | 20 | 연달아 21번까지 즉시 통과, 이후 1초에 1번 |

- `/answers` 분당 20회: 기본 topK(10)에서 요청 1건이 약 4,400~4,800토큰(실측,
  dart-findings.md 2-10)이라 한 IP가 한도를 꽉 채워도 분당 약 10만 토큰 - TPM 한도 200,000의
  절반이다. 한 사람이 화면에서 질문하는 속도(답변 1건에 2~3초 + 읽는 시간)로는 닿지 않는다.
- `/search`는 LLM을 거치지 않아 TPM과 무관하고(질의 임베딩 수십 토큰뿐) 보호 대상은 DB
  커넥션/스레드다. 그래서 더 느슨하게 잡았다.
- `nodelay`: burst 범위의 요청을 줄 세워 늦추지 않고 즉시 처리한다. 사용자가 기다리는 요청이라
  "느려짐"보다 "초과분은 바로 429"가 낫다.
- **값 자체는 실사용 트래픽 실측이 아니라 TPM 한도에서 역산한 값이다.**

**알아둘 한계 — 같은 공유기/학교 네트워크의 사용자는 IP 하나로 묶인다.** 발표장에서 청중
여럿이 같은 Wi-Fi로 동시에 써보면 합쳐서 분당 20회에 걸릴 수 있다. 그런 시연을 계획한다면
발표 전에 `rate`/`burst`를 올리고 reload하면 된다(아래).

### 적용 방법

Nginx 설정은 EC2의 `/etc/nginx`에 있고 **백엔드 jar와 무관하다.** 따라서:

- **jar를 재배포(18절)해도 자동으로 반영되지 않는다** - 최초 1회 SSH로 직접 고쳐야 한다.
- 반대로 한 번 적용하면 jar 재배포, 인스턴스 재시작, blue-green 전환(15절 - `upstream` 안의
  포트만 바꾼다), 인증서 갱신으로는 사라지지 않는다.
- 설정을 고친 뒤에는 **수동으로 reload해야 반영된다**:

```bash
# 고치기 전에 백업
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> \
  "sudo cp /etc/nginx/conf.d/insightstock-backend.conf /etc/nginx/insightstock-backend.conf.pre-ratelimit"

# (파일 수정 후) 문법 검증이 통과할 때만 reload - 재시작이 아니라 reload라 무중단이다(16절)
ssh -i "<PEM_PATH>" ec2-user@<PUBLIC_IP> "sudo nginx -t && sudo nginx -s reload"
```

적용 후 확인(같은 IP에서 연달아 호출 - 12번째부터 429가 나와야 한다):

```bash
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code} " -X POST https://insightstock.site/api/v1/answers \
    -H "Content-Type: application/json" -d '{"query":""}'
done; echo
# 기대: 400이 11번(빈 질문이라 LLM 호출 없이 검증에서 거절 - 비용 0) 이후 429
```

되돌리기: 백업 파일을 다시 복사하고 `sudo nginx -t && sudo nginx -s reload`.

### 검증 상태

- **로컬 검증 완료(2026-10-12)**: 위 설정을 `nginx:alpine` 컨테이너에 그대로 넣고(TLS 줄만 제외,
  가짜 백엔드 연결) 실제 요청으로 확인했다. `nginx -t` 통과 / `/answers` 연속 15회 → 200 11번 뒤
  429 / `/search` 연속 25회 → 200 21번 뒤 429 / 429 응답은 `application/problem+json` +
  `errorCode: RATE_LIMITED` + CORS 헤더 / 제한 중에도 `OPTIONS`는 전부 통과 / 다른 경로는 영향
  없음 / 백엔드가 직접 준 429(`LLM_RATE_LIMITED` 등)는 본문 그대로 통과 / 몇 초 뒤 다시 200.
- **EC2에는 아직 적용하지 않았다** - 인스턴스가 정지 상태였다. 다음에 켤 때 위 "적용 방법"대로
  적용하고, certbot이 고쳐둔 실제 파일과 합쳐졌을 때도 `nginx -t`가 통과하는지 확인할 것.

## 알려진 제약 / 다음 단계 후보

- **t3.small은 데이터 이전(19절) 이후 검색을 감당하지 못한다** — 첫 검색 직후 인스턴스가
  무응답이 됐다(2026-10-12 실측). 그래서 기본은 t3.small로 정지, 쓸 때만 t3.medium으로
  올린다(19절). t3.small에서는 백엔드를 띄우지 말 것.
- EBS는 20GB다(19절). 인스턴스를 꺼둬도 볼륨 요금은 나간다(8GB 대비 월 약 $1.1 추가).

- ~~퍼블릭 IP가 껐다 켤 때마다 바뀐다~~ — **해결됨(2026-10-12, Elastic IP 적용, 12절).**
  stop → start 후에도 IP가 유지되고 Vercel 재배포 없이 연동이 유지됨을 실측으로 확인했다.
  대신 인스턴스를 꺼둔 시간에도 Elastic IP 요금(시간당 $0.005, 월 약 $3.6)이 나가는
  트레이드오프가 생겼다.
- 백엔드가 jar 직접 실행 방식이라 EC2 재부팅 시 자동 기동되지 않는다 — systemd 서비스 등록
  또는 Docker 이미지화가 다음 단계 후보. 수동으로 띄울 때는 Elasticsearch가 healthy가 된
  뒤에 띄워야 한다(7절 주의 1) — systemd로 옮길 때도 이 기동 순서 의존성을 반영할 것.
- ~~Vercel rewrite 프록시는 임시 우회다~~ — **해결됨(2026-10-12).** `insightstock.site` +
  Let's Encrypt로 백엔드에 TLS를 붙이고 프록시를 제거했다(9절, 10절).
- Let's Encrypt 인증서는 90일짜리다(현재 만료 2027-01-10). 자동 갱신 타이머는 켜뒀지만
  인스턴스가 꺼져 있는 동안은 돌지 않는다 — 오래 꺼뒀다 켜면 `sudo certbot renew`로 확인(9절).
- **무중단 배포(Blue-Green) — 수동 절차는 검증 완료(13~17절), 자동화는 다음 단계.**
  이번 세션은 의도적으로 스크립트 없이 손으로 진행했다(이해/검증이 먼저, 자동화는 별도
  작업으로 미룸). 15절의 수동 절차를 스크립트 하나로 묶는 게 다음 후보.
- **blue-green 시도 시 t3.medium 이상 필요(17절)** — JVM 2개가 동시에 떠야 해서다. 단일
  인스턴스 운영도 데이터 이전(19절) 이후로는 t3.small로 버티지 못하므로, 백엔드를 띄울 때는
  항상 t3.medium으로 올린다. 인스턴스의 현재 타입/상태는 progress.md의 최신 기록을 확인할 것.
- **레이트 리밋(20절)은 EC2에 아직 적용되지 않았다** — 설정과 절차만 문서화된 상태다.

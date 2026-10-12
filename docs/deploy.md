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

검증:

```bash
curl http://<PUBLIC_IP>/actuator/health        # 80번, 정상 응답 기대
curl -m 8 http://<PUBLIC_IP>:8080/actuator/health   # 8080번, 타임아웃/거부 기대
```

## 10. 프론트엔드(Vercel) 배포와 Mixed Content 문제

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

### 해결: Next.js rewrite를 이용한 서버사이드 프록시

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

**시연 직전 도메인+TLS를 붙이면**: 이 rewrite 우회는 더 이상 필요 없다. `next.config.ts`의
`rewrites()`를 제거하고 `NEXT_PUBLIC_API_BASE_URL`을 다시 백엔드의 `https://<도메인>`으로
직접 가리키게 바꾸면 된다(그때는 진짜 HTTPS끼리 통신이라 Mixed Content 문제 자체가 없음).

## 11. CORS 설정

`cors.allowed-origins`는 `application.yml`에서 `${CORS_ALLOWED_ORIGINS:http://localhost:3000}`
로 외부화되어 있다 — `.env`(로컬은 미설정 시 localhost:3000 기본값, 배포 EC2는
`.env`에 `CORS_ALLOWED_ORIGINS=https://<Vercel 프로덕션 URL>` 추가) 로 값을 넣는다.

10절의 Vercel rewrite 프록시를 쓰는 한, 브라우저는 항상 같은 origin으로만 호출하므로
CORS 프리플라이트 자체가 발생하지 않아 이 값이 실제로 검증 경로에 관여하지는 않는다.
다만 향후 프론트가 백엔드를 직접(도메인+TLS로) 호출하는 방식으로 되돌아갈 경우를 대비해
값은 계속 맞춰둔다.

## 12. Elastic IP — 고정 IP 적용 (2026-10-12 적용)

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

## 알려진 제약 / 다음 단계 후보

- ~~퍼블릭 IP가 껐다 켤 때마다 바뀐다~~ — **해결됨(2026-10-12, Elastic IP 적용, 12절).**
  stop → start 후에도 IP가 유지되고 Vercel 재배포 없이 연동이 유지됨을 실측으로 확인했다.
  대신 인스턴스를 꺼둔 시간에도 Elastic IP 요금(시간당 $0.005, 월 약 $3.6)이 나가는
  트레이드오프가 생겼다.
- 백엔드가 jar 직접 실행 방식이라 EC2 재부팅 시 자동 기동되지 않는다 — systemd 서비스 등록
  또는 Docker 이미지화가 다음 단계 후보. 수동으로 띄울 때는 Elasticsearch가 healthy가 된
  뒤에 띄워야 한다(7절 주의 1) — systemd로 옮길 때도 이 기동 순서 의존성을 반영할 것.
- Vercel rewrite 프록시는 임시 우회다 — 시연 직전 도메인 구매 + Let's Encrypt로 백엔드에
  진짜 TLS를 붙이면 제거하고 직접 호출로 되돌린다(10절 참고).
- **무중단 배포(Blue-Green) — 수동 절차는 검증 완료(13~17절), 자동화는 다음 단계.**
  이번 세션은 의도적으로 스크립트 없이 손으로 진행했다(이해/검증이 먼저, 자동화는 별도
  작업으로 미룸). 15절의 수동 절차를 스크립트 하나로 묶는 게 다음 후보.
- **blue-green 시도 시 t3.medium 이상 필요(17절)** — 평소엔 t3.small로 충분하지만, 무중단
  전환을 실제로 쓰려면 그 순간만큼은 t3.medium 이상이어야 한다. 인스턴스가 지금
  t3.medium으로 남아있는지 t3.small로 되돌렸는지는 progress.md의 최신 상태를 확인할 것.

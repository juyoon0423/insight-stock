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

퍼블릭 IP 조회 (재시작할 때마다 바뀔 수 있음 — Elastic IP를 안 붙였기 때문. 시연 때 고정 IP가
필요하면 이 단계에서 Elastic IP 할당을 추가로 고려):

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

인스턴스를 끄면 이번에 붙은 퍼블릭 IP는 반환된다(Elastic IP 미사용). 다시 켤 때는 3단계
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

# 퍼블릭 IP가 바뀌었으므로 다시 조회
aws ec2 describe-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text

# SSH 접속 후 docker compose가 자동 기동되지 않으므로 다시 올려준다
ssh -i "<PEM_PATH>" ec2-user@<새 PUBLIC_IP> "cd ~/insight-stock && docker compose up -d"

# 백엔드도 다시 실행 (재부팅 시 프로세스가 사라지므로)
ssh -i "<PEM_PATH>" ec2-user@<새 PUBLIC_IP> \
  "cd ~/insight-stock && nohup java -jar app.jar > app.log 2>&1 < /dev/null & disown"
```

> 참고: SSH 보안 그룹 규칙이 "내 IP"로 고정되어 있으므로, 새 위치(카페, 다른 네트워크 등)에서
> 접속하려면 1단계의 "내 IP가 바뀌면" 절차대로 규칙을 갱신해야 한다.

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
  않고 Vercel 서버에서만 쓰임) = 현재 EC2 IP. **EC2 IP가 바뀔 때마다 이 값을 갱신하고
  재배포해야 한다** (11절 참고).

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

## 12. IP가 바뀔 때마다 갱신해야 하는 값 (요약)

인스턴스를 껐다 켤 때마다 퍼블릭 IP가 바뀐다(Elastic IP 미사용). 그때마다:

1. `frontend/next.config.ts`의 `BACKEND_ORIGIN` 기본값 또는 Vercel의 `BACKEND_ORIGIN`
   환경변수를 새 IP로 갱신 → `npx vercel --prod`로 재배포
2. EC2 쪽 `.env`의 `CORS_ALLOWED_ORIGINS`는 프론트엔드 주소가 바뀌지 않는 한 그대로 둬도 됨
   (Vercel 프로덕션 URL은 고정 alias라 IP가 바뀌어도 안 변함)
3. 로컬 개발용 `frontend/.env.local`의 `NEXT_PUBLIC_API_BASE_URL`은 이 배포 절차와 무관
   (로컬 개발은 `http://localhost:8080`을 그대로 씀)

## 알려진 제약 / 다음 단계 후보

- 퍼블릭 IP가 껐다 켤 때마다 바뀐다 — Vercel의 `BACKEND_ORIGIN` 환경변수를 매번 갱신하고
  재배포해야 한다(12절). Elastic IP를 붙이면 이 갱신 자체가 없어진다 — 비용 대비 효과를
  판단해 다음 단계에서 고려.
- 백엔드가 jar 직접 실행 방식이라 EC2 재부팅 시 자동 기동되지 않는다 — systemd 서비스 등록
  또는 Docker 이미지화가 다음 단계 후보.
- Vercel rewrite 프록시는 임시 우회다 — 시연 직전 도메인 구매 + Let's Encrypt로 백엔드에
  진짜 TLS를 붙이면 제거하고 직접 호출로 되돌린다(10절 참고).
- 무중단 배포(재배포 시 다운타임 없이 교체)는 이번 범위 밖 — 별도로 설계할 것.

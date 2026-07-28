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

## 8. 완전히 정리할 때 (더 이상 안 쓸 때)

```bash
aws ec2 terminate-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 wait instance-terminated --region ap-northeast-2 --instance-ids <INSTANCE_ID>
aws ec2 delete-security-group --region ap-northeast-2 --group-id <SG_ID>
```

## 알려진 제약 / 다음 단계 후보

- 퍼블릭 IP가 껐다 켤 때마다 바뀐다 — 시연 때 프론트엔드 설정(API base URL)을 매번 갱신해야
  하거나, Elastic IP를 별도로 붙여야 한다.
- 백엔드가 jar 직접 실행 방식이라 EC2 재부팅 시 자동 기동되지 않는다 — systemd 서비스 등록
  또는 Docker 이미지화가 다음 단계 후보.
- 8080 포트가 전체 공개되어 있다 — Nginx 리버스 프록시(80/443)로 감추고 8080 자체는 보안
  그룹에서 제거하는 게 다음 단계.
- 무중단 배포(재배포 시 다운타임 없이 교체)는 이번 범위 밖 — 별도로 설계할 것.

# insight-stock

## 로컬 개발용 Docker 환경

`docker-compose.yml`로 PostgreSQL(pgvector 포함)과 Elasticsearch(Nori 형태소 분석기 포함)를
로컬에 띄운다.

- **postgres**: `pgvector/pgvector:pg16` 이미지. 컨테이너 최초 기동 시
  `docker/postgres/init/01-create-extension.sql`이 실행되어 `vector` 확장을 자동으로 활성화한다.
- **elasticsearch**: `docker/elasticsearch/Dockerfile`로 `elasticsearch:8.15.3` 베이스에
  `analysis-nori` 플러그인을 설치한 커스텀 이미지. 개발 편의를 위해 `xpack.security.enabled=false`,
  단일 노드로 기동한다.

두 서비스 모두 데이터 유지를 위해 named volume(`pgdata`, `esdata`)을 사용하므로, 컨테이너를
`down`(볼륨 삭제 없이) 했다가 다시 올려도 데이터가 남아있다.

### 준비

`.env`에 아래 값이 채워져 있어야 한다 (`.env.example` 참고, 실제 `.env`는 git에 올리지 않는다):

```
POSTGRES_USER=
POSTGRES_PASSWORD=
```

### 기동 / 종료

```bash
# 최초 기동 (elasticsearch는 커스텀 이미지라 빌드가 필요)
docker compose up -d --build

# 상태 확인 (STATUS가 healthy가 될 때까지 몇십 초 정도 걸릴 수 있음)
docker compose ps

# 로그 확인
docker compose logs -f postgres
docker compose logs -f elasticsearch

# 종료 (데이터는 유지됨)
docker compose down

# 종료 + 데이터까지 완전 삭제
docker compose down -v
```

### 정상 동작 확인

**PostgreSQL + pgvector**

```bash
docker compose exec postgres bash -c 'psql -U "$POSTGRES_USER" -d insight_stock -c "\dx"'
```
`vector` 확장이 목록에 보이면 정상이다.

**Elasticsearch + Nori**

```bash
# 클러스터 상태 (status: green/yellow면 정상)
curl -s "http://localhost:9200/_cluster/health?pretty"

# nori 플러그인 설치 확인
curl -s http://localhost:9200/_cat/plugins
```

## 관련 문서

- [docs/dart-findings.md](./docs/dart-findings.md) — Open DART API 탐색 기록 (list/document API,
  발견한 함정, 청킹 전략)
- [docs/erd.md](./docs/erd.md) — 데이터베이스 설계 (companies / disclosures / chunks)

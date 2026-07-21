-- docker/postgres/init에서 이미 설치되지만, 마이그레이션만으로도 완전히 재현 가능하도록
-- 안전하게 한 번 더 명시한다.
CREATE EXTENSION IF NOT EXISTS vector;

-- =========================================================
-- companies
-- =========================================================
CREATE TABLE companies (
    id         BIGSERIAL PRIMARY KEY,
    corp_code  VARCHAR(255) NOT NULL,
    corp_name  VARCHAR(255) NOT NULL,
    stock_code VARCHAR(255),
    corp_cls   VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_companies_corp_code UNIQUE (corp_code)
);

-- =========================================================
-- disclosures
-- =========================================================
CREATE TABLE disclosures (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT       NOT NULL,
    rcept_no         VARCHAR(255) NOT NULL,
    report_nm        VARCHAR(255) NOT NULL,
    rcept_dt         VARCHAR(255) NOT NULL,
    has_document     BOOLEAN      NOT NULL DEFAULT false,
    document_status  VARCHAR(255) NOT NULL DEFAULT 'PENDING',
    char_length      INTEGER,
    raw_content      TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_disclosures_rcept_no UNIQUE (rcept_no),
    CONSTRAINT fk_disclosures_company
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
);

-- 배치가 document_status로 미완료/실패 건을 골라 재처리하므로 조회 빈도가 높다.
CREATE INDEX idx_disclosures_document_status ON disclosures (document_status);

-- company_id는 FK지만 Postgres가 FK 컬럼을 자동으로 인덱싱해주지 않으므로 명시적으로 추가한다.
CREATE INDEX idx_disclosures_company_id ON disclosures (company_id);

-- =========================================================
-- chunks
-- =========================================================
CREATE TABLE chunks (
    id               BIGSERIAL PRIMARY KEY,
    disclosure_id    BIGINT        NOT NULL,
    chunk_index      INTEGER       NOT NULL,
    section_title    VARCHAR(255),
    chunk_type       VARCHAR(255)  NOT NULL,
    content          TEXT          NOT NULL,
    embedding        vector(1536),
    embedding_model  VARCHAR(255),
    token_count      INTEGER,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_chunks_disclosure
        FOREIGN KEY (disclosure_id) REFERENCES disclosures (id) ON DELETE CASCADE,
    -- chunk_index는 "공시 내 청크 순번"이므로 같은 공시 안에서 중복되면 안 된다.
    CONSTRAINT uk_chunks_disclosure_chunk_index UNIQUE (disclosure_id, chunk_index)
);

CREATE INDEX idx_chunks_disclosure_id ON chunks (disclosure_id);

-- pgvector 유사도 검색 인덱스: HNSW를 선택했다.
--
-- - IVFFlat은 클러스터(lists) 개수를 테이블에 데이터가 어느 정도 쌓인 뒤 통계를 보고
--   정해야 품질이 나오고(대략 lists = rows/1000 권장), 데이터가 거의 없는 상태에서 만들면
--   클러스터링 품질이 떨어진다. 지금 chunks 테이블은 비어 있는 상태로 시작해서 배치로
--   계속 늘어나는 구조라, 초기부터 최적 파라미터를 잡기 어렵고 나중에 REINDEX로
--   다시 만들어줘야 한다.
-- - HNSW는 그래프를 점진적으로(incremental) 구성하기 때문에 테이블이 비어 있는 시점에
--   인덱스를 만들어도 데이터가 쌓이면서 품질이 자연스럽게 좋아지고, 같은 recall 기준에서
--   IVFFlat보다 쿼리 속도가 더 빠른 경우가 많다(빌드 시간/메모리 비용은 더 든다).
-- - 임베딩에 text-embedding-3-small(OpenAI 권장 거리 척도: cosine)을 쓰기로 했으므로
--   vector_cosine_ops를 사용한다.
CREATE INDEX idx_chunks_embedding_hnsw ON chunks
    USING hnsw (embedding vector_cosine_ops);

-- =========================================================
-- users
-- =========================================================
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_users_email UNIQUE (email)
);

-- =========================================================
-- watchlist (users <-> companies 다대다 연결)
-- =========================================================
CREATE TABLE watchlist (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    company_id BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_watchlist_user_company UNIQUE (user_id, company_id),
    CONSTRAINT fk_watchlist_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_company
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
);

-- FK 컬럼은 Postgres가 자동으로 인덱싱해주지 않는다(disclosures.company_id와 같은 이유).
-- user_id: "내 관심 목록" 조회, company_id: 4단계 알림 배치가 "이 회사를 누가 보고 있나" 조회.
CREATE INDEX idx_watchlist_user_id ON watchlist (user_id);
CREATE INDEX idx_watchlist_company_id ON watchlist (company_id);

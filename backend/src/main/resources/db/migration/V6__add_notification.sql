-- =========================================================
-- notification (관심 회사의 신규 공시 알림)
-- =========================================================
CREATE TABLE notification (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    disclosure_id BIGINT      NOT NULL,
    is_read       BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 같은 사용자에게 같은 공시로는 알림이 최대 1건.
    -- 스케줄러가 겹치는 시간 윈도우를 여러 번 훑어도 INSERT ... ON CONFLICT DO NOTHING으로 안전하게 재실행 가능.
    CONSTRAINT uk_notification_user_disclosure UNIQUE (user_id, disclosure_id),

    CONSTRAINT fk_notification_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_disclosure
        FOREIGN KEY (disclosure_id) REFERENCES disclosures (id) ON DELETE CASCADE
);

-- 헤더 배지("안 읽은 개수")와 알림 목록("최신순") 조회를 커버.
CREATE INDEX idx_notification_user_unread
    ON notification (user_id, is_read, created_at DESC);

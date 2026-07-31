-- ShedLock 공식 스키마 그대로(Postgres) - net.javacrumbs.shedlock:shedlock-provider-jdbc-template가
-- 이 테이블 구조를 그대로 기대한다. 무중단(Blue-Green) 배포 중 인스턴스가 잠깐 2개 뜰 때
-- 같은 @Scheduled 배치가 양쪽에서 동시 실행되는 걸 막기 위한 분산 락 저장소.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

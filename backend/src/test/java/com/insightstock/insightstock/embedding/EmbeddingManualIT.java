package com.insightstock.insightstock.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필의 유일한 유료 단계 - 3단계(jtokkit 실측 비용 계산)에서 최종
// 승인된 청크 수를 확인하기 전까지는 절대 실행하지 않는다. 다른 백필 ManualIT(수집/
// 문서/청킹/색인)과 파일을 분리해 실수로 같이 실행될 여지를 없앴고, 승인 플래그를
// 이중으로(파일 단위 + 메서드 단위 limit 0) 둬서 manualTest를 통째로 돌려도 과금되지
// 않게 했다. embedding.collector.enabled=false를 유지해 스케줄러와 동시에 호출되는
// 경로도 막아야 한다(다른 안전장치와 별개로 실행 전 반드시 확인).
@Tag("manual")
@SpringBootTest
class EmbeddingManualIT {

    // 2단계/3단계가 끝나고 최종 승인된 뒤에만 true로 바꾼다.
    private static final boolean BACKFILL_APPROVED = false;
    // 파일럿은 비용이 무시할 수준(청크 10개, 기존 임베딩 파이프라인 검증 때와 동일한
    // 방식)이라 승인 플래그만으로 보호. fullBackfillEmbed()는 승인 플래그에 더해
    // BACKFILL_LIMIT을 0 초과로 직접 설정해야만 동작하는 이중 안전장치를 둔다.
    private static final int PILOT_LIMIT = 10;
    private static final int BACKFILL_LIMIT = 0;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pilotEmbed() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 3단계 비용 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        long beforePending = countPending();
        System.out.println("=== 임베딩 파일럿 시작: 대기 " + beforePending + "건 ===");

        embeddingService.embedPending(PILOT_LIMIT);

        long afterPending = countPending();
        System.out.println("임베딩 대기(embedding IS NULL): " + beforePending + " -> " + afterPending);
    }

    @Test
    void fullBackfillEmbed() {
        if (!BACKFILL_APPROVED || BACKFILL_LIMIT <= 0) {
            System.out.println(
                    "BACKFILL_APPROVED=" + BACKFILL_APPROVED + ", BACKFILL_LIMIT=" + BACKFILL_LIMIT
                            + " - 3단계 실측 비용 확인 후 둘 다 설정하고 재실행하세요.");
            return;
        }

        long beforePending = countPending();
        System.out.println("=== 임베딩 백필 시작: 대기 " + beforePending + "건 중 최대 " + BACKFILL_LIMIT + "건 ===");

        embeddingService.embedPending(BACKFILL_LIMIT);

        long afterPending = countPending();
        System.out.println("임베딩 대기(embedding IS NULL): " + beforePending + " -> " + afterPending);
    }

    private long countPending() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks WHERE embedding IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}

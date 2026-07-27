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

    private static final double PRICE_PER_MILLION_TOKENS = 0.02;

    // 7일치 백필 완료(2026-07-27, 167,834건 전부 처리, embedding IS NULL 0건, 실패 0건) -
    // 안전값으로 복귀. 다음 백필 때 승인 후 다시 켤 것.
    private static final boolean BACKFILL_APPROVED = false;
    // 파일럿은 비용이 무시할 수준(청크 10개, 기존 임베딩 파이프라인 검증 때와 동일한
    // 방식)이라 승인 플래그만으로 보호. fullBackfillEmbed()는 승인 플래그에 더해
    // BACKFILL_LIMIT을 0 초과로 직접 설정해야만 동작하는 이중 안전장치를 둔다.
    private static final int PILOT_LIMIT = 10;
    private static final int BACKFILL_LIMIT = 0;
    // embedPending(limit)도 findByEmbeddingIsNull()이 limit만큼 즉시 메모리에 로딩한다.
    // 청크 content는 공시 원문보다 훨씬 작지만(평균 수백 토큰), ChunkingManualIT에서
    // 겪은 것과 같은 위험을 피하기 위해 BACKFILL_LIMIT을 한 번에 넘기지 않고 이 크기로
    // 나눠 반복 호출한다.
    private static final int BATCH_SIZE = 5_000;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private TokenCounter tokenCounter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // API 호출도, DB 쓰기도 없는 순수 로컬 계산 - jtokkit(OpenAI 임베딩과 동일한
    // cl100k_base 인코딩)으로 embedding IS NULL인 청크 전체의 토큰 수를 세어 실제
    // 임베딩 비용을 미리 계산한다. content를 한 번에 List로 다 읽지 않고
    // RowCallbackHandler로 한 행씩 스트리밍 처리한다 - ChunkingManualIT에서 대량
    // 텍스트를 한 번에 메모리에 올렸다가 프로세스가 죽었던 것과 같은 위험을
    // 피하기 위함. API를 안 부르므로 BACKFILL_APPROVED 가드가 필요 없다.
    @Test
    void estimateCost() {
        long[] chunkCount = {0};
        long[] totalTokens = {0};

        jdbcTemplate.query("SELECT content FROM chunks WHERE embedding IS NULL", rs -> {
            totalTokens[0] += tokenCounter.count(rs.getString("content"));
            chunkCount[0]++;
        });

        double cost = totalTokens[0] / 1_000_000.0 * PRICE_PER_MILLION_TOKENS;
        System.out.println("=== 임베딩 비용 실측 (API 호출 없음, jtokkit 로컬 계산) ===");
        System.out.printf(
                "임베딩 대기 청크: %d건, 총 토큰: %d개, 예상 비용: $%.4f%n",
                chunkCount[0], totalTokens[0], cost);
    }

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
        System.out.println("=== 임베딩 백필 시작: 대기 " + beforePending + "건 중 최대 " + BACKFILL_LIMIT
                + "건 (배치 크기 " + BATCH_SIZE + ") ===");

        long totalProcessed = 0;
        long processedThisBatch;
        do {
            long beforeBatch = countPending();
            embeddingService.embedPending(BATCH_SIZE);
            long afterBatch = countPending();
            processedThisBatch = beforeBatch - afterBatch;
            totalProcessed += processedThisBatch;
            System.out.println("배치 처리: " + processedThisBatch + "건 (남은 대기: " + afterBatch + "건, 누적 "
                    + totalProcessed + "건)");
        } while (processedThisBatch > 0 && totalProcessed < BACKFILL_LIMIT);

        long afterPending = countPending();
        System.out.println("임베딩 백필 완료: 총 " + totalProcessed + "건 처리");
        System.out.println("임베딩 대기(embedding IS NULL): " + beforePending + " -> " + afterPending);
    }

    private long countPending() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks WHERE embedding IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}

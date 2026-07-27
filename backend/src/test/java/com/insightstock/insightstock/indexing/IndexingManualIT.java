package com.insightstock.insightstock.indexing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 4단계 - CHUNKED 공시를 전부 ElasticSearch에 색인한다. 임베딩
// 여부와 무관하게(비용 0) 3단계 비용 결정 전에 먼저 돌려도 되는 단계.
// ChunkingManualIT에서 겪은 것과 같은 이유로(대상 공시가 raw_content를 그대로 갖고
// 있어 findByDocumentStatus()가 큰 limit을 한 번에 통째로 메모리에 로딩함) BATCH_SIZE
// 단위로 나눠 반복 호출한다(서비스 로직 변경 없음).
@Tag("manual")
@SpringBootTest
class IndexingManualIT {

    // 2단계 실행을 승인하기 전까지는 false로 두어 실수로 실행돼도 아무 일도 안 일어나게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    private static final int BATCH_SIZE = 200;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backfillAll() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 2단계 실행 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        long beforeIndexed = countByStatus("INDEXED");
        System.out.println("=== 색인 시작: 현재 INDEXED " + beforeIndexed + "건, CHUNKED 대기 "
                + countByStatus("CHUNKED") + "건 (배치 크기 " + BATCH_SIZE + ") ===");

        long totalProcessed = 0;
        long processedThisBatch;
        do {
            long beforeBatch = countByStatus("CHUNKED");
            indexingService.indexPending(BATCH_SIZE);
            long afterBatch = countByStatus("CHUNKED");
            processedThisBatch = beforeBatch - afterBatch;
            totalProcessed += processedThisBatch;
            System.out.println("배치 처리: " + processedThisBatch + "건 (남은 CHUNKED: " + afterBatch + "건)");
        } while (processedThisBatch > 0);

        long afterIndexed = countByStatus("INDEXED");
        System.out.println("색인 완료: 총 " + totalProcessed + "건 처리");
        System.out.println("INDEXED 건수: " + beforeIndexed + " -> " + afterIndexed);
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM disclosures WHERE document_status = ?", Long.class, status);
        return count == null ? 0 : count;
    }
}

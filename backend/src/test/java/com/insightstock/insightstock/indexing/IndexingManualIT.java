package com.insightstock.insightstock.indexing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 4단계 - CHUNKED 공시를 한 번에 전부 ElasticSearch에 색인한다.
// 스케줄러 배치 크기(20건/10분)를 거치지 않고 indexPending()에 큰 limit을 직접 넘긴다
// (서비스 로직 변경 없음). 임베딩 여부와 무관하게(비용 0) 3단계 비용 결정 전에 먼저
// 돌려도 되는 단계.
@Tag("manual")
@SpringBootTest
class IndexingManualIT {

    // 2단계 실행을 승인하기 전까지는 false로 두어 실수로 실행돼도 아무 일도 안 일어나게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    private static final int BACKFILL_LIMIT = 50_000;

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
        System.out.println("=== 색인 시작: 현재 INDEXED " + beforeIndexed + "건 ===");

        indexingService.indexPending(BACKFILL_LIMIT);

        long afterIndexed = countByStatus("INDEXED");
        System.out.println("INDEXED 건수: " + beforeIndexed + " -> " + afterIndexed);
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM disclosures WHERE document_status = ?", Long.class, status);
        return count == null ? 0 : count;
    }
}

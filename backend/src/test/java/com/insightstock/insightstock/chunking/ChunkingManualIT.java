package com.insightstock.insightstock.chunking;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 3단계 - PARSED 공시를 전부 청킹한다. 스케줄러 배치 크기
// (20건/10분)를 거치지 않되, chunkPending()에 큰 limit을 한 번에 넘기지는 않는다
// - PARSED 공시는 이미 raw_content(최대 수십만자짜리 문서 포함)를 갖고 있어서,
// findByDocumentStatus()가 대상 전체를 즉시 메모리에 로딩하는 chunkPending() 특성상
// limit을 5만처럼 크게 주면 한 번에 대량의 원문 텍스트가 로딩된다(실제로 이 방식으로
// 시도했다가 예외 로그 하나 없이 프로세스가 죽는 걸 겪었다 - 정확한 원인은 특정 못
// 했지만 재현을 피하는 게 우선). BATCH_SIZE 단위로 나눠 반복 호출해 한 번에 메모리에
// 올라가는 양을 제한한다(서비스 로직 변경 없음 - chunkPending(int limit)을 여러 번
// 부르는 것뿐).
@Tag("manual")
@SpringBootTest
class ChunkingManualIT {

    // 2단계 실행을 승인하기 전까지는 false로 두어 실수로 실행돼도 아무 일도 안 일어나게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    private static final int BATCH_SIZE = 200;

    @Autowired
    private ChunkingService chunkingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backfillAll() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 2단계 실행 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        long beforeChunks = countChunks();
        System.out.println("=== 청킹 시작: 현재 청크 " + beforeChunks + "건, PARSED 대기 "
                + countParsed() + "건 (배치 크기 " + BATCH_SIZE + ") ===");

        long totalProcessed = 0;
        long processedThisBatch;
        do {
            long beforeBatch = countParsed();
            chunkingService.chunkPending(BATCH_SIZE);
            long afterBatch = countParsed();
            processedThisBatch = beforeBatch - afterBatch;
            totalProcessed += processedThisBatch;
            System.out.println("배치 처리: " + processedThisBatch + "건 (남은 PARSED: " + afterBatch + "건)");
        } while (processedThisBatch > 0);

        long afterChunks = countChunks();
        System.out.println("청킹 완료: 총 " + totalProcessed + "건 처리");
        System.out.println("청크 총 건수: " + beforeChunks + " -> " + afterChunks);
    }

    private long countChunks() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks", Long.class);
        return count == null ? 0 : count;
    }

    private long countParsed() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM disclosures WHERE document_status = 'PARSED'", Long.class);
        return count == null ? 0 : count;
    }
}

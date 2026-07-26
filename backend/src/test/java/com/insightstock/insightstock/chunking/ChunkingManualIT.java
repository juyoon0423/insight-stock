package com.insightstock.insightstock.chunking;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 3단계 - PARSED 공시를 한 번에 전부 청킹한다. 스케줄러 배치
// 크기(20건/10분)를 거치지 않고 chunkPending()에 큰 limit을 직접 넘긴다(서비스 로직
// 변경 없음). 이 단계 결과(청크 수)가 바로 3단계(실측 비용 계산)의 입력이 된다.
@Tag("manual")
@SpringBootTest
class ChunkingManualIT {

    // 2단계 실행을 승인하기 전까지는 false로 두어 실수로 실행돼도 아무 일도 안 일어나게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    private static final int BACKFILL_LIMIT = 50_000;

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
        System.out.println("=== 청킹 시작: 현재 청크 " + beforeChunks + "건 ===");

        chunkingService.chunkPending(BACKFILL_LIMIT);

        long afterChunks = countChunks();
        System.out.println("청크 총 건수: " + beforeChunks + " -> " + afterChunks);
    }

    private long countChunks() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks", Long.class);
        return count == null ? 0 : count;
    }
}

package com.insightstock.insightstock.collector;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 2단계 - PENDING 공시 원문을 한 번에 대량 처리한다. 스케줄러의
// 배치 크기(20건/10분)를 거치지 않고 collectPending()에 큰 limit을 직접 넘겨 한 번의
// 호출로 끝낸다(서비스 로직 변경 없음 - collectPending(int limit)이 이미 임의 개수를
// 받는다). document API 자체는 무료지만 disclosures 테이블을 대량으로 바꾸는 통합
// 테스트라 다른 ManualIT과 같은 패턴을 따른다.
@Tag("manual")
@SpringBootTest
class DocumentCollectorManualIT {

    // 2단계 실행을 승인하기 전까지는 false로 두어 실수로 실행돼도 아무 일도 안 일어나게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    // 1단계(DisclosureCollectorManualIT)로 수집된 PENDING 전체를 커버할 만큼 넉넉하게 잡음.
    private static final int BACKFILL_LIMIT = 50_000;

    @Autowired
    private DocumentCollectorService documentCollectorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backfillAll() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 2단계 실행 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        System.out.println("=== 원문 수집 시작 ===");
        printCountsByStatus("수집 전");

        documentCollectorService.collectPending(BACKFILL_LIMIT);

        printCountsByStatus("수집 후");
    }

    private void printCountsByStatus(String label) {
        jdbcTemplate
                .queryForList(
                        "SELECT document_status, count(*) AS cnt FROM disclosures GROUP BY document_status ORDER BY document_status")
                .forEach(row -> System.out.println(
                        label + " - " + row.get("document_status") + ": " + row.get("cnt")));
    }
}

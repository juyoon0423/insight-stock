package com.insightstock.insightstock.backfill;

import com.insightstock.insightstock.chunking.ChunkingService;
import com.insightstock.insightstock.collector.DocumentCollectorService;
import com.insightstock.insightstock.indexing.IndexingService;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

// 2026-10 운영 DB 추가 색인 - 무료 단계(원문 수집·청킹·색인)를 접수일 구간(BackfillRange)으로
// 제한해서 실행한다. 유료 단계(임베딩)는 EmbeddingManualIT에 따로 있다.
//
// 실행 예(터널이 열린 상태에서, backend/ 폴더):
//   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/insight_stock \
//   SPRING_ELASTICSEARCH_URIS=http://localhost:19200 BACKFILL_STEP=pilot-documents \
//   ./gradlew manualTest --tests '*RangeBackfillManualIT.pilotDocuments'
//
// 단계 이름: pilot-documents, pilot-chunk, pilot-index, range-documents, range-chunk, range-index.
// report()는 읽기 전용이라 단계 이름이 필요 없다.
@Tag("manual")
@SpringBootTest(properties = "scheduling.enabled=false")
class RangeBackfillManualIT {

    // 구간 전체 처리 시 한 번에 고르는 공시 수 - 처리는 어차피 한 건씩 다시 조회한다.
    private static final int BATCH_SIZE = 200;

    @Autowired
    private DocumentCollectorService documentCollectorService;

    @Autowired
    private ChunkingService chunkingService;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    @Test
    void report() throws Exception {
        BackfillRange.assertSafeContext(context, dataSource);
        System.out.println("=== 구간 " + BackfillRange.FROM + "~" + BackfillRange.TO + " 상태 (읽기 전용) ===");
        jdbc.queryForList(
                        "SELECT document_status, count(*) AS cnt FROM disclosures "
                                + "WHERE rcept_dt BETWEEN ? AND ? GROUP BY 1 ORDER BY 1",
                        BackfillRange.FROM, BackfillRange.TO)
                .forEach(row -> System.out.println("  " + row.get("document_status") + ": " + row.get("cnt")));
        System.out.println("  청크: " + jdbc.queryForObject(
                "SELECT count(*) FROM chunks c JOIN disclosures d ON d.id = c.disclosure_id "
                        + "WHERE d.rcept_dt BETWEEN ? AND ?",
                Long.class, BackfillRange.FROM, BackfillRange.TO));

        List<Long> pilot = BackfillRange.pilotIds(jdbc);
        System.out.println("=== 파일럿 " + pilot.size() + "건 ===");
        for (Long id : pilot) {
            jdbc.queryForList(
                            "SELECT d.id, d.rcept_dt, d.document_status, d.char_length, d.report_nm, "
                                    + "(SELECT count(*) FROM chunks c WHERE c.disclosure_id = d.id) AS chunks "
                                    + "FROM disclosures d WHERE d.id = ?", id)
                    .forEach(row -> System.out.println("  " + row));
        }
    }

    @Test
    void pilotDocuments() throws Exception {
        runPilot("pilot-documents", documentCollectorService::collectByIds);
    }

    @Test
    void pilotChunk() throws Exception {
        runPilot("pilot-chunk", chunkingService::chunkByIds);
    }

    @Test
    void pilotIndex() throws Exception {
        runPilot("pilot-index", indexingService::indexByIds);
    }

    @Test
    void rangeDocuments() throws Exception {
        runRange("range-documents", "PENDING", documentCollectorService::collectByIds);
    }

    @Test
    void rangeChunk() throws Exception {
        runRange("range-chunk", "PARSED", chunkingService::chunkByIds);
    }

    @Test
    void rangeIndex() throws Exception {
        runRange("range-index", "CHUNKED", indexingService::indexByIds);
    }

    private void runPilot(String step, Function<List<Long>, Integer> action) throws Exception {
        if (!BackfillRange.stepIs(step)) {
            System.out.println(BackfillRange.STEP_ENV + "=" + step + " 일 때만 실행한다 - 건너뜀");
            return;
        }
        BackfillRange.assertSafeContext(context, dataSource);
        List<Long> pilot = BackfillRange.pilotIds(jdbc);
        if (pilot.size() != BackfillRange.PILOT_SIZE) {
            throw new IllegalStateException("파일럿 대상이 " + pilot.size() + "건 - 구간 목록 수집이 먼저 필요");
        }
        System.out.println("=== " + step + " 시작: 대상 id " + pilot + " ===");
        int processed = action.apply(pilot);
        System.out.println(step + " 완료: 처리 " + processed + "건 (상태가 맞지 않아 건너뛴 것 제외)");
    }

    // 구간 안에서 지정 상태인 공시가 없어질 때까지 BATCH_SIZE씩 처리한다. 한 바퀴에 하나도
    // 처리하지 못하면(전부 건너뜀) 멈춘다 - 무한 반복 방지.
    private void runRange(String step, String status, Function<List<Long>, Integer> action) throws Exception {
        if (!BackfillRange.stepIs(step)) {
            System.out.println(BackfillRange.STEP_ENV + "=" + step + " 일 때만 실행한다 - 건너뜀");
            return;
        }
        BackfillRange.assertSafeContext(context, dataSource);
        System.out.println("=== " + step + " 시작 (" + status + ", 배치 " + BATCH_SIZE + ") ===");
        long total = 0;
        while (true) {
            List<Long> ids = BackfillRange.rangeIdsWithStatus(jdbc, status, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            int processed = action.apply(ids);
            total += processed;
            Long failed = jdbc.queryForObject(
                    "SELECT count(*) FROM disclosures WHERE rcept_dt BETWEEN ? AND ? AND document_status = 'FAILED'",
                    Long.class, BackfillRange.FROM, BackfillRange.TO);
            System.out.println("배치: " + processed + "건 처리, 누적 " + total + "건, 구간 FAILED " + failed + "건");
            if (processed == 0) {
                break;
            }
        }
        System.out.println(step + " 완료: 총 " + total + "건");
    }
}

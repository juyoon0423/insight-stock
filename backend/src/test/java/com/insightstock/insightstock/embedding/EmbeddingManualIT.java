package com.insightstock.insightstock.embedding;

import com.insightstock.insightstock.backfill.BackfillRange;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필의 유일한 유료 단계 - 3단계(jtokkit 실측 비용 계산)에서 최종
// 승인된 청크 수를 확인하기 전까지는 절대 실행하지 않는다. 다른 백필 ManualIT(수집/
// 문서/청킹/색인)과 파일을 분리해 실수로 같이 실행될 여지를 없앴고, 승인 플래그를
// 이중으로(파일 단위 + 메서드 단위 limit 0) 둬서 manualTest를 통째로 돌려도 과금되지
// 않게 했다. embedding.collector.enabled=false를 유지해 스케줄러와 동시에 호출되는
// 경로도 막아야 한다(다른 안전장치와 별개로 실행 전 반드시 확인).
@Tag("manual")
// scheduling.enabled=false: 컨텍스트만 떠도 수집/색인 스케줄러가 대상 DB에 쓰는 것을 막는다.
@SpringBootTest(properties = "scheduling.enabled=false")
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

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    // ===== 2026-10 운영 DB 추가 색인(BackfillRange 구간) =====
    // 예산은 실행할 때 EMBEDDING_BUDGET_USD로 직접 준다(코드에 남기지 않음). 사전 추정이 예산을
    // 넘으면 시작하지 않고, 실행 중 누적 비용이 예산에 닿으면 다음 배치 전에 멈춘다.
    private static final String BUDGET_ENV = "EMBEDDING_BUDGET_USD";
    // 이 구간 비교 기준: 2026-07 백필 구간(같은 요일 구성의 5영업일 + 주말).
    private static final String BASELINE_FROM = "20260720";
    private static final String BASELINE_TO = "20260726";
    // 보고서명에서 [기재정정] 같은 앞머리와 (2026.06) 같은 괄호를 떼어 유형으로 묶는다.
    private static final String REPORT_TYPE_SQL =
            "regexp_replace(regexp_replace(report_nm, '^\\[[^]]*\\]', ''), '\\([^)]*\\)', '', 'g')";

    // 읽기 전용 - API 호출도 쓰기도 없다. 출력하는 것:
    //  1) 지금 임베딩 대기 청크의 실제 토큰 수와 비용(jtokkit)
    //  2) 대기 청크가 구간 밖/파일럿 밖에 있는지
    //  3) 구간 전체의 예상 비용 = 구간 공시의 유형별 건수 x 7월 같은 유형의 공시당 실측 토큰
    @Test
    void estimateRange() throws Exception {
        BackfillRange.assertSafeContext(context, dataSource);
        printPendingGuards();
        long[] pending = countPendingTokens();
        System.out.printf("임베딩 대기 청크: %d건, 토큰 %d개, 비용 $%.4f (jtokkit 실측)%n",
                pending[0], pending[1], cost(pending[1]));

        // 7월 유형별 공시당 토큰(원문 없는 공시는 0으로 포함). 7월에 없던 유형은 7월 전체 평균을 쓴다.
        String sql = """
                WITH base AS (
                  SELECT %1$s AS t, d.id, COALESCE(sum(c.token_count), 0) AS tokens
                  FROM disclosures d LEFT JOIN chunks c ON c.disclosure_id = d.id
                  WHERE d.rcept_dt BETWEEN ? AND ? GROUP BY 1, 2),
                per_type AS (SELECT t, avg(tokens) AS avg_tokens FROM base GROUP BY t),
                overall AS (SELECT avg(tokens) AS avg_tokens FROM base),
                target AS (SELECT %1$s AS t, count(*) AS n FROM disclosures
                           WHERE rcept_dt BETWEEN ? AND ? GROUP BY 1)
                SELECT sum(target.n) AS disclosures,
                       sum(target.n * COALESCE(per_type.avg_tokens, overall.avg_tokens)) AS tokens,
                       sum(CASE WHEN per_type.t IS NULL THEN target.n ELSE 0 END) AS unmatched
                FROM target LEFT JOIN per_type USING (t) CROSS JOIN overall
                """.formatted(REPORT_TYPE_SQL);
        jdbcTemplate.queryForList(sql, BASELINE_FROM, BASELINE_TO, BackfillRange.FROM, BackfillRange.TO)
                .forEach(row -> {
                    double tokens = row.get("tokens") == null ? 0 : ((Number) row.get("tokens")).doubleValue();
                    System.out.printf(
                            "구간 전체 예상(7월 유형별 실측 기준): 공시 %s건(7월에 없던 유형 %s건), 토큰 약 %.0f개, 비용 약 $%.2f%n",
                            row.get("disclosures"), row.get("unmatched"), tokens, cost((long) tokens));
                });
    }

    @Test
    void embedPilot() throws Exception {
        runBudgetedEmbedding("embed-pilot", true);
    }

    @Test
    void embedRange() throws Exception {
        runBudgetedEmbedding("embed-range", false);
    }

    private void runBudgetedEmbedding(String step, boolean pilotOnly) throws Exception {
        if (!BackfillRange.stepIs(step)) {
            System.out.println(BackfillRange.STEP_ENV + "=" + step + " 일 때만 실행한다 - 건너뜀");
            return;
        }
        BackfillRange.assertSafeContext(context, dataSource);
        double budget = readBudget();

        long[] guards = printPendingGuards();
        if (guards[0] > 0 || (pilotOnly && guards[1] > 0)) {
            throw new IllegalStateException("임베딩 대기 청크에 대상 밖 청크가 섞여 있다 - 중단");
        }
        long[] pending = countPendingTokens();
        double estimated = cost(pending[1]);
        System.out.printf("=== %s: 대기 %d건, 토큰 %d개, 사전 추정 $%.4f, 예산 $%.2f ===%n",
                step, pending[0], pending[1], estimated, budget);
        if (pending[0] == 0) {
            return;
        }
        if (estimated > budget) {
            throw new IllegalStateException("사전 추정 비용이 예산을 넘는다 - 시작하지 않음");
        }

        long baseTokens = embeddedTokensInRange();
        long processedTotal = 0;
        while (true) {
            long before = countPending();
            embeddingService.embedPending(BATCH_SIZE);
            long after = countPending();
            processedTotal += before - after;
            double spent = cost(embeddedTokensInRange() - baseTokens);
            System.out.printf("배치: 누적 %d건, 남은 대기 %d건, 이번 실행 누적 비용 $%.4f / 예산 $%.2f%n",
                    processedTotal, after, spent, budget);
            if (after == 0 || before == after) {
                break;
            }
            // 다음 배치(최대 BATCH_SIZE건)를 하면 예산을 넘을 수 있으면 여기서 멈춘다.
            double nextBatchMax = cost((long) BATCH_SIZE * 2_000);
            if (spent + nextBatchMax > budget) {
                System.out.println("예산 근접 - 다음 배치 전에 멈춤");
                break;
            }
        }
    }

    // [0] 구간 밖 대기 청크 수, [1] 파일럿 밖 대기 청크 수(구간 안이어도 파일럿이 아니면 셈)
    private long[] printPendingGuards() {
        Long outsideRange = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks c JOIN disclosures d ON d.id = c.disclosure_id "
                        + "WHERE c.embedding IS NULL AND d.rcept_dt NOT BETWEEN ? AND ?",
                Long.class, BackfillRange.FROM, BackfillRange.TO);
        List<Long> pilot = BackfillRange.pilotIds(jdbcTemplate);
        String pilotIn = pilot.stream().map(String::valueOf).collect(Collectors.joining(","));
        Long outsidePilot = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL AND disclosure_id NOT IN ("
                        + (pilotIn.isEmpty() ? "-1" : pilotIn) + ")",
                Long.class);
        System.out.println("대기 청크 중 구간 밖: " + outsideRange + "건, 파일럿 밖: " + outsidePilot + "건");
        return new long[] {outsideRange == null ? 0 : outsideRange, outsidePilot == null ? 0 : outsidePilot};
    }

    private long[] countPendingTokens() {
        long[] result = {0, 0};
        jdbcTemplate.query("SELECT content FROM chunks WHERE embedding IS NULL", rs -> {
            result[1] += tokenCounter.count(rs.getString("content"));
            result[0]++;
        });
        return result;
    }

    // 구간 청크 중 이미 임베딩된 것의 토큰 합(EmbeddingService가 저장한 jtokkit 값 - 7월 실측에서
    // OpenAI 응답의 usage와 정확히 일치했다).
    private long embeddedTokensInRange() {
        Long tokens = jdbcTemplate.queryForObject(
                "SELECT COALESCE(sum(c.token_count), 0) FROM chunks c JOIN disclosures d ON d.id = c.disclosure_id "
                        + "WHERE c.embedding IS NOT NULL AND d.rcept_dt BETWEEN ? AND ?",
                Long.class, BackfillRange.FROM, BackfillRange.TO);
        return tokens == null ? 0 : tokens;
    }

    private double readBudget() {
        String value = System.getenv(BUDGET_ENV);
        double budget = value == null ? 0 : Double.parseDouble(value);
        if (budget <= 0) {
            throw new IllegalStateException(BUDGET_ENV + "에 이번 실행의 예산(달러)을 지정해야 한다");
        }
        return budget;
    }

    private static double cost(long tokens) {
        return tokens / 1_000_000.0 * PRICE_PER_MILLION_TOKENS;
    }

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

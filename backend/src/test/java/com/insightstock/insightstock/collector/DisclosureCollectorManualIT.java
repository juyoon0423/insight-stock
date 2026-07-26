package com.insightstock.insightstock.collector;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 1단계 - DART list API로 과거 기간 공시를 수집한다(collect()는
// rcept_no 중복 체크로 멱등이라 기존 데이터와 겹치는 범위를 넣어도 안전). DART list API
// 자체는 무료지만 disclosures/companies 테이블을 대량으로 바꾸는 통합 테스트라
// CorpCodeSyncManualIT와 같은 패턴(@Tag("manual"), manualTest 태스크로만 실행)을 따른다.
@Tag("manual")
@SpringBootTest
class DisclosureCollectorManualIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 2단계 실행을 승인하기 전까지는 false로 두어, manualTest를 통째로 돌리거나
    // 실수로 이 테스트를 실행해도 실제 수집이 일어나지 않게 한다.
    private static final boolean BACKFILL_APPROVED = false;
    // 후보 기간(progress.md 데이터 규모 확대 논의) - 실행 전 필요하면 조정.
    private static final int BACKFILL_DAYS = 90;

    @Autowired
    private DisclosureCollectorService collectorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backfillRange() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 2단계 실행 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        LocalDate endDate = LocalDate.now(KST).minusDays(1);
        LocalDate beginDate = endDate.minusDays(BACKFILL_DAYS - 1);

        long beforeDisclosures = countDisclosures();
        long beforeCompanies = countCompanies();
        System.out.println("=== 기간 지정 수집 시작: " + beginDate + " ~ " + endDate + " ===");

        collectorService.collect(beginDate, endDate);

        long afterDisclosures = countDisclosures();
        long afterCompanies = countCompanies();
        System.out.println("disclosures 총 건수: " + beforeDisclosures + " -> " + afterDisclosures);
        System.out.println("companies 총 건수: " + beforeCompanies + " -> " + afterCompanies);
    }

    private long countDisclosures() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM disclosures", Long.class);
        return count == null ? 0 : count;
    }

    private long countCompanies() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Long.class);
        return count == null ? 0 : count;
    }
}

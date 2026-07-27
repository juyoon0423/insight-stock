package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.dart.DartListClient;
import com.insightstock.insightstock.dart.dto.DartListResponse;
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
    // 후보 기간(progress.md 데이터 규모 확대 논의) - peekCount() 실측(일평균 ~570건,
    // 90일 기준 55,310건이 예상의 8배였음) 이후 가장 보수적인 7일로 시작하기로 함.
    // peekCount()/backfillRange()가 같은 범위를 보도록 이 상수 하나로 공유한다.
    private static final int BACKFILL_DAYS = 7;

    @Autowired
    private DisclosureCollectorService collectorService;

    @Autowired
    private DartListClient dartListClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // DB에 아무 것도 쓰지 않는 순수 조회 - list API를 page_count=1로 구간마다 1번씩만
    // 불러 totalCount만 확인한다(1건만 받아와도 totalCount는 전체 범위 기준 값 그대로
    // 옴). 여러 구간(7/14/30/60/90일)을 한 번에 비교해 특정 기간에 몰린 건지, 전체가
    // 고르게 그런 건지 구분하기 위함. 실제 수집 전에 규모를 무료로 가늠하기 위한
    // 것이라 BACKFILL_APPROVED 가드가 필요 없다 - 이 메서드 자체가 쓰기 동작을 하지
    // 않는다.
    @Test
    void peekCount() {
        LocalDate endDate = LocalDate.now(KST).minusDays(1);
        int[] windows = {7, 14, 30, 60, 90};

        System.out.println("=== 규모 사전 확인 (DB 변경 없음), 기준일=" + endDate + " ===");
        for (int days : windows) {
            LocalDate beginDate = endDate.minusDays(days - 1);
            DartListResponse response = dartListClient.fetchList(beginDate, endDate, 1, 1);
            double perDay = response.totalCount() == null ? 0 : response.totalCount() / (double) days;
            System.out.printf(
                    "최근 %3d일 (%s ~ %s): status=%s, totalCount=%s, 일평균=%.1f건/일%n",
                    days, beginDate, endDate, response.status(), response.totalCount(), perDay);
        }
    }

    @Test
    void backfillRange() {
        if (!BACKFILL_APPROVED) {
            System.out.println("BACKFILL_APPROVED=false - 2단계 실행 승인 후 true로 바꾸고 재실행하세요.");
            return;
        }

        LocalDate[] range = resolveRange();
        LocalDate beginDate = range[0];
        LocalDate endDate = range[1];

        long beforeDisclosures = countDisclosures();
        long beforeCompanies = countCompanies();
        System.out.println("=== 기간 지정 수집 시작: " + beginDate + " ~ " + endDate + " ===");

        collectorService.collect(beginDate, endDate);

        long afterDisclosures = countDisclosures();
        long afterCompanies = countCompanies();
        System.out.println("disclosures 총 건수: " + beforeDisclosures + " -> " + afterDisclosures);
        System.out.println("companies 총 건수: " + beforeCompanies + " -> " + afterCompanies);
    }

    private LocalDate[] resolveRange() {
        LocalDate endDate = LocalDate.now(KST).minusDays(1);
        LocalDate beginDate = endDate.minusDays(BACKFILL_DAYS - 1);
        return new LocalDate[] {beginDate, endDate};
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

package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.backfill.BackfillRange;
import com.insightstock.insightstock.dart.DartListClient;
import com.insightstock.insightstock.dart.dto.DartListResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

// 데이터 규모 확대 백필 1단계 - DART list API로 과거 기간 공시를 수집한다(collect()는
// rcept_no 중복 체크로 멱등이라 기존 데이터와 겹치는 범위를 넣어도 안전). DART list API
// 자체는 무료지만 disclosures/companies 테이블을 대량으로 바꾸는 통합 테스트라
// CorpCodeSyncManualIT와 같은 패턴(@Tag("manual"), manualTest 태스크로만 실행)을 따른다.
@Tag("manual")
// scheduling.enabled=false: 컨텍스트만 떠도 수집 스케줄러가 대상 DB에 쓰는 것을 막는다.
@SpringBootTest(properties = "scheduling.enabled=false")
class DisclosureCollectorManualIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 실행할 때 BACKFILL_STEP=collect-list를 직접 지정해야만 수집한다(코드에 true를 남기지 않음).
    // manualTest를 통째로 돌리거나 실수로 이 테스트를 실행해도 실제 수집이 일어나지 않게 한다.
    // 2026-07 백필(최근 7일) 때는 코드 상수 BACKFILL_APPROVED를 true/false로 바꿔가며 실행했다.
    private static final String STEP = "collect-list";

    @Autowired
    private DisclosureCollectorService collectorService;

    @Autowired
    private DartListClient dartListClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

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
    void backfillRange() throws Exception {
        if (!BackfillRange.stepIs(STEP)) {
            System.out.println(BackfillRange.STEP_ENV + "=" + STEP + " 일 때만 실행한다 - 건너뜀");
            return;
        }
        BackfillRange.assertSafeContext(context, dataSource);

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

    // 2026-10 운영 DB 추가 색인: 고정 구간(BackfillRange, 10/20~10/26). 예전에는 "어제 기준
    // 최근 7일"이었는데, 실행 날짜에 따라 구간이 바뀌어 다른 단계와 어긋날 수 있었다.
    private LocalDate[] resolveRange() {
        DateTimeFormatter yyyymmdd = DateTimeFormatter.BASIC_ISO_DATE;
        return new LocalDate[] {
                LocalDate.parse(BackfillRange.FROM, yyyymmdd), LocalDate.parse(BackfillRange.TO, yyyymmdd)};
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

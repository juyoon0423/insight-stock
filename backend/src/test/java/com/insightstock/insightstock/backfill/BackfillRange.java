package com.insightstock.insightstock.backfill;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

// 2026-10 운영 DB 추가 색인(10/20~10/26 접수분)용 공통 값과 안전장치.
// 실행기(*ManualIT)들이 같은 구간과 같은 파일럿 대상을 보도록 여기 한 곳에서만 정의한다.
public final class BackfillRange {

    // 접수일 구간(rcept_dt는 yyyyMMdd 문자열이라 BETWEEN이 날짜 순서와 같다).
    // 10/20(화)~10/26(월) 5영업일 - DART 기준 약 2,862건(2026-10-27 조회).
    public static final String FROM = "20261020";
    public static final String TO = "20261026";

    // 운영 DB는 SSH 터널(docs/deploy.md 24절)로 로컬 15432에 붙인다. 로컬 개발 DB(5432)에
    // 실수로 쓰지 않도록, 접속 URL이 이 포트가 아니면 실행기가 아무것도 하지 않고 실패한다.
    public static final String TUNNEL_PORT = ":15432/";

    // 실행할 단계 이름을 환경변수로 직접 지정해야만 쓰기 동작을 한다(예: BACKFILL_STEP=pilot-documents).
    // manualTest를 통째로 돌리거나 다른 메서드를 실수로 골라도 쓰기가 일어나지 않게 하기 위함.
    public static final String STEP_ENV = "BACKFILL_STEP";

    // 파일럿 20건의 유형별 배분. 청크 수를 좌우하는 큰 문서(반기보고서·투자설명서·증권신고서·
    // 감사보고서)를 일부러 넣고, 나머지는 이 구간에 많은 유형과 그 밖의 유형으로 채운다.
    // 유형은 보고서명 "앞부분"으로 맞춘다([기재정정] 같은 머리말은 허용) - 처음에 '%증권신고서%'로
    // 했더니 "효력발생안내( ... 제출 증권신고서 )" 같은 한두 줄짜리 안내가 큰 문서 자리에 뽑혔다.
    // 같은 유형 안에서는 md5(rcept_no) 순서 - 구간 전체에서 고르게 섞이고, 상태와 무관하게
    // 고르므로 단계가 바뀌어도 대상이 같다.
    private static final String PREFIX = "^(\\[[^]]*\\])?";
    private static final String[][] PILOT_QUOTAS = {
            {PREFIX + "반기보고서", "2"},
            {PREFIX + "투자설명서", "2"},
            {PREFIX + "증권신고서", "2"},
            {PREFIX + "감사보고서", "1"},
            {PREFIX + "주요사항보고서", "2"},
            {PREFIX + "임원ㆍ주요주주", "2"},
            {PREFIX + "주식등의대량보유", "1"},
            {PREFIX + "증권발행실적", "1"},
            {PREFIX + "단일판매", "1"},
            {PREFIX + "기업설명회", "1"},
            {PREFIX + "일괄신고", "1"},
    };
    private static final int PILOT_OTHERS = 4;
    public static final int PILOT_SIZE = 20;

    private BackfillRange() {
    }

    public static boolean stepIs(String step) {
        return step.equals(System.getenv(STEP_ENV));
    }

    // 쓰기 전에 반드시 호출: 스케줄러가 꺼져 있고, 접속 대상이 터널(운영 DB)인지 확인한다.
    public static void assertSafeContext(ApplicationContext context, DataSource dataSource) throws Exception {
        if (context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
            throw new IllegalStateException("스케줄링이 켜져 있다 - scheduling.enabled=false가 적용되지 않음");
        }
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (!url.contains(TUNNEL_PORT)) {
                throw new IllegalStateException("접속 대상이 터널(15432)이 아니다 - 로컬 DB 보호를 위해 중단");
            }
        }
    }

    // 파일럿 대상 id 20건. 같은 DB 상태(구간의 공시 목록)에서는 항상 같은 결과가 나온다.
    public static List<Long> pilotIds(JdbcTemplate jdbc) {
        List<Long> chosen = new ArrayList<>();
        for (String[] quota : PILOT_QUOTAS) {
            chosen.addAll(jdbc.queryForList(
                    "SELECT id FROM disclosures WHERE rcept_dt BETWEEN ? AND ? AND report_nm ~ ? "
                            + "ORDER BY md5(rcept_no) LIMIT ?",
                    Long.class, FROM, TO, quota[0], Integer.parseInt(quota[1])));
        }
        StringBuilder notLike = new StringBuilder();
        List<Object> args = new ArrayList<>(List.of(FROM, TO));
        for (String[] quota : PILOT_QUOTAS) {
            notLike.append(" AND report_nm !~ ?");
            args.add(quota[0]);
        }
        args.add(PILOT_OTHERS);
        chosen.addAll(jdbc.queryForList(
                "SELECT id FROM disclosures WHERE rcept_dt BETWEEN ? AND ?" + notLike + " ORDER BY md5(rcept_no) LIMIT ?",
                Long.class, args.toArray()));
        return chosen;
    }

    // 구간 안에서 특정 상태인 공시 id(작은 순서, 최대 limit건).
    public static List<Long> rangeIdsWithStatus(JdbcTemplate jdbc, String status, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM disclosures WHERE rcept_dt BETWEEN ? AND ? AND document_status = ? "
                        + "ORDER BY id LIMIT ?",
                Long.class, FROM, TO, status, limit);
    }
}

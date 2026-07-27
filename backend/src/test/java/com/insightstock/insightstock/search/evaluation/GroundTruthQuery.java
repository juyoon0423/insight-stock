package com.insightstock.insightstock.search.evaluation;

// 검색 품질 측정용 질의 1건. 채점에 실제로 쓰이는 값은 targetRceptNo뿐이고,
// corpCode/corpName/reportNm은 이 질의를 만들 때 근거를 남기기 위한 기록용이다
// (report_nm 문자열 재매칭으로 채점하지 않는다 - [기재정정] 등 표기 변형 문제를
// 원천 회피하기 위함, search-quality-queries.json의 _readme 참고).
//
// category/theme은 둘 다 nullable이다 - 정형 벤치마크(search-quality-queries.json)는
// category(KEYWORD_FRIENDLY/EVENT)만 쓰고 theme이 없고, 서술형 벤치마크
// (search-quality-queries-narrative.json)는 반대로 theme(차입금/매출·실적/합병목적
// 등)만 쓰고 category가 없다 - 같은 로더/평가기를 두 파일에 그대로 재사용하기 위함.
public record GroundTruthQuery(
        String id,
        QueryCategory category,
        String theme,
        String query,
        boolean lexicalMismatch,
        String mismatchNote,
        String targetCorpCode,
        String targetCorpName,
        String targetReportNm,
        String targetRceptNo) {
}

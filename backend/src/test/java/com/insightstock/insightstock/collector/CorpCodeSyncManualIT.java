package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.dart.dto.CorpCodeRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// corpCode.xml 전체(118,535건) 적재 전에 소규모로 먼저 검증하는 수동 테스트. DART
// corpCode.xml 자체는 무료 API지만, 실제 네트워크 호출 + companies 테이블을 직접
// 변경하는 통합 테스트라 HybridSearchManualIT/AnswerServiceManualIT와 같은 패턴
// (@Tag("manual"), manualTest 태스크로만 실행)을 따라 기본 `./gradlew test`에서 제외한다.
@Tag("manual")
@SpringBootTest
class CorpCodeSyncManualIT {

    // 실측(dart-findings.md 2-11)에서 발견한 실제 상호변경 사례 - 기존 DB에는
    // "우정바이오"로 저장돼 있는데 corpCode.xml에서는 "콜마바이오텍"이었다.
    // 덮어쓰기 로직이 실제로 동작하는지 이 건을 파일럿에 포함시켜 직접 확인한다.
    private static final String KNOWN_RENAME_CORP_CODE = "01065013";

    @Autowired
    private CorpCodeSyncService corpCodeSyncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pilotSync() {
        List<CorpCodeRecord> allRecords = corpCodeSyncService.fetchAndParse();
        System.out.println("=== corpCode.xml 파싱 결과: 총 " + allRecords.size() + "건 ===");

        Optional<CorpCodeRecord> renameCase = allRecords.stream()
                .filter(r -> r.corpCode().equals(KNOWN_RENAME_CORP_CODE))
                .findFirst();
        renameCase.ifPresent(r -> System.out.println("상호변경 케이스 확인: " + r));

        System.out.println("적재 전 DB corp_name(" + KNOWN_RENAME_CORP_CODE + "): "
                + queryCorpName(KNOWN_RENAME_CORP_CODE));
        long beforeCount = countCompanies();

        List<CorpCodeRecord> pilotBatch = new ArrayList<>(allRecords.subList(0, 500));
        renameCase.ifPresent(pilotBatch::add);

        int processed = corpCodeSyncService.upsert(pilotBatch);
        System.out.println("파일럿 upsert 처리 건수: " + processed);

        long afterCount = countCompanies();
        System.out.println("companies 총 건수: " + beforeCount + " -> " + afterCount);
        System.out.println("적재 후 DB corp_name(" + KNOWN_RENAME_CORP_CODE + "): "
                + queryCorpName(KNOWN_RENAME_CORP_CODE));
    }

    @Test
    void fullSync() {
        long beforeCount = countCompanies();
        corpCodeSyncService.syncAll();
        long afterCount = countCompanies();
        System.out.println("=== 전체 동기화 완료: companies 총 건수 " + beforeCount + " -> " + afterCount + " ===");
    }

    private long countCompanies() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Long.class);
        return count == null ? 0 : count;
    }

    private String queryCorpName(String corpCode) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT corp_name FROM companies WHERE corp_code = ?", String.class, corpCode);
        return names.isEmpty() ? "(없음)" : names.get(0);
    }
}

package com.insightstock.insightstock.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.User;
import com.insightstock.insightstock.domain.Watchlist;
import com.insightstock.insightstock.repository.CompanyRepository;
import com.insightstock.insightstock.repository.DisclosureRepository;
import com.insightstock.insightstock.repository.UserRepository;
import com.insightstock.insightstock.repository.WatchlistRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// watchlist 등록 시각 이후에 생긴 공시만 알림으로 잡히는지, 같은 감지를 다시 돌려도
// ON CONFLICT DO NOTHING 덕분에 중복이 안 생기는지를 실제 로컬 dev DB에 대고 검증한다.
// 테스트 클래스 자체를 @Transactional로 감싸 종료 시 Spring이 자동 롤백하므로,
// ChunkingManualIT류(BACKFILL_APPROVED 게이트로 실수 실행을 막는 방식)와 달리 실제
// 데이터를 영구적으로 남기지 않는다 - 외부 유료 API 호출도 없어 @Tag("manual") 없이
// 기본 test에 포함한다.
//
// 감지 쿼리는 watchlist 전체를 대상으로 하는 글로벌 배치라, 기존 watchlist 행을 지우지
// 않으면 이 테스트가 로컬 dev DB에 이미 있는 실제 watchlist(다른 회사/사용자)까지 함께
// 매칭 대상으로 삼는다 - 결과 자체는 user_id로 필터링해 틀리지 않지만, notification_id_seq를
// 실제 데이터만큼 불필요하게 소모하고(Postgres 시퀀스는 ON CONFLICT/ROLLBACK과 무관하게
// 항상 전진한다 - 실측 확인함) 다른 환경(예: watchlist가 비어있는 신규 DB)에서 결과 건수가
// 달라질 여지를 남긴다. 테스트 트랜잭션 안에서만 유효한 삭제(끝나면 롤백되어 실제 데이터는
// 그대로)로 기존 watchlist를 비워, 이 테스트가 만든 픽스처만 감지 대상이 되도록 격리한다.
@SpringBootTest
@Transactional
class NotificationDetectionServiceTest {

    @Autowired
    private NotificationDetectionService detectionService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DisclosureRepository disclosureRepository;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void detectsOnlyDisclosuresAfterWatchlistRegistration() {
        // 이 테스트 트랜잭션 범위 안에서만 유효 - 끝나면 @Transactional이 롤백하므로 실제
        // watchlist는 그대로 남는다. notification은 watchlist가 아니라 user/disclosure를
        // FK로 참조하므로 이 삭제로 기존 notification 행이 cascade로 지워지지 않는다.
        jdbcTemplate.update("DELETE FROM watchlist");

        Company company = persistCompany();
        User user = persistUser();
        Watchlist watchlist = persistWatchlist(user, company);

        Instant watchlistCreatedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        setWatchlistCreatedAt(watchlist.getId(), watchlistCreatedAt);

        Disclosure newDisclosure = persistDisclosure(company, "TEST-NOTIF-NEW");
        setDisclosureCreatedAt(newDisclosure.getId(), watchlistCreatedAt.plusSeconds(60));

        Disclosure oldDisclosure = persistDisclosure(company, "TEST-NOTIF-OLD");
        setDisclosureCreatedAt(oldDisclosure.getId(), watchlistCreatedAt.minusSeconds(60));

        // 네이티브 감지 쿼리는 영속성 컨텍스트를 거치지 않고 테이블을 직접 읽으므로,
        // 위에서 저장/수정한 내용이 먼저 flush돼야 감지 대상에 잡힌다.
        entityManager.flush();

        detectionService.detectNew();

        assertThat(existsNotification(user.getId(), newDisclosure.getId())).isTrue();
        assertThat(existsNotification(user.getId(), oldDisclosure.getId())).isFalse();

        long countAfterFirstRun = countNotifications(user.getId());

        // 같은 감지를 재실행해도 ON CONFLICT DO NOTHING으로 중복이 생기면 안 된다.
        detectionService.detectNew();
        long countAfterSecondRun = countNotifications(user.getId());

        assertThat(countAfterSecondRun).isEqualTo(countAfterFirstRun);
    }

    private Company persistCompany() {
        Company company = new Company();
        company.setCorpCode("TEST-NOTIF-" + System.nanoTime());
        company.setCorpName("알림감지테스트회사");
        return companyRepository.save(company);
    }

    private User persistUser() {
        User user = new User();
        user.setEmail("notif-test-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setNickname("알림테스트유저");
        return userRepository.save(user);
    }

    private Watchlist persistWatchlist(User user, Company company) {
        Watchlist watchlist = new Watchlist();
        watchlist.setUser(user);
        watchlist.setCompany(company);
        return watchlistRepository.save(watchlist);
    }

    private Disclosure persistDisclosure(Company company, String rceptNoPrefix) {
        Disclosure disclosure = new Disclosure();
        disclosure.setCompany(company);
        disclosure.setRceptNo(rceptNoPrefix + "-" + System.nanoTime());
        disclosure.setReportNm("테스트 공시");
        disclosure.setRceptDt("20260727");
        disclosure.setHasDocument(false);
        return disclosureRepository.save(disclosure);
    }

    // watchlist.created_at/disclosures.created_at은 @CreationTimestamp라 엔티티 setter로는
    // 값을 제어할 수 없다(insert 시점에 Hibernate가 무조건 now()로 덮어씀) - 감지 조건
    // (d.created_at > w.created_at) 성립 여부를 결정적으로 만들기 위해 저장 직후 JDBC로
    // 직접 덮어쓴다.
    private void setWatchlistCreatedAt(Long id, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE watchlist SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }

    private void setDisclosureCreatedAt(Long id, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE disclosures SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }

    private boolean existsNotification(Long userId, Long disclosureId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE user_id = ? AND disclosure_id = ?",
                Long.class, userId, disclosureId);
        return count != null && count > 0;
    }

    private long countNotifications(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }
}

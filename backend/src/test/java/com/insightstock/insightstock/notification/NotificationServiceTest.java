package com.insightstock.insightstock.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.Notification;
import com.insightstock.insightstock.domain.User;
import com.insightstock.insightstock.repository.CompanyRepository;
import com.insightstock.insightstock.repository.DisclosureRepository;
import com.insightstock.insightstock.repository.NotificationRepository;
import com.insightstock.insightstock.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 알림 조회/읽음 처리의 핵심 요구사항 - "본인 것만" - 을 실제 DB에 두 사용자 데이터를
// 넣고 검증한다. list()/countUnread()/markAsRead() 전부 특정 user_id/notification_id로
// 좁혀 조회하므로, NotificationDetectionServiceTest와 달리 기존 watchlist/notification
// 데이터를 지울 필요 없이(이 테스트는 자기가 만든 사용자/알림만 다룬다) 자연히 격리된다.
// 테스트 클래스를 @Transactional로 감싸 종료 시 자동 롤백 - 유료 API 호출도 없어
// @Tag("manual") 없이 기본 test에 포함한다.
@SpringBootTest
@Transactional
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DisclosureRepository disclosureRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void ownerCanListAndReadOwnNotification() {
        Company company = persistCompany();
        User owner = persistUser("owner");
        Disclosure disclosure = persistDisclosure(company);
        Notification notification = persistNotification(owner, disclosure);

        var items = notificationService.list(owner.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getId()).isEqualTo(notification.getId());
        assertThat(items.get(0).getDisclosure().getReportNm()).isEqualTo(disclosure.getReportNm());
        assertThat(items.get(0).getDisclosure().getCompany().getCorpName()).isEqualTo(company.getCorpName());

        assertThat(notificationService.countUnread(owner.getId())).isEqualTo(1);

        notificationService.markAsRead(notification.getId(), owner.getId());
        assertThat(notificationService.countUnread(owner.getId())).isEqualTo(0);

        // 이미 읽음 처리된 알림을 다시 읽음 처리해도 예외 없이 안전해야 한다(idempotent).
        notificationService.markAsRead(notification.getId(), owner.getId());
        assertThat(notificationService.countUnread(owner.getId())).isEqualTo(0);
    }

    @Test
    void otherUserCannotReadSomeoneElsesNotification() {
        Company company = persistCompany();
        User owner = persistUser("owner");
        User stranger = persistUser("stranger");
        Disclosure disclosure = persistDisclosure(company);
        Notification notification = persistNotification(owner, disclosure);

        // 다른 사용자의 notification id로 읽음 처리를 시도하면, 존재 자체를 노출하지 않고
        // WatchlistNotFoundException과 동일한 방식으로 404에 대응하는 예외를 던져야 한다.
        assertThatThrownBy(() -> notificationService.markAsRead(notification.getId(), stranger.getId()))
                .isInstanceOf(NotificationNotFoundException.class);

        // 침입 시도가 실패한 뒤에도 원래 소유자 기준으로는 여전히 안 읽음 상태여야 한다.
        assertThat(notificationService.countUnread(owner.getId())).isEqualTo(1);
        // 다른 사용자에게는 애초에 이 알림이 보이지 않는다(목록도, 미읽음 건수도).
        assertThat(notificationService.list(stranger.getId())).isEmpty();
        assertThat(notificationService.countUnread(stranger.getId())).isEqualTo(0);
    }

    private Company persistCompany() {
        Company company = new Company();
        company.setCorpCode("TEST-NOTIF-API-" + System.nanoTime());
        company.setCorpName("알림API테스트회사");
        return companyRepository.save(company);
    }

    private User persistUser(String label) {
        User user = new User();
        user.setEmail("notif-api-test-" + label + "-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setNickname("알림API테스트-" + label);
        return userRepository.save(user);
    }

    private Disclosure persistDisclosure(Company company) {
        Disclosure disclosure = new Disclosure();
        disclosure.setCompany(company);
        disclosure.setRceptNo("TEST-NOTIF-API-" + System.nanoTime());
        disclosure.setReportNm("테스트 공시(알림 API)");
        disclosure.setRceptDt("20260727");
        disclosure.setHasDocument(false);
        return disclosureRepository.save(disclosure);
    }

    private Notification persistNotification(User user, Disclosure disclosure) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setDisclosure(disclosure);
        return notificationRepository.save(notification);
    }
}

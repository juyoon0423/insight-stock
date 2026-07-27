package com.insightstock.insightstock.notification;

import com.insightstock.insightstock.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// watchlist에 등록된 회사의 신규 공시를 감지해 notification 행으로 만든다.
// 감지 로직 자체는 NotificationRepository.detectAndInsertNotifications() 단일 쿼리가 전부고,
// 이 서비스는 그 쿼리를 트랜잭션으로 감싸고 결과 건수를 로그로 남기는 역할만 한다.
@Slf4j
@Service
public class NotificationDetectionService {

    private final NotificationRepository notificationRepository;

    public NotificationDetectionService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void detectNew() {
        int insertedCount = notificationRepository.detectAndInsertNotifications();
        log.info("알림 감지 배치 완료: 신규 {}건", insertedCount);
    }
}

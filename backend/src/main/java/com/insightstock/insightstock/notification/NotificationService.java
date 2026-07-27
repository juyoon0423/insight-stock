package com.insightstock.insightstock.notification;

import com.insightstock.insightstock.domain.Notification;
import com.insightstock.insightstock.repository.NotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 알림 조회/읽음 처리 API 전용 서비스 - watchlist에 등록된 회사의 신규 공시를 찾아 알림
// 행을 만드는 배치 관심사(NotificationDetectionService)와는 별개로 분리했다.
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public List<Notification> list(Long userId) {
        return notificationRepository.findByUserIdWithDisclosureAndCompany(userId);
    }

    public long countUnread(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markAsRead(Long id, Long userId) {
        // 소유권을 쿼리 조건(user_id)에 녹였다 - 본인 것이 아니면 갱신 0행이 되어 "찾지
        // 못함"과 구분 없이 404로 처리된다(WatchlistService.remove()와 동일 패턴).
        if (notificationRepository.markAsRead(id, userId) == 0) {
            throw new NotificationNotFoundException();
        }
    }
}

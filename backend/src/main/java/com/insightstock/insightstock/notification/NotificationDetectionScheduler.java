package com.insightstock.insightstock.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// notification.collector.enabled=false로 끄면 자동 배치 없이 감지를 멈출 수 있다(다른
// collector들과 같은 패턴). 수집/청킹 스케줄러와 의도적으로 분리된 별도 스케줄러다 -
// 나중에 무중단 배포로 인스턴스가 잠깐 2개 뜰 때, 감지 로직이 수집 로직에 섞여 있으면
// 중복 실행 위험이 있는데 관심사를 분리해두면 "이 스케줄러는 한 인스턴스만" 같은 제어를
// 걸기 쉬워진다(설계 논의, progress.md 참고).
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.collector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDetectionScheduler {

    private final NotificationDetectionService detectionService;

    public NotificationDetectionScheduler(NotificationDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @Scheduled(fixedDelayString = "${notification.collector.fixed-delay-ms}")
    public void detect() {
        try {
            detectionService.detectNew();
        } catch (Exception e) {
            log.error("알림 감지 배치 실패", e);
        }
    }
}

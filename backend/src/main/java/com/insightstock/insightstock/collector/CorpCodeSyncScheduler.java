package com.insightstock.insightstock.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// list/document 스케줄러(10분 간격)와 달리 corpCode.xml은 전체 스냅샷이라 하루 한 번이면
// 충분하다(cron은 application.yml의 corp-code-sync.collector.cron 참고). 118,535건 전체를
// 매번 다시 훑는 무거운 배치라, 기본값은 비활성화(다른 collector 스케줄러들이 검증 전
// enabled: false로 시작했던 것과 같은 안전장치).
@Slf4j
@Component
@ConditionalOnProperty(prefix = "corp-code-sync.collector", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CorpCodeSyncScheduler {

    private final CorpCodeSyncService corpCodeSyncService;

    public CorpCodeSyncScheduler(CorpCodeSyncService corpCodeSyncService) {
        this.corpCodeSyncService = corpCodeSyncService;
    }

    @Scheduled(cron = "${corp-code-sync.collector.cron}")
    public void sync() {
        try {
            corpCodeSyncService.syncAll();
        } catch (Exception e) {
            log.error("corpCode.xml 동기화 배치 실패", e);
        }
    }
}

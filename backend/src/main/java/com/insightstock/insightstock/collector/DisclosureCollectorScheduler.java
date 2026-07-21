package com.insightstock.insightstock.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DisclosureCollectorScheduler {

    private final DisclosureCollectorService collectorService;

    public DisclosureCollectorScheduler(DisclosureCollectorService collectorService) {
        this.collectorService = collectorService;
    }

    @Scheduled(fixedDelayString = "${dart.collector.fixed-delay-ms}")
    public void collect() {
        try {
            collectorService.collectToday();
        } catch (Exception e) {
            log.error("DART 공시 수집 배치 실패", e);
        }
    }
}

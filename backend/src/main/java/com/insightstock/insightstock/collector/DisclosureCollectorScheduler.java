package com.insightstock.insightstock.collector;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DisclosureCollectorScheduler {

    private final DisclosureCollectorService collectorService;

    public DisclosureCollectorScheduler(DisclosureCollectorService collectorService) {
        this.collectorService = collectorService;
    }

    // fixed-delay 10분(600,000ms)보다 넉넉한 15분을 lockAtMostFor로 잡는다 - 인스턴스가
    // 크래시해 락을 못 놓아도 15분 뒤엔 다른 인스턴스가 다시 시도할 수 있게 하는 안전장치.
    // lockAtLeastFor는 blue-green 전환 중인 두 인스턴스 간 시계가 살짝 어긋나도 락 해제
    // 직후 즉시 재실행되는 걸 막기 위한 최소값(0은 이 방어력이 없어져서 피함).
    @Scheduled(fixedDelayString = "${dart.collector.fixed-delay-ms}")
    @SchedulerLock(name = "disclosureCollector", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
    public void collect() {
        try {
            collectorService.collectToday();
        } catch (Exception e) {
            log.error("DART 공시 수집 배치 실패", e);
        }
    }
}

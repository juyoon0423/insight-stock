package com.insightstock.insightstock.indexing;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "indexing.collector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IndexingScheduler {

    private final IndexingService indexingService;
    private final int batchSize;

    public IndexingScheduler(
            IndexingService indexingService,
            @Value("${indexing.collector.batch-size}") int batchSize) {
        this.indexingService = indexingService;
        this.batchSize = batchSize;
    }

    // lockAtMostFor/lockAtLeastFor 근거는 DisclosureCollectorScheduler와 동일
    // (fixed-delay 10분 대비 넉넉한 15분, 시계 어긋남 방어용 최소 5초).
    @Scheduled(fixedDelayString = "${indexing.collector.fixed-delay-ms}")
    @SchedulerLock(name = "indexing", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
    public void collect() {
        try {
            indexingService.indexPending(batchSize);
        } catch (Exception e) {
            log.error("색인 배치 실패", e);
        }
    }
}

package com.insightstock.insightstock.collector;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DocumentCollectorScheduler {

    private final DocumentCollectorService documentCollectorService;
    private final int batchSize;

    public DocumentCollectorScheduler(
            DocumentCollectorService documentCollectorService,
            @Value("${dart.document-collector.batch-size}") int batchSize) {
        this.documentCollectorService = documentCollectorService;
        this.batchSize = batchSize;
    }

    // lockAtMostFor/lockAtLeastFor 근거는 DisclosureCollectorScheduler와 동일
    // (fixed-delay 10분 대비 넉넉한 15분, 시계 어긋남 방어용 최소 5초).
    @Scheduled(fixedDelayString = "${dart.document-collector.fixed-delay-ms}")
    @SchedulerLock(name = "documentCollector", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
    public void collect() {
        try {
            documentCollectorService.collectPending(batchSize);
        } catch (Exception e) {
            log.error("document 수집 배치 실패", e);
        }
    }
}

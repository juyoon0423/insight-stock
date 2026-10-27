package com.insightstock.insightstock.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 배치 전체를 켜고 끄는 스위치. 원래 InsightStockApplication에 @EnableScheduling이
// 직접 붙어 있어서, 수동 실행기(*ManualIT)처럼 앱 컨텍스트를 띄우기만 해도 공시 수집/원문
// 수집/색인 스케줄러가 즉시 같이 돌았다 - 특히 SSH 터널로 운영 DB에 붙인 상태에서는 의도하지
// 않은 쓰기가 운영 DB에 들어간다. scheduling.enabled=false일 때만 끄고, 값이 없으면 기존처럼
// 켠다(matchIfMissing=true) - 운영 서버와 bootRun 동작은 바뀌지 않는다.
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

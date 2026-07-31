package com.insightstock.insightstock.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 무중단(Blue-Green) 배포 중 인스턴스가 잠깐 2개 뜨는 구간에 @Scheduled 배치가 양쪽에서
// 동시 실행되는 걸 막기 위한 ShedLock 설정. 락 저장소는 이미 쓰고 있는 Postgres의
// shedlock 테이블(V7 마이그레이션)을 그대로 쓴다 - 새 인프라(Redis 등) 추가 불필요.
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}

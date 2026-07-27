package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // disclosure를 함께 쓰므로(목록에 report_nm 등을 보여줘야 함) fetch join으로 N+1을 피한다.
    @Query("SELECT n FROM Notification n JOIN FETCH n.disclosure WHERE n.user.id = :userId ORDER BY n.createdAt DESC")
    List<Notification> findByUserIdWithDisclosure(@Param("userId") Long userId);

    long countByUserIdAndReadFalse(Long userId);

    // watchlist ⋈ disclosures를 company_id로 조인해 "관심 등록 이후 신규 공시"를 찾아 그대로
    // INSERT한다. Java 루프로 건별 저장하는 대신 단일 INSERT ... SELECT로 처리 - chunks의
    // raw_content처럼 대량 텍스트를 로딩하는 게 아니라 두 테이블의 id/시각만 비교하는 순수
    // 조인이라 이 방식이 적합하다. ON CONFLICT DO NOTHING이 uk_notification_user_disclosure에
    // 걸려 스케줄러가 겹치는 범위를 재실행해도 중복 없이 안전하다(watchlist의 동일 패턴과 같음).
    // disclosures.created_at에는 인덱스가 없어 Seq Scan을 타는데(EXPLAIN ANALYZE로 확인,
    // progress.md 참고), 새로 생긴 공시 수가 아니라 disclosures 테이블 전체 크기에 비례해
    // 자라는 비용이라 지금 규모(수천 건)에서는 문제 없다 - 1만 건을 넘어가면 재측정 후
    // idx_disclosures_created_at 추가를 검토할 것("먼저 실측하고 설계한다" 원칙, pg_trgm
    // 사례와 동일한 이유로 지금 미리 넣지 않음).
    @Modifying
    @Query(
            value = """
                    INSERT INTO notification (user_id, disclosure_id)
                    SELECT w.user_id, d.id
                    FROM watchlist w
                    JOIN disclosures d ON d.company_id = w.company_id
                    WHERE d.created_at > w.created_at
                    ON CONFLICT (user_id, disclosure_id) DO NOTHING
                    """,
            nativeQuery = true)
    int detectAndInsertNotifications();
}

package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 목록 화면에 회사명/보고서명을 같이 보여줘야 하므로 disclosure/company를 한 번에
    // fetch join한다(watchlist.company를 fetch join하는 WatchlistRepository와 동일한 이유 -
    // N+1 방지).
    @Query("SELECT n FROM Notification n JOIN FETCH n.disclosure d JOIN FETCH d.company "
            + "WHERE n.user.id = :userId ORDER BY n.createdAt DESC")
    List<Notification> findByUserIdWithDisclosureAndCompany(@Param("userId") Long userId);

    long countByUserIdAndReadFalse(Long userId);

    // 소유권을 쿼리 조건(user_id)에 녹였다 - WatchlistRepository.deleteByIdAndUserId()와
    // 동일 패턴. 본인 것이 아니면 0행이 되어 "찾지 못함"과 구분 없이 404로 처리된다.
    // WHERE 절이 is_read 상태를 따지지 않으므로 이미 읽음이어도 다시 호출하면 그대로
    // true로 갱신되고 1행이 반환된다 - 읽음 처리는 몇 번을 반복해도 안전(idempotent)하다.
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.user.id = :userId")
    int markAsRead(@Param("id") Long id, @Param("userId") Long userId);

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

package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // disclosure를 함께 쓰므로(목록에 report_nm 등을 보여줘야 함) fetch join으로 N+1을 피한다.
    @Query("SELECT n FROM Notification n JOIN FETCH n.disclosure WHERE n.user.id = :userId ORDER BY n.createdAt DESC")
    List<Notification> findByUserIdWithDisclosure(@Param("userId") Long userId);

    long countByUserIdAndReadFalse(Long userId);
}

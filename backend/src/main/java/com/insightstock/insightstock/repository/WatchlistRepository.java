package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Watchlist;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    boolean existsByUserIdAndCompanyId(Long userId, Long companyId);

    // company를 함께 쓰므로 fetch join으로 N+1을 피한다.
    @Query("SELECT w FROM Watchlist w JOIN FETCH w.company WHERE w.user.id = :userId ORDER BY w.createdAt DESC")
    List<Watchlist> findByUserIdWithCompany(@Param("userId") Long userId);

    // 소유권을 조건절(user_id)에 녹여 삭제 자체를 한 쿼리로 처리한다 - 존재 확인과 소유권
    // 확인을 별도로 하지 않아도 "본인 것이 아니면 0행 삭제"가 그대로 "찾지 못함"과 같은 의미가 된다.
    long deleteByIdAndUserId(Long id, Long userId);
}

package com.insightstock.insightstock.watchlist;

import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Watchlist;
import com.insightstock.insightstock.repository.CompanyRepository;
import com.insightstock.insightstock.repository.UserRepository;
import com.insightstock.insightstock.repository.WatchlistRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public WatchlistService(
            WatchlistRepository watchlistRepository,
            CompanyRepository companyRepository,
            UserRepository userRepository) {
        this.watchlistRepository = watchlistRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Watchlist create(Long userId, Long companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(CompanyNotFoundException::new);
        if (watchlistRepository.existsByUserIdAndCompanyId(userId, companyId)) {
            throw new DuplicateWatchlistException();
        }
        Watchlist watchlist = new Watchlist();
        // 이미 JWT로 인증된 userId라 User를 다시 조회하지 않고 프록시 참조만 붙인다.
        watchlist.setUser(userRepository.getReferenceById(userId));
        watchlist.setCompany(company);
        try {
            return watchlistRepository.save(watchlist);
        } catch (DataIntegrityViolationException e) {
            // 동시 등록 경합 대비 - existsByUserIdAndCompanyId 통과 후에도
            // uk_watchlist_user_company 제약이 걸릴 수 있다.
            throw new DuplicateWatchlistException();
        }
    }

    public List<Watchlist> list(Long userId) {
        return watchlistRepository.findByUserIdWithCompany(userId);
    }

    @Transactional
    public void remove(Long id, Long userId) {
        // 소유권을 쿼리 조건(user_id)에 녹였다 - 본인 것이 아니면 삭제 0행이 되어 "찾지
        // 못함"과 구분 없이 404로 처리된다(다른 사용자 항목의 존재 여부 노출 방지).
        if (watchlistRepository.deleteByIdAndUserId(id, userId) == 0) {
            throw new WatchlistNotFoundException();
        }
    }
}

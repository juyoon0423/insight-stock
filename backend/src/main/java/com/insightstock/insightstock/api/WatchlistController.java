package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.CompanySummary;
import com.insightstock.insightstock.api.dto.WatchlistCreateRequest;
import com.insightstock.insightstock.api.dto.WatchlistItemResponse;
import com.insightstock.insightstock.api.dto.WatchlistListResponse;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Watchlist;
import com.insightstock.insightstock.watchlist.WatchlistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 화이트리스트 방식이라 SecurityConfig에 별도 permitAll이 없는 이 경로는 전부 인증
// 필수(anyRequest().authenticated())로 보호된다. userId는 JwtAuthenticationFilter가
// SecurityContext principal에 이미 넣어둔 값을 그대로 받는다.
@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistItemResponse create(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody WatchlistCreateRequest request) {
        return toItemResponse(watchlistService.create(userId, request.companyId()));
    }

    @GetMapping
    public WatchlistListResponse list(@AuthenticationPrincipal Long userId) {
        return new WatchlistListResponse(watchlistService.list(userId).stream().map(this::toItemResponse).toList());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        watchlistService.remove(id, userId);
    }

    private WatchlistItemResponse toItemResponse(Watchlist watchlist) {
        Company company = watchlist.getCompany();
        return new WatchlistItemResponse(
                watchlist.getId(),
                new CompanySummary(
                        company.getId(), company.getCorpCode(), company.getCorpName(), company.getStockCode(),
                        company.getCorpCls()),
                watchlist.getCreatedAt());
    }
}

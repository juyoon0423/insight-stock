package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.CompanySummary;
import com.insightstock.insightstock.api.dto.NotificationItemResponse;
import com.insightstock.insightstock.api.dto.NotificationListResponse;
import com.insightstock.insightstock.api.dto.UnreadCountResponse;
import com.insightstock.insightstock.dart.DartUrls;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.Notification;
import com.insightstock.insightstock.notification.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 화이트리스트 방식이라 SecurityConfig에 별도 permitAll이 없는 이 경로는 전부 인증
// 필수(anyRequest().authenticated())로 보호된다. userId는 JwtAuthenticationFilter가
// SecurityContext principal에 이미 넣어둔 값을 그대로 받는다 - WatchlistController와
// 동일한 패턴, 클라이언트가 보낸 값은 신뢰하지 않는다.
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationListResponse list(@AuthenticationPrincipal Long userId) {
        return new NotificationListResponse(
                notificationService.list(userId).stream().map(this::toItemResponse).toList());
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Long userId) {
        return new UnreadCountResponse(notificationService.countUnread(userId));
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationService.markAsRead(id, userId);
    }

    private NotificationItemResponse toItemResponse(Notification notification) {
        Disclosure disclosure = notification.getDisclosure();
        Company company = disclosure.getCompany();
        return new NotificationItemResponse(
                notification.getId(),
                new CompanySummary(
                        company.getId(), company.getCorpCode(), company.getCorpName(), company.getStockCode(),
                        company.getCorpCls()),
                disclosure.getReportNm(),
                disclosure.getRceptNo(),
                DartUrls.viewerUrl(disclosure.getRceptNo()),
                notification.getCreatedAt(),
                notification.isRead());
    }
}

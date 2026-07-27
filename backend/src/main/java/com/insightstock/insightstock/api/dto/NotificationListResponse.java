package com.insightstock.insightstock.api.dto;

import java.util.List;

public record NotificationListResponse(List<NotificationItemResponse> notifications) {
}

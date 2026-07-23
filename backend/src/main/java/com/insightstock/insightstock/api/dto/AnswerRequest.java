package com.insightstock.insightstock.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

// topK는 null이면 AnswerService의 기본값(hybrid-search.top-k)을 쓴다. 상한 50은
// hybrid-search.candidate-size와 맞춘 값 - RRF가 후보 50개 안에서만 융합하므로
// 그 이상은 의미가 없다.
public record AnswerRequest(
        @NotBlank(message = "질문을 입력해주세요.") String query,
        @Min(value = 1, message = "topK는 1 이상이어야 합니다.")
                @Max(value = 50, message = "topK는 50 이하여야 합니다.")
                Integer topK) {
}

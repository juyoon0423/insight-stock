package com.insightstock.insightstock.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 이 API는 로그인 없이 열려 있고 호출마다 LLM 비용과 OpenAI TPM(분당 토큰 한도)을 쓴다.
// 그래서 요청 하나가 쓸 수 있는 토큰의 크기를 입력 단계에서 제한한다(요청 "횟수" 제한은
// Nginx limit_req가 맡는다 - docs/deploy.md 20절).
//
// query 500자: 공시에 대한 질문은 길어야 한두 문장이다(예시/평가 질의는 전부 60자 이내).
// 500자면 정상 질문은 막지 않으면서, 수만 자를 붙여 넣어 토큰을 태우는 요청은 막는다.
//
// topK는 null이면 AnswerService의 기본값(hybrid-search.top-k = 10)을 쓴다. 상한 20:
// 컨텍스트 청크 1건이 평균 약 650토큰이라 topK=50이면 요청 1건이 약 33,000토큰이고,
// 그런 요청 6건이면 TPM 한도 200,000(부하테스트에서 5 VU로도 닿았던 값 - docs/loadtest.md)이
// 소진된다. 20이면 약 13,000토큰으로 기본값의 2배까지만 허용한다. 프론트는 topK를 보내지
// 않으므로(기본 10) 화면 동작에는 영향이 없다.
public record AnswerRequest(
        @NotBlank(message = "질문을 입력해주세요.")
                @Size(max = 500, message = "질문은 500자 이하로 입력해주세요.")
                String query,
        @Min(value = 1, message = "topK는 1 이상이어야 합니다.")
                @Max(value = 20, message = "topK는 20 이하여야 합니다.")
                Integer topK) {
}

package com.insightstock.insightstock.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightstock.insightstock.rag.RagApiException;
import com.insightstock.insightstock.rag.RagRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// DB/ES/OpenAI 없이 예외 -> HTTP 응답 매핑만 검증한다. 예외를 던지기만 하는 테스트용
// 컨트롤러에 실제 GlobalExceptionHandler를 붙여 MockMvc로 호출한다.
class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {

        @GetMapping("/rate-limited")
        String rateLimited() {
            throw new RagRateLimitException("OpenAI 채팅 완성 API 호출 실패 (429 재시도 2회 소진)", null);
        }

        @GetMapping("/llm-down")
        String llmDown() {
            throw new RagApiException("OpenAI 채팅 완성 API 호출 실패", null);
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 속도제한_소진은_429와_LLM_RATE_LIMITED로_응답한다() throws Exception {
        mockMvc.perform(get("/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LLM_RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.instance").value("/rate-limited"));
    }

    @Test
    void 그_외_LLM_실패는_기존대로_502와_LLM_UNAVAILABLE로_응답한다() throws Exception {
        mockMvc.perform(get("/llm-down"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void 클라이언트에는_OpenAI_원본_에러_문구를_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/rate-limited"))
                .andExpect(jsonPath("$.detail").value("지금 요청이 몰려 답변을 생성하지 못했습니다. 잠시 후 다시 시도해주세요."));
    }
}

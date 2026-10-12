package com.insightstock.insightstock.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightstock.insightstock.rag.AnswerService;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.watchlist.WatchlistService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 클라이언트가 잘못 보낸 요청(깨진 JSON, 숫자 자리에 문자, 지원하지 않는 메서드 등)은
// 스프링 MVC가 컨트롤러 메서드를 부르기 전에 예외로 걸러낸다. 이 예외들에 전용 핸들러가
// 없으면 GlobalExceptionHandler의 Exception 핸들러가 받아 500/INTERNAL_ERROR가 된다 -
// 서버 잘못이 아닌데 "서버 오류"로 응답하고 error 로그까지 남는 문제를 검증한다.
// 실제 컨트롤러를 쓰되 서비스는 대역이라 DB/ES/OpenAI를 호출하지 않는다(비용 0).
class StandardClientErrorMappingTest {

    // 실제 앱은 정적 리소스 핸들러가 있어 없는 경로에서 NoResourceFoundException이 난다.
    // standalone MockMvc에는 그 핸들러가 없으므로(NoHandlerFoundException이 난다) 직접 던진다.
    @RestController
    static class MissingResourceController {

        @GetMapping("/missing-resource")
        String missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "api/v1/nope");
        }
    }

    private final AnswerService answerService = mock(AnswerService.class);
    private final HybridSearchService hybridSearchService = mock(HybridSearchService.class);
    private final WatchlistService watchlistService = mock(WatchlistService.class);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new AnswerController(answerService),
                    new SearchController(hybridSearchService, 10),
                    new WatchlistController(watchlistService),
                    new MissingResourceController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 깨진_JSON_본문은_400과_MALFORMED_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"삼성전자 배당"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.instance").value("/api/v1/answers"));
        verifyNoInteractions(answerService);
    }

    @Test
    void 본문의_필드_타입이_틀려도_400과_MALFORMED_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"삼성전자 배당\",\"topK\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void 쿼리_파라미터_타입_불일치는_400과_VALIDATION_ERROR로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "삼성전자").param("topK", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("topK 파라미터의 형식이 올바르지 않습니다."));
        verifyNoInteractions(hybridSearchService);
    }

    @Test
    void 경로의_ID가_숫자가_아니면_400과_VALIDATION_ERROR로_응답한다() throws Exception {
        mockMvc.perform(delete("/api/v1/watchlist/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("id 파라미터의 형식이 올바르지 않습니다."));
        verifyNoInteractions(watchlistService);
    }

    @Test
    void 지원하지_않는_HTTP_메서드는_405와_METHOD_NOT_ALLOWED로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/search").param("q", "삼성전자"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", "GET"));
    }

    @Test
    void 지원하지_않는_Content_Type은_415와_UNSUPPORTED_MEDIA_TYPE으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/v1/answers").contentType(MediaType.TEXT_PLAIN).content("삼성전자 배당"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 매핑된_핸들러가_없는_경로는_404와_NOT_FOUND로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void 없는_리소스_경로도_404와_NOT_FOUND로_응답한다() throws Exception {
        mockMvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}

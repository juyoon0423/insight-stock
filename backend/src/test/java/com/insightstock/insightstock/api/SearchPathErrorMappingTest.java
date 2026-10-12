package com.insightstock.insightstock.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insightstock.insightstock.embedding.EmbeddingApiException;
import com.insightstock.insightstock.embedding.EmbeddingRateLimitException;
import com.insightstock.insightstock.rag.AnswerService;
import com.insightstock.insightstock.rag.ChatCompletionClient;
import com.insightstock.insightstock.rag.CitationParser;
import com.insightstock.insightstock.rag.PromptBuilder;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.KeywordSearchService;
import com.insightstock.insightstock.search.RrfFuser;
import com.insightstock.insightstock.search.VectorSearchService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 검색 경로(/search, /answers)에서 질의 임베딩이 실패했을 때의 HTTP 응답을 검증한다.
// GlobalExceptionHandlerTest는 예외를 컨트롤러에서 직접 던지지만, 실제 경로에서는
// HybridSearchService가 임베딩 호출을 CompletableFuture 안에서 실행하고 join()하므로
// 예외가 CompletionException에 감싸져 올라온다 - 그 "감싸진 상태"를 그대로 재현하려고
// 실제 HybridSearchService/컨트롤러를 쓰고 그 아래 의존성만 대역으로 바꿨다.
// DB/ES/OpenAI를 호출하지 않는다(비용 0).
class SearchPathErrorMappingTest {

    private final KeywordSearchService keywordSearchService = mock(KeywordSearchService.class);
    private final VectorSearchService vectorSearchService = mock(VectorSearchService.class);
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(2);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(keywordSearchService.search(anyString(), anyInt())).thenReturn(List.of());

        HybridSearchService hybridSearchService = new HybridSearchService(
                keywordSearchService, vectorSearchService, new RrfFuser(), mock(ChunkRepository.class),
                searchExecutor, 50, 60, true);
        AnswerService answerService = new AnswerService(
                hybridSearchService, new PromptBuilder(), mock(ChatCompletionClient.class), new CitationParser(), 10);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SearchController(hybridSearchService, 10), new AnswerController(answerService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
    }

    @Test
    void search에서_임베딩이_실패하면_502와_SEARCH_UNAVAILABLE로_응답한다() throws Exception {
        when(vectorSearchService.search(anyString(), anyInt()))
                .thenThrow(new EmbeddingApiException("OpenAI 임베딩 API 호출 실패 (텍스트 1건)", null));

        mockMvc.perform(get("/api/v1/search").param("q", "삼성전자 배당"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void answers에서_임베딩이_실패하면_502와_SEARCH_UNAVAILABLE로_응답한다() throws Exception {
        when(vectorSearchService.search(anyString(), anyInt()))
                .thenThrow(new EmbeddingApiException("OpenAI 임베딩 API 호출 실패 (텍스트 1건)", null));

        mockMvc.perform(post("/api/v1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"삼성전자 배당\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    void search에서_임베딩_속도제한을_소진하면_429와_SEARCH_RATE_LIMITED로_응답한다() throws Exception {
        when(vectorSearchService.search(anyString(), anyInt()))
                .thenThrow(new EmbeddingRateLimitException("OpenAI 임베딩 API 호출 실패 (429 재시도 3회 소진)", null));

        mockMvc.perform(get("/api/v1/search").param("q", "삼성전자 배당"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_RATE_LIMITED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/search"));
    }

    @Test
    void answers에서_임베딩_속도제한을_소진하면_429와_SEARCH_RATE_LIMITED로_응답한다() throws Exception {
        when(vectorSearchService.search(anyString(), anyInt()))
                .thenThrow(new EmbeddingRateLimitException("OpenAI 임베딩 API 호출 실패 (429 재시도 3회 소진)", null));

        mockMvc.perform(post("/api/v1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"삼성전자 배당\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_RATE_LIMITED"));
    }

    @Test
    void 분류하지_않는_원인은_풀어도_기존대로_500과_INTERNAL_ERROR다() throws Exception {
        // 예: Elasticsearch 장애. 감싼 예외를 푼다고 해서 모든 실패가 502가 되면 안 된다.
        when(keywordSearchService.search(anyString(), anyInt())).thenThrow(new IllegalStateException("ES down"));
        when(vectorSearchService.search(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/search").param("q", "삼성전자 배당"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }
}

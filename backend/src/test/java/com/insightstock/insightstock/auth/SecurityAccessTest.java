package com.insightstock.insightstock.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 실제 보안 필터 체인(SecurityConfig + JwtAuthenticationFilter)을 통째로 띄워 "어떤 경로가 열려
// 있고 어떤 경로가 막혀 있는가"를 검증한다. 화이트리스트를 잘못 고치면 공개 API가 막히거나
// (데모 불가) 보호된 API가 열리는데(정보 노출), 둘 다 단위 테스트로는 잡히지 않는다.
//
// 유료 API를 호출하지 않는다: 공개 API는 빈 질의로 호출해 검증 단계(400)에서 끝나게 한다 -
// "401이 아니라 400"이라는 것 자체가 인증 없이 컨트롤러까지 도달했다는 증거다. DB에 쓰지도
// 않는다(조회만). 그래서 @Tag("manual") 없이 기본 test에 포함한다.
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAccessTest {

    // 존재하지 않는 사용자 ID - 조회 결과가 항상 비어 있어 실제 사용자 데이터를 건드리지 않는다.
    private static final long NON_EXISTENT_USER_ID = 9_999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ---- 공개 경로: 토큰 없이 컨트롤러까지 도달해야 한다 ----

    @Test
    void 헬스체크는_토큰_없이_열려_있다() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void 검색과_답변은_토큰_없이_컨트롤러까지_도달한다() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/answers").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void 회사_자동완성과_인증_API는_토큰_없이_컨트롤러까지_도달한다() throws Exception {
        mockMvc.perform(get("/api/v1/companies").param("q", "")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 보호된 경로 ----

    @Test
    void 관심목록과_알림은_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void 유효한_access_토큰이면_보호된_경로에_들어간다() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist").header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_토큰으로는_보호된_경로에_들어갈_수_없다() throws Exception {
        String refreshToken = jwtTokenProvider.generateRefreshToken(NON_EXISTENT_USER_ID);

        mockMvc.perform(get("/api/v1/watchlist").header(HttpHeaders.AUTHORIZATION, bearer(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }

    @Test
    void 위조된_토큰은_401이지만_공개_경로까지_막지는_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        // 만료/위조 토큰이 헤더에 남아 있어도 공개 API는 계속 쓸 수 있어야 한다.
        mockMvc.perform(get("/api/v1/search").param("q", "").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isBadRequest());
    }

    // ---- 화이트리스트 밖은 기본적으로 막혀 있다 ----

    @Test
    void 공개_경로라도_허용하지_않은_메서드는_토큰_없이_쓸_수_없다() throws Exception {
        // GET /api/v1/search만 열려 있다. POST는 화이트리스트에 없어 인증부터 요구한다.
        mockMvc.perform(post("/api/v1/search").param("q", "삼성전자")).andExpect(status().isUnauthorized());
    }

    @Test
    void 정의되지_않은_경로는_비로그인이면_401_로그인_상태면_404다() throws Exception {
        mockMvc.perform(get("/api/v1/nope")).andExpect(status().isUnauthorized());
        // 실제 앱 구성에서 없는 경로가 500이 아니라 404/NOT_FOUND로 나가는지도 여기서 확인된다
        // (standalone MockMvc로는 재현할 수 없던 경로 - StandardClientErrorMappingTest 참고).
        mockMvc.perform(get("/api/v1/nope").header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    private String accessToken() {
        return jwtTokenProvider.generateAccessToken(NON_EXISTENT_USER_ID);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}

package com.insightstock.insightstock.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// 화이트리스트 방식 - 명시적으로 permitAll()한 경로만 공개, 나머지는 기본 인증 필수
// (anyRequest().authenticated()). 새 API를 추가할 때 깜빡하고 보호를 빠뜨리는 사고를
// 막기 위해 반대 방향(블랙리스트)보다 이 방식을 선택했다(3단계 설계 논의 참고).
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // REST API + JWT라 세션 기반 CSRF 토큰이 필요 없다(세션 자체를 안 씀).
                .csrf(AbstractHttpConfigurer::disable)
                // WebConfig의 CorsConfigurationSource 빈을 그대로 재사용한다.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // CORS 프리플라이트는 Authorization 헤더를 안 실어 보내므로 항상 통과시켜야
                        // 한다 - 그렇지 않으면 보호된 경로의 프리플라이트가 401로 막혀 브라우저가
                        // 본 요청 자체를 보내지 않는다.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 하이브리드 검색/RAG 답변은 포트폴리오 핵심 가치라 로그인 장벽 없이
                        // 체험 가능해야 한다는 판단으로 공개 유지(3단계 설계 논의 참고).
                        .requestMatchers(HttpMethod.GET, "/api/v1/search").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/answers").permitAll()
                        // 회사 검색은 WatchList 등록 전 자동완성용 - 로그인 없이도 검색만은
                        // 가능해야 하므로 공개 유지(등록 자체는 WatchList API가 인증을 요구).
                        .requestMatchers(HttpMethod.GET, "/api/v1/companies").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

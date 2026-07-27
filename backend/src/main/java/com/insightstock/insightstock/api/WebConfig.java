package com.insightstock.insightstock.api;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// Next.js 프론트(다른 포트/도메인)에서 API를 호출할 수 있게 허용한다. 허용 origin을
// application.yml 값으로 빼서 배포 도메인이 생기면 코드 변경 없이 값만 바꾸면 된다.
//
// Spring Security 도입 후에는 CorsConfigurationSource 빈으로 노출해 SecurityConfig의
// cors()가 그대로 재사용한다 - WebMvcConfigurer.addCorsMappings와 Security의 CORS 처리를
// 동시에 쓰면 두 경로가 중복/충돌할 수 있어 이 빈 하나로 단일화했다.
@Configuration
public class WebConfig {

    private final List<String> allowedOrigins;

    public WebConfig(@Value("${cors.allowed-origins}") String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::strip)
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}

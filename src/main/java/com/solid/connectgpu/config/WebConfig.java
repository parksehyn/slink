package com.solid.connectgpu.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 노트북에서 따로 서빙하는 포털(다른 origin)이 VM의 Relay API를 호출할 수 있도록 CORS 허용.
 * 인증이 Authorization Bearer 헤더(쿠키 아님)라 allowCredentials는 불필요하다.
 * 기본은 모든 origin 허용(테스트/랩 용도). 운영 시 {@code slink.cors.allowed-origins}로 제한.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${slink.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}

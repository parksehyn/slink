package com.solid.connectgpu.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 노트북에서 따로 서빙하는 포털(다른 origin)이 VM의 Relay API를 호출할 수 있도록 CORS 허용.
 * 인증이 Authorization Bearer 헤더(쿠키 아님)라 allowCredentials는 불필요하다.
 * 기본은 모든 origin 허용(테스트/랩 용도). 운영 시 {@code slink.cors.allowed-origins}로 제한.
 *
 * <p>{@code portal.static.dir} 설정 시 포털 정적 파일을 jar 밖 디렉터리에서 우선 서빙한다
 * (없으면 jar 안 {@code classpath:/static/portal/}로 폴백). 화면만 고칠 때 재빌드 없이
 * 파일 교체(또는 git pull) + 새로고침으로 반영된다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${slink.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Value("${portal.static.dir:}")
    private String portalStaticDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (portalStaticDir == null || portalStaticDir.isBlank()) return;
        String loc = portalStaticDir.endsWith("/") ? portalStaticDir : portalStaticDir + "/";
        // 외부 디렉터리 우선, 누락 파일은 jar 안 정적 파일로 폴백. 캐시는 끔(즉시 반영).
        registry.addResourceHandler("/portal/**")
                .addResourceLocations("file:" + loc, "classpath:/static/portal/")
                .setCacheControl(CacheControl.noCache());
    }
}

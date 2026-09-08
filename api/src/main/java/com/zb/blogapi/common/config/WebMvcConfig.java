package com.zb.blogapi.common.config;

import com.zb.blogapi.common.interceptor.RequestLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 0주차 MVC 과제 - Interceptor 등록 + CORS 설정.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestLoggingInterceptor())
                .addPathPatterns("/api/**");
    }

    // 0주차 MVC 과제 3번: http://localhost:3000 만 허용하는 CORS 설정.
    //
    // allowedMethods에 GET 외에 다른 메서드도 넣어둔 이유: GET + 기본 헤더만 쓰면
    // 브라우저가 "단순 요청"으로 분류해서 preflight(OPTIONS)를 아예 안 보낸다.
    // preflight 발생을 재현하려면, 브라우저 쪽 fetch 호출에서 Content-Type: application/json
    // 같은 커스텀 헤더를 명시적으로 추가해서 "복잡한 요청"으로 만들어야 한다.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}

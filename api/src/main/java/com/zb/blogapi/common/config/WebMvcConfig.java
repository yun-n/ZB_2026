package com.zb.blogapi.common.config;

import com.zb.blogapi.common.interceptor.RequestLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 0주차 MVC 과제 - Interceptor 등록.
 *
 * addPathPatterns로 어떤 URL에 적용할지 지정한다.
 * Filter의 addUrlPatterns와 비슷해 보이지만, 이건 Spring MVC 레벨의 경로 매칭이라
 * 서블릿 컨테이너 레벨인 Filter의 URL 패턴과는 별개의 메커니즘이다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestLoggingInterceptor())
                .addPathPatterns("/api/**");
    }
}

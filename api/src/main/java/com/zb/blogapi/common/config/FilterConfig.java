package com.zb.blogapi.common.config;

import com.zb.blogapi.common.filter.RequestLoggingFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 0주차 MVC 과제 - Filter를 FilterRegistrationBean으로 등록.
 *
 * @Component만 붙여도 Spring Boot가 자동 등록해주긴 하지만,
 * FilterRegistrationBean을 쓰면 적용 URL 패턴과 순서(order)를 명시적으로 지정할 수 있다.
 * 지금은 필터가 1개뿐이라 순서 자체는 의미가 크지 않지만, 등록 방식을 익히는 게 목적.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestLoggingFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setName("requestLoggingFilter");
        return registration;
    }
}

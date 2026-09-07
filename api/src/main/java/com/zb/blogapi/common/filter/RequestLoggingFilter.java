package com.zb.blogapi.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 0주차 MVC 과제 - 1단계: Filter 실행 시점을 관찰하기 위한 로깅 필터.
 *
 * 서블릿 컨테이너(톰캣) 레벨에서 동작한다. DispatcherServlet보다도 앞단이라,
 * 여기서 발생하는 예외는 @RestControllerAdvice가 잡지 못한다 (4단계에서 직접 확인 예정).
 */
public class RequestLoggingFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilter(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        log.info("[FILTER] IN  - {} {}", request.getMethod(), request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            // 이 로그는 응답이 서블릿 컨테이너를 완전히 빠져나가기 직전에 찍힌다.
            // Interceptor의 afterCompletion과 순서를 비교해볼 것 (3단계).
            log.info("[FILTER] OUT - {} {} - status={}", request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }
}

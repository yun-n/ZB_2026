package com.zb.blogapi.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 0주차 MVC 과제 - 1단계: Filter 실행 시점을 관찰하기 위한 로깅 필터.
 *
 * 서블릿 컨테이너(톰캣) 레벨에서 동작한다. DispatcherServlet보다도 앞단이라,
 * 여기서 발생하는 예외는 @RestControllerAdvice가 잡지 못한다 (2단계 실험에서 직접 확인).
 */
public class RequestLoggingFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        log.info("[FILTER] IN  - {} {}", request.getMethod(), request.getRequestURI());
        try {
            // 0주차 MVC 과제 2번: Filter 단계에서 발생한 예외가 @RestControllerAdvice로
            // 처리되지 않는다는 것을 재현하기 위한 의도적인 테스트 분기.
            // 이 예외는 chain.doFilter() 호출 "전"에 터지므로, DispatcherServlet 자체가
            // 실행되지 않는다 — 즉 Interceptor도, Controller도, GlobalExceptionHandler도
            // 전혀 관여하지 못한다.
            if (request.getHeader("X-Force-Filter-Error") != null) {
                throw new RuntimeException("의도적으로 발생시킨 Filter 예외");
            }
            chain.doFilter(request, response);
        } finally {
            // 이 로그는 응답이 서블릿 컨테이너를 완전히 빠져나가기 직전에 찍힌다.
            // Interceptor의 afterCompletion과 순서를 비교해볼 것 (3단계).
            log.info("[FILTER] OUT - {} {} - status={}", request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }
}

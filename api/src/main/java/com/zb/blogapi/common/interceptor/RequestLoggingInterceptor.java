package com.zb.blogapi.common.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 0주차 MVC 과제 - 2단계: Interceptor 실행 시점을 관찰하기 위한 로깅 인터셉터.
 *
 * Filter와 다른 점: Interceptor는 Spring MVC(DispatcherServlet) 내부에서 동작하기 때문에,
 * "어떤 Controller 메서드가 호출될지"(handler 파라미터)를 이미 알고 있다.
 * Filter는 이 정보를 모른다 — 그냥 서블릿 요청/응답만 볼 수 있을 뿐이다.
 */
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Controller 메서드가 호출되기 "직전"에 실행된다.
        // handler에는 어떤 컨트롤러의 어떤 메서드가 호출될지에 대한 정보가 들어있다 (Filter는 이걸 모른다).
        log.info("[INTERCEPTOR] PRE  - {} {} - handler={}", request.getMethod(), request.getRequestURI(), handler);
        return true; // false를 리턴하면 여기서 요청이 끊기고 Controller가 아예 호출되지 않는다.
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                            org.springframework.web.servlet.ModelAndView modelAndView) {
        // Controller 메서드가 "정상적으로" 끝난 직후, 뷰가 렌더링되기 전에 실행된다.
        // 주의: Controller에서 예외가 발생하면 postHandle은 아예 호출되지 않는다 (4단계에서 확인할 부분).
        log.info("[INTERCEPTOR] POST - {} {}", request.getMethod(), request.getRequestURI());
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                 Exception ex) {
        // 응답이 클라이언트로 완전히 나가기 직전, 뷰 렌더링까지 끝난 후 항상 호출된다 (성공/예외 여부 무관).
        // ex 파라미터가 null이 아니면, 처리 중 예외가 있었다는 뜻이다.
        log.info("[INTERCEPTOR] AFTER_COMPLETION - {} {} - status={} - exception={}",
                request.getMethod(), request.getRequestURI(), response.getStatus(), ex);
    }
}

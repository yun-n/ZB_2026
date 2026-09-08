package com.zb.blogapi.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0주차 MVC 과제 - 2단계: Controller에서 발생한 예외를 JSON으로 응답 처리.
 *
 * 중요: 이 클래스가 잡을 수 있는 예외는 DispatcherServlet의 doDispatch() 내부,
 * 즉 HandlerMapping ~ Controller ~ Interceptor 실행 구간에서 발생한 것뿐이다.
 *
 * Filter(RequestLoggingFilter)에서 발생하는 예외는 DispatcherServlet 문턱을
 * 넘기도 전이라, 이 클래스가 절대 잡을 수 없다. 그 경우는 서블릿 컨테이너(톰캣)의
 * 기본 에러 처리 메커니즘으로 넘어간다 (2번 실험에서 직접 비교할 부분).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
        log.info("[EXCEPTION_HANDLER] Controller 예외를 JSON으로 변환 - message={}", ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Handled by @RestControllerAdvice");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

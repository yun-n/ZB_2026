package com.zb.blogapi.post;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PostController {

    private static final Logger log = LoggerFactory.getLogger(PostController.class);

    private final PostRepository postRepository;

    public PostController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    // 1주차 EXPLAIN ANALYZE 비교 대상 API.
    // 지금은 OFFSET 기반 Pageable을 그대로 씀 — 인덱스/커서 페이지네이션 적용 전 베이스라인.
    //
    // @RequestParam에 name을 명시적으로 지정한 이유:
    // 이름을 안 적으면 Spring이 컴파일러가 남긴 파라미터 이름 정보(리플렉션, -parameters 컴파일 옵션 필요)에
    // 의존하게 되는데, 빌드 도구/IDE 설정에 따라 이 옵션이 꺼져 있으면
    // "Name for argument of type [java.lang.String] not specified" 에러가 난다.
    // 이름을 명시하면 컴파일 옵션과 무관하게 항상 안전하게 동작한다.
    @GetMapping("/api/posts/feed")
    public Page<Post> feed(
            @RequestParam(name = "status", defaultValue = "PUBLISHED") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        // Filter의 IN/OUT, Interceptor의 PRE/POST 사이에서 Controller가 실제로 언제 실행되는지
        // 로그로 명확히 보여주기 위한 것. (0주차 MVC 과제 - 실행 순서 관찰용)
        log.info("[CONTROLLER] IN  - status={}, page={}, size={}", status, page, size);

        // 0주차 MVC 과제 2번: @RestControllerAdvice가 Controller 예외를 어떻게 처리하는지
        // 재현하기 위한 의도적인 테스트 분기. status=ERROR로 호출하면 예외가 발생한다.
        if ("ERROR".equals(status)) {
            throw new IllegalStateException("의도적으로 발생시킨 Controller 예외");
        }

        Page<Post> result = postRepository.findByStatusOrderByCreatedAtDescIdDesc(status, PageRequest.of(page, size));
        log.info("[CONTROLLER] OUT - totalElements={}", result.getTotalElements());
        return result;
    }
}

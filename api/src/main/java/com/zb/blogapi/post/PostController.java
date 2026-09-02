package com.zb.blogapi.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PostController {

    private final PostRepository postRepository;

    public PostController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    // 1주차 EXPLAIN ANALYZE 비교 대상 API.
    // 지금은 OFFSET 기반 Pageable을 그대로 씀 — 인덱스/커서 페이지네이션 적용 전 베이스라인.
    @GetMapping("/api/posts/feed")
    public Page<Post> feed(
            @RequestParam(defaultValue = "PUBLISHED") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return postRepository.findByStatusOrderByCreatedAtDescIdDesc(status, PageRequest.of(page, size));
    }
}

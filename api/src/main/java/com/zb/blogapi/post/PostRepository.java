package com.zb.blogapi.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {

    // 1주차 실습 대상: 지금은 status 조건 + OFFSET 페이지네이션(JPA Pageable 기본 동작).
    // 인덱스 적용 전/후, 그리고 커서 기반 페이지네이션으로의 전환을 1~2주차에서 다룬다.
    Page<Post> findByStatusOrderByCreatedAtDescIdDesc(String status, Pageable pageable);
}

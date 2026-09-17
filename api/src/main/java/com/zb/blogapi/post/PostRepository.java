package com.zb.blogapi.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    // 2주차 실험 - OFFSET 방식 (베이스라인). 지금까지 계속 써온 그 메서드.
    Page<Post> findByStatusOrderByCreatedAtDescIdDesc(String status, Pageable pageable);

    // 2주차 실험 - 커서 방식.
    //
    // 처음엔 MySQL의 row constructor 문법 (created_at, id) < (?, ?) 를 그대로 썼는데,
    // EXPLAIN ANALYZE로 확인해보니 "Index lookup + Filter" 형태로 처리되어 status 조건까지만
    // 인덱스로 좁혀지고, (created_at, id) 비교는 그 뒤에서 85,021건을 하나씩 걸러내는
    // Filter로 처리됐다 (row constructor가 인덱스 range scan 조건으로 녹아들지 않음).
    //
    // 같은 논리를 OR 체인으로 풀어서 쓰니 "Index range scan"으로 바뀌어, 실제로 20건만
    // 읽고 끝났다 (0.106ms 중앙값, row constructor 대비 약 3,500배). 그래서 OR 체인을 채택했다.
    @Query(value = """
            SELECT * FROM posts
            WHERE status = :status
              AND (
                created_at < :cursorCreatedAt
                OR (created_at = :cursorCreatedAt AND id < :cursorId)
              )
            ORDER BY created_at DESC, id DESC
            LIMIT :size
            """, nativeQuery = true)
    List<Post> findFeedByCursor(
            @Param("status") String status,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            @Param("size") int size
    );

    // 2주차 N+1 실험 - JOIN FETCH로 posts와 author(User)를 한 쿼리로 함께 가져온다.
    // *-to-one 관계라 페이지네이션(Pageable)과 함께 써도 안전하다 (카티션 곱 걱정 없음).
    //
    // 반환 타입을 Page<Post>가 아니라 List<Post>로 바꾼 이유:
    // Page를 쓰면 Spring Data JPA가 총 개수를 세는 count 쿼리를 자동으로 추가 실행하는데,
    // JOIN FETCH가 걸린 쿼리 그대로 count까지 실행되면서 posts 89,889건 하나하나마다
    // users를 Nested Loop로 조회하는 또 다른 형태의 비효율(총 418ms)이 발생했다.
    // List로 바꾸면 이 count 쿼리 자체가 아예 안 나간다.
    @Query("SELECT p FROM Post p JOIN FETCH p.author WHERE p.status = :status ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> findByStatusWithAuthor(@Param("status") String status, Pageable pageable);
}

package com.zb.blogapi.post;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
public class PostController {

    private static final Logger log = LoggerFactory.getLogger(PostController.class);

    private final PostRepository postRepository;

    public PostController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    // 1주차 EXPLAIN ANALYZE 비교 대상 API. (2주차 기준: OFFSET 방식 베이스라인)
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
            throw new IllegalStateException("의도적으로 발생시킨 Controller 예외 (0주차 MVC 과제 2번 실험용)");
        }

        Page<Post> result = postRepository.findByStatusOrderByCreatedAtDescIdDesc(status, PageRequest.of(page, size));
        log.info("[CONTROLLER] OUT - totalElements={}", result.getTotalElements());
        return result;
    }

    // 2주차 실험 - 커서 방식.
    // cursorCreatedAt/cursorId를 안 넘기면 "가장 최신부터"로 취급한다 (첫 페이지).
    // 이후 페이지는 클라이언트가 직전 응답의 마지막 게시글의 createdAt/id를 그대로
    // 다음 요청의 커서로 넘겨야 한다.
    @GetMapping("/api/posts/feed-cursor")
    public List<Post> feedCursor(
            @RequestParam(name = "status", defaultValue = "PUBLISHED") String status,
            @RequestParam(name = "cursorCreatedAt", required = false) String cursorCreatedAt,
            @RequestParam(name = "cursorId", required = false) Long cursorId,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        LocalDateTime cursor = (cursorCreatedAt != null)
                ? LocalDateTime.parse(cursorCreatedAt)
                : LocalDateTime.now().plusYears(100); // 커서 없음 = 첫 페이지, 미래 시각으로 "전부보다 크다" 취급
        Long id = (cursorId != null) ? cursorId : Long.MAX_VALUE;

        log.info("[CONTROLLER] IN  (cursor) - status={}, cursorCreatedAt={}, cursorId={}, size={}",
                status, cursor, id, size);
        List<Post> result = postRepository.findFeedByCursor(status, cursor, id, size);
        log.info("[CONTROLLER] OUT (cursor) - returned={}", result.size());
        return result;
    }

    // 2주차 N+1 실험 - 의도적으로 N+1을 만든 버전.
    //
    // findByStatusOrderByCreatedAtDescIdDesc는 posts만 SELECT 한 번으로 가져온다 (author는
    // LAZY라 아직 로딩 안 됨). 그 다음 for문에서 p.getAuthor().getUsername()을 호출하는 순간마다
    // Hibernate가 "이 author 아직 로딩 안 됐네" 하고 그때그때 SELECT users WHERE id=? 를 날린다.
    // 게시글이 20개면 총 쿼리가 1(posts) + 20(각 author) = 21번 나간다 — 이게 N+1이다.
    //
    // @Transactional이 필요한 이유: application.yml에 open-in-view: false로 해뒀기 때문에,
    // Repository 호출이 끝나면 영속성 컨텍스트(세션)가 바로 닫힌다. 그 상태에서 지연 로딩된
    // author에 접근하면 LazyInitializationException이 난다. 그래서 세션이 열려있는 동안
    // (Controller 메서드 전체가 트랜잭션 범위 안에 있는 동안) author까지 다 읽어서 DTO로
    // 변환해버려야 한다.
    @Transactional(readOnly = true)
    @GetMapping("/api/posts/feed-with-author")
    public List<PostFeedItem> feedWithAuthor(
            @RequestParam(name = "status", defaultValue = "PUBLISHED") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        Page<Post> posts = postRepository.findByStatusOrderByCreatedAtDescIdDesc(status, PageRequest.of(page, size));
        List<PostFeedItem> result = new ArrayList<>();
        for (Post p : posts) {
            // N+1 발생 지점: 게시글마다 이 줄에서 SELECT users 쿼리가 하나씩 추가로 나간다.
            String authorUsername = p.getAuthor().getUsername();
            result.add(new PostFeedItem(p.getId(), p.getTitle(), authorUsername, p.getCreatedAt()));
        }
        return result;
    }

    // 2주차 N+1 실험 - JOIN FETCH로 해결한 버전.
    // posts와 author를 한 쿼리로 함께 가져오므로(SELECT ... JOIN users ...), 쿼리가 총 1번만 나간다.
    // *-to-one(ManyToOne) 관계의 fetch join은 페이지네이션(LIMIT/OFFSET)과 함께 써도
    // 데이터 중복(카티션 곱) 문제가 없어 Pageable을 그대로 쓸 수 있다.
    @GetMapping("/api/posts/feed-with-author-fixed")
    public List<PostFeedItem> feedWithAuthorFixed(
            @RequestParam(name = "status", defaultValue = "PUBLISHED") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        List<Post> posts = postRepository.findByStatusWithAuthor(status, PageRequest.of(page, size));
        List<PostFeedItem> result = new ArrayList<>();
        for (Post p : posts) {
            result.add(new PostFeedItem(p.getId(), p.getTitle(), p.getAuthor().getUsername(), p.getCreatedAt()));
        }
        return result;
    }
}

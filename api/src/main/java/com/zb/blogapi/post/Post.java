package com.zb.blogapi.post;

import com.zb.blogapi.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 의도적으로 FK 제약을 걸지 않음 (0주차 베이스라인에서 InnoDB 자동 인덱스 생성을 막기 위함)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 2주차 N+1 실험용 연관관계.
    // insertable/updatable = false로 해서 쓰기는 여전히 user_id 컬럼(위 필드)으로 하고,
    // 읽기 전용으로만 User를 지연 로딩(LAZY)한다. FK 제약은 걸지 않았으므로 DB 레벨
    // 인덱스 베이스라인에는 영향 없다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User author;

    @Column(nullable = false, length = 20)
    private String status; // PUBLISHED, DRAFT 등 — Enum은 1주차 이후 리팩터링

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Post() {
    }

    public Post(Long userId, String status, String title, String content) {
        this.userId = userId;
        this.status = status;
        this.title = title;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public User getAuthor() {
        return author;
    }

    public String getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

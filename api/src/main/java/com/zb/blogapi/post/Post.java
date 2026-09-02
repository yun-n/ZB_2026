package com.zb.blogapi.post;

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

    @Column(nullable = false, length = 20)
    private String status; // PUBLISHED, DRAFT 등 — Enum은 1주차 이후 리팩터링

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false)
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

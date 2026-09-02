package com.zb.blogapi.like;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "likes")
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 의도적으로 FK 제약 없음 + 유니크 제약도 아직 없음 (0주차 베이스라인)
    // 1주차에 uk_likes_post_user (post_id, user_id) 추가 예정
    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Like() {
    }

    public Like(Long postId, Long userId) {
        this.postId = postId;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

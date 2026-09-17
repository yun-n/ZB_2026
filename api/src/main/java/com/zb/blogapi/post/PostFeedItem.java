package com.zb.blogapi.post;

import java.time.LocalDateTime;

// 2주차 N+1 실험용 응답 DTO. 엔티티를 직접 JSON으로 내보내면 Hibernate 지연 로딩 프록시가
// 그대로 직렬화되면서 문제가 생길 수 있어, 필요한 값만 뽑아 담는 순수 DTO로 응답한다.
public record PostFeedItem(Long id, String title, String authorUsername, LocalDateTime createdAt) {
}

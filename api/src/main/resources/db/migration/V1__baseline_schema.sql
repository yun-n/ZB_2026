-- V1: 0주차 베이스라인 스키마
-- 의도적으로 PRIMARY KEY 외에는 인덱스를 걸지 않았습니다.
-- FK 제약도 걸지 않았습니다 (MySQL InnoDB는 FK 컬럼에 자동으로 인덱스를 만들기 때문에,
-- "인덱스 없는 상태"라는 베이스라인을 지키기 위한 의도적인 선택입니다).
-- 1주차부터 V2__add_indexes.sql 같은 다음 마이그레이션에서 인덱스를 추가하며
-- "V1(전) vs V2(후)"를 EXPLAIN ANALYZE로 비교합니다.

CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50) NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE posts (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    status      VARCHAR(20) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE likes (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

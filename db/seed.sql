-- 시드 데이터 생성 스크립트
-- 기본값: users 1,000 / posts 100,000 / likes 500,000
-- 자료 원문 규모(posts 100만, likes 1000만)로 키우고 싶으면 아래 변수만 조절해서 재실행하면 됩니다.
-- (단, 로컬 환경 스펙에 따라 시간이 꽤 걸릴 수 있어 우선 1/10 규모로 잡았습니다.)

SET SESSION cte_max_recursion_depth = 2000000;

SET @num_users = 1000;
SET @num_posts = 100000;
SET @num_likes = 500000;

-- 1) users
INSERT INTO users (username, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @num_users
)
SELECT
    CONCAT('user_', n),
    NOW() - INTERVAL FLOOR(RAND() * 730) DAY
FROM seq;

-- 2) posts (user_id는 users 범위 내에서 랜덤, status는 90% PUBLISHED / 10% DRAFT)
INSERT INTO posts (user_id, status, title, content, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @num_posts
)
SELECT
    FLOOR(1 + RAND() * @num_users),
    IF(RAND() < 0.9, 'PUBLISHED', 'DRAFT'),
    CONCAT('post title ', n),
    CONCAT('post content body for post number ', n, ' - lorem ipsum dummy text.'),
    NOW() - INTERVAL FLOOR(RAND() * 730) DAY - INTERVAL FLOOR(RAND() * 86400) SECOND
FROM seq;

-- 3) likes (post_id / user_id 모두 랜덤 - 중복 방지 제약이 아직 없는 0주차 베이스라인이라 중복 있을 수 있음)
INSERT INTO likes (post_id, user_id, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @num_likes
)
SELECT
    FLOOR(1 + RAND() * @num_posts),
    FLOOR(1 + RAND() * @num_users),
    NOW() - INTERVAL FLOOR(RAND() * 730) DAY - INTERVAL FLOOR(RAND() * 86400) SECOND
FROM seq;

SELECT
    (SELECT COUNT(*) FROM users) AS users_count,
    (SELECT COUNT(*) FROM posts) AS posts_count,
    (SELECT COUNT(*) FROM likes) AS likes_count;

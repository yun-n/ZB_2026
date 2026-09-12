-- V2: 1주차 인덱스 최적화 실습 결과 반영
--
-- 대상 쿼리 (PostController#feed):
--   SELECT * FROM posts WHERE status='PUBLISHED'
--   ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0;
--
-- 실험 배경: idx_a(created_at,id), idx_b(status,created_at,id), idx_c(created_at,status)
-- 세 후보를 각각 단독으로 걸어 EXPLAIN ANALYZE + 5~6회 반복 측정으로 비교했다
-- (docs/week1.md 참고). 그 결과 status를 맨 앞에 둔 등치 조건 선행 인덱스가
-- 유일하게 "Index lookup" 한 단계로 끝났고(Filter, Sort 단계 모두 제거됨),
-- 나머지 두 후보보다 안정적으로 빨랐다.
--
-- 베이스라인 대비 중앙값 75.0ms -> 0.096ms (약 781배)
CREATE INDEX idx_posts_status_created_at_id
    ON posts (status, created_at DESC, id DESC);

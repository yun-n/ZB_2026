-- 0주차 기준: posts, likes 모두 PRIMARY 인덱스만 있어야 정상입니다.
-- 1주차에 인덱스를 추가한 뒤 이 스크립트를 다시 실행해서 "전/후"를 비교하세요.

SHOW INDEX FROM users;
SHOW INDEX FROM posts;
SHOW INDEX FROM likes;

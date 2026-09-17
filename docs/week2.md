# 2주차 — OFFSET vs 커서 페이지네이션, N+1 문제

대상 API: `GET /api/posts/feed`(OFFSET), `GET /api/posts/feed-cursor`(커서),
`GET /api/posts/feed-with-author`(N+1), `GET /api/posts/feed-with-author-fixed`(해결)

## 기본 루프

1주차와 동일하게 측정 → 가설 → 한 번에 하나만 변경 → 재측정 → 결론 순서로 진행했다.
— N+1을 한 번 고쳤다고 생각했는데 재측정 결과
오히려 느려져서, 원인을 다시 찾고 한 번 더 고치는 과정을 거쳤다.

---

## 실험 1 — OFFSET vs 커서 페이지네이션

### 1) OFFSET 90000 — 깊은 페이지 SQL 단일 측정

```sql
EXPLAIN ANALYZE
SELECT * FROM posts WHERE status='PUBLISHED'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 90000;
```

측정값: 551ms*, 319, 257, 192, 222ms (*첫 실행은 워밍업으로 중앙값 계산에서 제외) → 중앙값 약 239.5ms

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — OFFSET 90000 (192ms, 222ms 실행분)</summary>

```
-- Run (192ms)
-> Limit/Offset: 20/90000 row(s)  (cost=5659 rows=0) (actual time=192..192 rows=0 loops=1)
    -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5659 rows=49619) (actual time=0.0229..188 rows=89889 loops=1)

-- Run (222ms)
-> Limit/Offset: 20/90000 row(s)  (cost=5659 rows=0) (actual time=222..222 rows=0 loops=1)
    -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5659 rows=49619) (actual time=0.0258..217 rows=89889 loops=1)
```
</details>

인덱스는 타고 있었지만(`Index lookup`), **89,889건을 실제로 다 읽었다.** 1주차 OFFSET 0(idx_b, 0.096ms)
대비 약 2,500배 느리다 — 인덱스 유무와 무관하게 OFFSET 자체의 구조적 비용이다.

### 참고 — row constructor란

여러 컬럼 값을 괄호로 묶어 하나의 "튜플(순서쌍)"처럼 비교하는 SQL 표준 문법이다.

```sql
WHERE (created_at, id) < ('2024-10-20 12:41:54', 7590)
```

이는 아래의 OR 조건과 논리적으로 완전히 동일하다 — 왼쪽 값(`created_at`)부터 비교하다가 같으면
다음 값(`id`)으로 비교를 넘어가는 "사전식 비교(lexicographic comparison)" 규칙을 따른다.

```sql
WHERE created_at < '2024-10-20 12:41:54'
   OR (created_at = '2024-10-20 12:41:54' AND id < 7590)
```

두 조건은 결과가 같지만, DB 옵티마이저가 이 둘을 항상 동일하게 최적화해주는 것은 아니다 — 아래
1차 시도(row constructor)와 2차 시도(OR 체인)의 실행계획 차이가 이를 직접 보여준다.

### 2) 커서 방식 1차 시도 — row constructor `(created_at, id) < (?, ?)`

**실험 전 예측**: row constructor가 표준 SQL 문법이라고 해서 시도

```sql
EXPLAIN ANALYZE
SELECT * FROM posts
WHERE status = 'PUBLISHED'
  AND (created_at, id) < ('2024-10-20 12:41:54', 7590)
ORDER BY created_at DESC, id DESC LIMIT 20;
```

핵심 근거:

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — row constructor (384ms, 422ms 실행분)</summary>

```
-- Run (384ms)
-> Limit: 20 row(s)  (cost=5659 rows=20) (actual time=384..384 rows=20 loops=1)
    -> Filter: ((posts.created_at,posts.id) < ('2024-10-20 12:41:54',7590))  (cost=5659 rows=49619) (actual time=384..384 rows=20 loops=1)
        -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5659 rows=49619) (actual time=0.0272..365 rows=85021 loops=1)

-- Run (422ms)
-> Limit: 20 row(s)  (cost=5659 rows=20) (actual time=422..422 rows=20 loops=1)
    -> Filter: ((posts.created_at,posts.id) < ('2024-10-20 12:41:54',7590))  (cost=5659 rows=49619) (actual time=422..422 rows=20 loops=1)
        -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5659 rows=49619) (actual time=0.0268..400 rows=85021 loops=1)
```
</details>

측정 범위: 384ms, 422ms 확인됨 **`status`
조건까지만 인덱스로 좁혀지고, `(created_at, id)` 비교는 Filter 단계에서 85,021건을 하나씩
걸러내는 방식으로 처리됐다** — row constructor가 인덱스의 범위 탐색 조건으로 녹아들지 않아,
오히려 OFFSET 90000(약 240ms)보다도 느렸다.

### 3) 커서 방식 2차 시도 — OR 체인으로 재작성 (채택)

```sql
EXPLAIN ANALYZE
SELECT * FROM posts
WHERE status = 'PUBLISHED'
  AND (
    created_at < '2024-10-20 12:41:54'
    OR (created_at = '2024-10-20 12:41:54' AND id < 7590)
  )
ORDER BY created_at DESC, id DESC LIMIT 20;
```

측정값: 3.14ms*, 0.0712, 0.116, 0.103, 0.113, 0.099ms (*첫 실행 워밍업 제외) → 중앙값 약 0.106ms

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — OR 체인 (6회 전체, 3.14ms 워밍업 포함)</summary>

```
-- Run 1 (3.14ms, 워밍업 - 중앙값 계산에서 제외)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=3.05..3.14 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=3.04..3.14 rows=20 loops=1)

-- Run 2 (0.0712ms)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=0.0204..0.0712 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=0.0193..0.0689 rows=20 loops=1)

-- Run 3 (0.116ms)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=0.0299..0.116 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=0.0285..0.112 rows=20 loops=1)

-- Run 4 (0.103ms)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=0.0349..0.103 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=0.0337..0.1 rows=20 loops=1)

-- Run 5 (0.113ms)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=0.0317..0.113 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=0.0304..0.109 rows=20 loops=1)

-- Run 6 (0.099ms, 중앙값에 가장 근접)
-> Limit: 20 row(s)  (cost=2201 rows=20) (actual time=0.0278..0.099 rows=20 loops=1)
    -> Index range scan on posts using idx_posts_status_created_at_id over (status = 'PUBLISHED' AND created_at = '2024-10-20 12:41:54' AND 7590 < id) OR (status = 'PUBLISHED' AND '2024-10-20 12:41:54' < created_at), with index condition: ((posts.`status` = 'PUBLISHED') and ((posts.created_at < TIMESTAMP'2024-10-20 12:41:54') or ((posts.created_at = TIMESTAMP'2024-10-20 12:41:54') and (posts.id < 7590))))  (cost=2201 rows=4889) (actual time=0.0265..0.0959 rows=20 loops=1)
```
</details>

같은 논리적 조건을 OR로 풀어쓰자 실행계획이 **실제로 `Index range scan`으로 바뀐 것이 원문으로 확인됐다**
(`over (...)` 절에 탐색 범위가 명시됨). 실제로 읽은 행이 20건 수준으로 줄었다. OFFSET 90000(약 240ms)
대비 약 2,264배, row constructor(약 380ms) 대비도 훨씬 빠르다.

### 참고 — Index lookup vs Index range scan

- **Index lookup**: 등치(`=`) 조건으로 인덱스에서 정확히 한 지점을 짚어 찾는 것. 예:
  `status='PUBLISHED'`처럼 "이 값과 같은 것"만 찾을 때 쓰인다.
- **Index range scan**: `<`, `>`, `BETWEEN` 같은 범위 조건으로, 인덱스에서 연속된 구간을 순서대로
  훑는 것. `over (...)` 절에 그 탐색 범위가 명시된다.

row constructor 시도에서는 `status='PUBLISHED'`만 Index lookup으로 처리되고 `(created_at, id)`
범위 조건은 인덱스에 못 녹아들어 Filter로 밀려났다. OR 체인에서는 등치 조건과 범위 조건이
하나로 합쳐져 Index range scan으로 처리되어, "PUBLISHED이면서 이 시점 이전인 구간"을 인덱스가
바로 짚어 들어갈 수 있었다 — 이게 두 방식의 성능 차이를 가른 핵심이다.

### SQL 레벨 3방식 비교

| 방식 | 실행계획 | 읽은 행(rows) | 중앙값 |
| --- | --- | --- | --- |
| OFFSET 90000 | Index lookup + Limit/Offset | 89,889 | ~240ms |
| row constructor `(a,b)<(?,?)` | Index lookup + **Filter** | 85,021 | ~380ms (오히려 더 느림) |
| **OR 체인 (채택)** | **Index range scan** | ~20 | **0.106ms** |

### 참고 — k6 스크립트 구조와 결과 해석

**기본 구조**
```javascript
import http from 'k6/http';           // k6 내장 HTTP 요청 모듈 (default export)
import { check, sleep } from 'k6';    // k6 핵심 모듈에서 check, sleep만 콕 집어 가져옴

export const options = {
    vus: 10,        // 동시에 요청을 보내는 가상 사용자 수
    duration: '20s', // 테스트 지속 시간
};

export default function () {          // 이 함수 내용이 VU 한 명당 반복 실행됨
    const res = http.get('...');
    check(res, { 'status is 200': (r) => r.status === 200 });  // 검증(실패 여부 판단)
    sleep(0.1);                       // 요청 사이 대기 (현실적인 트래픽 흉내)
}
```

**결과 해석 시 보는 항목**
- `checks_succeeded`: 100%가 아니면 성능 이전에 에러부터 확인해야 한다.
- `http_req_duration`의 `p(95)`, `p(99)`: 평균(avg)은 극단값에 묻혀 왜곡되기 쉬우므로, 대부분
  이 백분위수 값을 대표 지표로 쓴다. p95는 "100명 중 95번째로 느린 사용자"의 체감 속도를 뜻한다.
- `http_reqs`: 같은 시간 동안 처리한 총 요청 수(처리량). 응답 속도뿐 아니라 "동시에 얼마나
  많은 사용자를 감당하는가"를 보여준다 — 이번 실험에서 OFFSET(174건) vs 커서(1,769건) 비교가
  이 지표의 실제 사례다.

### API 레벨 측정 (k6, 10 VUs, 20초, 같은 깊은 위치)

<details>
<summary>k6 raw 결과 — OFFSET (page=4250, size=20)</summary>

```
checks_total.......: 174     8.31576/s
checks_succeeded...: 100.00% 174 out of 174

http_req_duration..............: avg=1.07s min=702.02ms med=982.12ms max=1.7s p(90)=1.47s p(95)=1.55s p(99)=1.64s
http_reqs......................: 174    8.31576/s
```
</details>

<details>
<summary>k6 raw 결과 — 커서 (cursorCreatedAt=2024-10-20T12:41:54, cursorId=7590, size=20)</summary>

```
checks_total.......: 1769    87.999362/s
checks_succeeded...: 100.00% 1769 out of 1769

http_req_duration..............: avg=11.33ms  min=1.84ms   med=6.88ms   max=136.31ms p(90)=22.72ms  p(95)=34.56ms  p(99)=65.15ms
http_reqs......................: 1769   87.999362/s
```
</details>

| 지표 | OFFSET | 커서 | 개선 배율 |
| --- | --- | --- | --- |
| p95 | 1.55초 | 34.56ms | 약 45배 |
| p99 | 1.64초 | 65.15ms | 약 25배 |
| 평균 | 1.07초 | 11.33ms | 약 94배 |
| 20초간 처리 요청 수 | 174건 | 1,769건 | 약 10배 |

---

## 실험 2 — N+1 문제 (세 번의 시행착오)

### 1) N+1 발생 버전 (`/api/posts/feed-with-author`)

`Post.author`를 `FetchType.LAZY`로 매핑하고, for문에서 `p.getAuthor().getUsername()`을 호출해
매 게시글마다 별도 조회가 나가도록 의도적으로 구성했다.

<details>
<summary>Raw SQL 로그 — N+1 버전 (size=5)</summary>

```
[FILTER] IN  - GET /api/posts/feed-with-author
[INTERCEPTOR] PRE  - handler=PostController#feedWithAuthor(String, int, int)

Hibernate:
    select
        p1_0.id,
        p1_0.user_id,
        p1_0.content,
        p1_0.created_at,
        p1_0.status,
        p1_0.title
    from
        posts p1_0
    where
        p1_0.status=?
    order by
        p1_0.created_at desc,
        p1_0.id desc
    limit
        ?

Hibernate:
    select
        count(p1_0.id)
    from
        posts p1_0
    where
        p1_0.status=?

Hibernate:
    select
        u1_0.id,
        u1_0.created_at,
        u1_0.username
    from
        users u1_0
    where
        u1_0.id=?

Hibernate:
    select
        u1_0.id,
        u1_0.created_at,
        u1_0.username
    from
        users u1_0
    where
        u1_0.id=?

Hibernate:
    select
        u1_0.id,
        u1_0.created_at,
        u1_0.username
    from
        users u1_0
    where
        u1_0.id=?

-- (이하 users 조회 1회 더, 총 4회 — posts 5건 중 작성자가 겹치는 건이 있어
--  1차 캐시(영속성 컨텍스트) 히트로 1건은 쿼리 없이 처리됨)
```
</details>

<details>
<summary>Raw SQL 로그 — N+1 버전 (size=20, 전체)</summary>

```
[FILTER] IN  - GET /api/posts/feed-with-author
[INTERCEPTOR] PRE  - handler=PostController#feedWithAuthor(String, int, int)

Hibernate:
    select
        p1_0.id, p1_0.user_id, p1_0.content, p1_0.created_at, p1_0.status, p1_0.title
    from posts p1_0
    where p1_0.status=?
    order by p1_0.created_at desc, p1_0.id desc
    limit ?
binding parameter (1:VARCHAR) <- [PUBLISHED]
binding parameter (2:INTEGER) <- [20]

Hibernate:
    select count(p1_0.id) from posts p1_0 where p1_0.status=?
binding parameter (1:VARCHAR) <- [PUBLISHED]

-- 이후 posts 20건을 순회하며 author(user_id)별로 각각 조회.
-- 실제로는 19회만 나갔다 (아래 순서로 등장한 user_id 중 한 건이 직전에 이미 조회된 값과
-- 겹쳐 1차 캐시 히트가 발생, 20건 중 1건은 쿼리 없이 처리됨):
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [218]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [219]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [212]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [201]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [422]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [76]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [410]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [185]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [734]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [174]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [497]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [384]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [708]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [375]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [43]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [40]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [361]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [801]
Hibernate: select u1_0.id, u1_0.created_at, u1_0.username from users u1_0 where u1_0.id=?  <- [470]

[INTERCEPTOR] POST / AFTER_COMPLETION - status=200
[FILTER] OUT - status=200
```
</details>

`size=5` 호출 시 관찰된 쿼리:
```
1. select ... from posts ...                          (게시글 목록, 1회)
2. select count(p1_0.id) ...                          (Page 타입이라 자동 추가, 1주차부터 이어진 이슈)
3~6. select ... from users where id=? (4회)            (5건 중 작성자 겹침으로 1차 캐시 히트 1건 발생)
```
`size=20` 호출 시 실제로는 `users` 조회가 **20회가 아니라 19회**만 확인됐다 — 20건 중 한 건의
작성자가 이미 조회된 값과 겹쳐 1차 캐시(영속성 컨텍스트)가 또 한 번 히트했다. 즉 총 쿼리는
1(posts) + 1(count) + 19(author) = **21개** (작성자가 전부 겹치지 않는 최악의 경우라면 22개까지
가능).

<details>
<summary>k6 raw 결과 — N+1 버전 (page=0, size=20)</summary>

```
checks_total.......: 629     31.012668/s
checks_succeeded...: 100.00% 629 out of 629

http_req_duration..............: avg=220.25ms min=55.09ms  med=211.17ms max=884.68ms p(90)=301.2ms  p(95)=341.98ms p(99)=832.94ms
http_reqs......................: 629    31.012668/s
```
</details>

### 2) JOIN FETCH + `Page` 유지

**실험 전 예측**: N+1을 해결하면 당연히 문제가 해결될 거라 생각했다. 불필요한 쿼리(author 20번)가 안 나가니까 "조금 빨라지지 않을까" 정도의 기대 —
지금처럼 오히려 몇 배 더 느려질 거라고는 전혀 예상하지 못했다.

`@Query("SELECT p FROM Post p JOIN FETCH p.author WHERE ...")`로 posts+author를 한 쿼리로 합쳤다.
쿼리 개수는 확실히 줄었다:

<details>
<summary>Raw SQL 로그 — JOIN FETCH + Page 유지 (size=5)</summary>

```
[FILTER] IN  - GET /api/posts/feed-with-author-fixed
[INTERCEPTOR] PRE  - handler=PostController#feedWithAuthorFixed(String, int, int)

Hibernate:
    select
        p1_0.id,
        a1_0.id,
        a1_0.created_at,
        a1_0.username,
        p1_0.content,
        p1_0.created_at,
        p1_0.status,
        p1_0.title,
        p1_0.user_id
    from
        posts p1_0
    join
        users a1_0
            on a1_0.id=p1_0.user_id
    where
        p1_0.status=?
    order by
        p1_0.created_at desc,
        p1_0.id desc
    limit
        ?
binding parameter (1:VARCHAR) <- [PUBLISHED]
binding parameter (2:INTEGER) <- [5]

Hibernate:
    select
        count(p1_0.id)
    from
        posts p1_0
    join
        users a1_0
            on a1_0.id=p1_0.user_id
    where
        p1_0.status=?
binding parameter (1:VARCHAR) <- [PUBLISHED]

[INTERCEPTOR] POST / AFTER_COMPLETION - status=200
[FILTER] OUT - status=200
```
</details>

```
1. select ... from posts p1_0 join users a1_0 on a1_0.id=p1_0.user_id where p1_0.status=? ...  (메인, 1회)
2. select count(p1_0.id) from posts p1_0 join users a1_0 on a1_0.id=p1_0.user_id where p1_0.status=?  (count, 1회)
```
총 2개 쿼리로 21~22개 대비 크게 줄었지만, k6로 재측정하니 오히려 훨씬 느려졌다.

<details>
<summary>k6 raw 결과 — JOIN FETCH + Page 유지 (page=0, size=20)</summary>

```
checks_total.......: 137     6.429963/s
checks_succeeded...: 100.00% 137 out of 137

http_req_duration..............: avg=1.42s min=997.3ms med=1.3s max=2.84s p(90)=2.11s p(95)=2.31s p(99)=2.57s
http_reqs......................: 137    6.429963/s
vus............................: 7      min=7        max=10
```
</details>

**원인 분석 — count 쿼리에 JOIN이 함께 실행됨:**

<details>
<summary>EXPLAIN ANALYZE — count(*) FROM posts (JOIN 없음)</summary>

```
-> Aggregate: count(0)  (cost=10542 rows=1) (actual time=73..73 rows=1 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5580 rows=49619) (actual time=1.33..69.3 rows=89889 loops=1)
```
</details>

<details>
<summary>EXPLAIN ANALYZE — count(p.id) FROM posts JOIN users (JOIN 있음)</summary>

```
-> Aggregate: count(p.id)  (cost=27987 rows=1) (actual time=418..418 rows=1 loops=1)
    -> Nested loop inner join  (cost=23025 rows=49619) (actual time=74.1..391 rows=89889 loops=1)
        -> Index lookup on p using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5659 rows=49619) (actual time=73.9..277 rows=89889 loops=1)
        -> Single-row covering index lookup on u using PRIMARY (id=p.user_id)  (cost=0.25 rows=1) (actual time=0.0011..0.00112 rows=1 loops=89889)
```
</details>

### 참고 — Covering index lookup vs Single-row covering index lookup

둘 다 "인덱스만으로 필요한 데이터를 다 구해서, 원본 테이블 행까지 갈 필요가 없다"는
covering index 개념은 같지만, 실행 횟수와 결과 행 수가 다르다.

- **Covering index lookup**: 인덱스 하나를 훑어 여러 행을 가져오는 것. 보통 `loops=1`로,
  조회 작업 자체는 한 번만 실행되고 그 안에서 여러 행을 순회한다.
- **Single-row covering index lookup**: PK처럼 유일한 값으로 조회해 결과가 항상 정확히
  1행만 나오는 것. JOIN의 안쪽 루프로 쓰이면 `loops=N`으로 N번 반복 실행된다 — 한 번은
  매우 빨라도(예: 0.001ms), 반복 횟수가 많으면(예: loops=89889) 누적 비용이 커진다.

JOIN 있는 count 쿼리가 느렸던 이유가 바로 이 두 번째 패턴이다 — `users` PK 조회 자체는
빨랐지만 89,889번 반복되며 총 418ms까지 늘어났다.

JOIN이 없으면 73ms(인덱스만 훑는 covering index count), JOIN이 있으면 418ms(약 5.7배) —
`users` PK 조회 한 건은 0.001ms로 매우 빠르지만, 이걸 **89,889번(loops=89889) 반복**하면서
누적 비용이 커졌다. 코드의 반복문 N+1과 본질이 같은, SQL 내부의 또 다른 형태의 N+1이었다.

### 3) JOIN FETCH + `List` 전환

**실험 전 예측**: 확실한 효과가 있을 거라고는 생각하지 못했다. "List를 실무에서 더 많이 쓴다"는
정도의 느낌으로 시도해본 것에 가까웠고, count 쿼리가 완전히 사라지면서 이 정도로 극적인 개선이
나올 거라고는 예상하지 못했다.

반환 타입을 `Page<Post>`에서 `List<Post>`로 바꿔, count 쿼리 자체가 나가지 않도록 했다.
`size=5` 호출 시 `Hibernate:` 블록이 메인 조회 1개만 찍히는 것을 확인했다.

<details>
<summary>k6 raw 결과 — JOIN FETCH + List 전환 (page=0, size=20)</summary>

```
checks_total.......: 1857    92.632044/s
checks_succeeded...: 100.00% 1857 out of 1857

http_req_duration..............: avg=7.06ms   min=1.83ms   med=5ms      max=90.95ms  p(90)=10.01ms  p(95)=13.53ms  p(99)=48.21ms
http_reqs......................: 1857   92.632044/s
```
</details>

### N+1 세 버전 종합 비교

| 버전                         | 쿼리 개수(size=20 기준) | p95 | p99 | 20초간 처리량 |
|----------------------------| --- | --- | --- | --- |
| N+1 발생                     | 21개 (1+1+19, 캐시 히트로 20이 아닌 19) | 341.98ms | 832.94ms | 629건 |
| JOIN FETCH + Page          | 2개 | **2.31초** (더 나쁨) | 2.57초 | 137건 |
| **JOIN FETCH + List (최종)** | **1개** | **13.53ms** | **48.21ms** | **1,857건** |

최초 N+1 대비 최종본은 p95 기준 약 25배, 잘못 고친 중간 버전 대비로는 약 170배 개선됐다.

---

## 왜 그런지 정리 (5문장)

1. OFFSET은 인덱스를 타더라도 지정한 개수(90,000)만큼 실제로 읽고 버려야 하는 구조적 한계가 있어,
   `rows=89889`라는 실행계획 근거와 k6 p95 1.55초(OFFSET 0 대비 수천 배)로 이를 직접 확인했다.
2. 논리적으로 동일한 조건이라도 SQL 표현 방식에 따라 옵티마이저가 인덱스를 다르게 활용한다 —
   row constructor `(a,b)<(?,?)`는 `Filter`로 처리되어 8만 건 이상을 훑었지만, 동일한 조건을 OR로
   풀어쓰자 `Index range scan`으로 바뀌어 20건만 읽는 것으로 줄었다.
3. 이 SQL 레벨 차이는 API 레벨 부하 테스트(k6)에서도 그대로 이어져, 커서 방식이 OFFSET 방식보다
   p95 기준 약 45배 빠르고 동시 처리량도 약 10배 많았다.
4. N+1을 없애려고 JOIN FETCH를 걸었을 때, 쿼리 개수는 22개에서 2개로 줄었지만 `Page` 타입이
   자동으로 만드는 count 쿼리에까지 JOIN이 따라붙어 89,889번 반복되는 Nested Loop가 발생했고,
   그 결과 API가 오히려 약 7배 느려졌다 — 쿼리 개수 감소가 항상 성능 개선을 의미하지 않는다는 것을
   실측으로 확인했다.
5. `Page`를 `List`로 바꿔 count 쿼리 자체를 없애자 비로소 p95가 13.53ms까지 떨어졌고, 이는 원인을
   "쿼리 개수"가 아니라 "각 쿼리의 실행계획"까지 직접 확인해야 진짜 해결에 도달할 수 있다는 것을
   보여준 사례였다.

## 참고 — 이번 주 추가/변경된 파일

- `PostRepository.findFeedByCursor`: 커서 기반 조회 (OR 체인 방식 채택)
- `PostController.feedCursor`: 커서 조회 엔드포인트
- `Post.author` (`@ManyToOne(LAZY)`, `insertable/updatable=false`): N+1 실험용 연관관계
- `PostFeedItem`: N+1 실험 응답 DTO
- `PostController.feedWithAuthor` / `feedWithAuthorFixed`: N+1 발생/해결 버전
- `PostRepository.findByStatusWithAuthor`: JOIN FETCH + List 반환 (count 쿼리 제거)
- `application.yml`: `spring.jpa.show-sql: true`, `logging.level.org.hibernate.orm.jdbc.bind: TRACE` 추가
- `k6/offset-deep.js`, `k6/cursor-deep.js`, `k6/n-plus-one.js`, `k6/n-plus-one-fixed.js`

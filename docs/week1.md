# 1주차 — DB 최적화: 복합 인덱스 실험

대상 API: `GET /api/posts/feed` (`PostController#feed`)
대상 쿼리:
```sql
SELECT * FROM posts WHERE status='PUBLISHED'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0;
```

## 기본 루프

측정 → 가설 → 한 번에 하나만 변경 → 재측정 → 결론, 순서로 진행했다.
인덱스 3종(idx_a, idx_b, idx_c)을 동시에 걸지 않고, `DROP INDEX` 후 다음 인덱스를 거는 방식으로
매번 단독 상태에서 측정해 어떤 변경이 효과였는지 섞이지 않게 했다.

## 요약표

| 항목 | 내용 |
| --- | --- |
| 환경 | MySQL 8.4 (Docker, mysql:8.4 이미지), posts 100,000건 / likes 500,000건 / users 1,000건, 로컬 Docker Desktop 기본 리소스 |
| 문제 | 피드 조회(`/api/posts/feed`) 쿼리가 인덱스 없는 상태에서 중앙값 75.0ms, 최대 89.9ms |
| 가설 | `status`, `created_at`에 인덱스가 없어 옵티마이저가 `Table scan`으로 100,000건 전체를 읽고, 별도의 `Sort`(filesort)까지 수행하기 때문일 것이다 |
| 변경 | `idx_posts_status_created_at_id (status, created_at DESC, id DESC)` 단독 추가 (V2 마이그레이션) |
| 측정 | 베이스라인 중앙값 75.0ms → 적용 후 중앙값 0.096ms (781배). 재현 확인(reset.sh 후 재측정): 0.178ms |
| 트레이드오프 | posts 쓰기(INSERT) 시 인덱스 유지 비용 추가 발생 — 실측 결과 10,000건 INSERT 기준 인덱스 있을 때 중앙값 0.13초, 없을 때 0.09초로 약 44% 느려짐(자세한 근거는 아래 "쓰기 비용 측정" 참고). status 값이 자주 바뀌는 워크로드라면(예: DRAFT→PUBLISHED 전환이 잦음) 갱신 비용이 더 커질 수 있음. 저장 공간은 `information_schema.TABLES`로 실측한 결과 posts 100,000건 기준 데이터 14.52MB, `idx_posts_status_created_at_id` 인덱스 자체는 5.52MB (데이터의 약 38%) `ANALYZE TABLE` 전후로 값이 동일했는데, 이는 `DATA_LENGTH`/`INDEX_LENGTH`가 통계 추정치가 아니라 InnoDB가 실제로 할당한 페이지 크기를 그대로 반영하기 때문이다 |

## 쓰기 비용 측정 (INSERT 1만 건, 인덱스 유무 비교)

동일한 posts 100,000건 상태에서, INSERT 10,000건 → 시간 기록 → DELETE로 원상복구를 반복하며
인덱스가 있을 때/없을 때를 비교했다. (한 번에 하나만 바꾸는 원칙에 따라 DROP INDEX로 인덱스만 제거하고
나머지 조건은 그대로 유지)

| 상태 | 측정값(초) | 중앙값(초) |
| --- | --- | --- |
| 인덱스 있음 (idx_posts_status_created_at_id) | 0.27*, 0.13, 0.14, 0.15, 0.12, 0.13 | 0.13 (*첫 실행은 캐시 워밍업으로 제외) |
| 인덱스 없음 | 0.09, 0.07, 0.10 | 0.09 |

인덱스가 있을 때 10,000건 INSERT가 약 44% 더 걸렸다 (0.09초 → 0.13초). 처음 예상했던 "10~30% 정도
느려질 것"보다 다소 크게 나왔는데, 인덱스가 하나뿐이고 반복 횟수(3~6회)가 적어 표본이 크지 않다는
점을 감안해야 한다.

덤으로, 측정을 마치고 인덱스를 다시 만들 때(`CREATE INDEX ... ON posts (...)`) 0.55초가 걸렸다.
이는 매 INSERT마다 드는 유지 비용과는 다른, 기존 100,000건 전체를 스캔해서 인덱스를 처음부터
구축하는 일회성 비용이다.

<details>
<summary>Raw 터미널 로그 (INSERT/DELETE/DROP/CREATE 전체 실행 순서)</summary>

```
-- 인덱스 있는 상태에서 INSERT 4회 연속 (터미널 입력 중 일부 붙여넣기 오류로 DELETE 없이 연달아 실행됨)
INSERT ... Query OK, 10000 rows affected (0.27 sec)   -- 워밍업, 중앙값 제외
INSERT ... Query OK, 10000 rows affected (0.13 sec)
INSERT ... Query OK, 10000 rows affected (0.14 sec)
INSERT ... Query OK, 10000 rows affected (0.15 sec)
DELETE FROM posts WHERE title LIKE 'bulk test title %';  -- Query OK, 40000 rows affected (0.45 sec)

-- 이후 INSERT/DELETE를 한 쌍씩 반복
INSERT ... Query OK, 10000 rows affected (0.12 sec)
DELETE ... Query OK, 10000 rows affected (0.14 sec)

INSERT ... Query OK, 10000 rows affected (0.13 sec)
DELETE ... Query OK, 10000 rows affected (0.17 sec)

-- 인덱스 제거
DROP INDEX idx_posts_status_created_at_id ON posts;  -- Query OK, 0 rows affected (0.06 sec)

-- 인덱스 없는 상태에서 INSERT/DELETE 3쌍
INSERT ... Query OK, 10000 rows affected (0.09 sec)
DELETE ... Query OK, 10000 rows affected (0.11 sec)

INSERT ... Query OK, 10000 rows affected (0.07 sec)
DELETE ... Query OK, 10000 rows affected (0.12 sec)

INSERT ... Query OK, 10000 rows affected (0.10 sec)
DELETE ... Query OK, 10000 rows affected (0.11 sec)

-- 인덱스 원상복구 (일회성 구축 비용 측정)
CREATE INDEX idx_posts_status_created_at_id ON posts (status, created_at DESC, id DESC);
-- Query OK, 0 rows affected (0.55 sec)
```
</details>

## 실험 상세 — 후보 3종 비교

동일 조건(posts 100,000건, `status='PUBLISHED'` 90,171건)에서 각각 단독으로 인덱스를 걸고
`EXPLAIN ANALYZE`를 5~7회 반복 측정했다 (첫 실행은 캐시 워밍업으로 제외, 이후 값의 중앙값 사용).

| 인덱스 | 컬럼 순서 | 중앙값(ms) | 베이스라인 대비 | 실행계획 |
| --- | --- | --- | --- | --- |
| (베이스라인) | 없음 | 75.0 | - | `Table scan` → `Filter` → `Sort`(filesort) → `Limit` |
| idx_a | `(created_at DESC, id DESC)` | 0.135 | 약 556배 | `Index scan` → `Filter` → `Limit` (정렬만 인덱스로 해결, status는 스캔 중 필터링) |
| **idx_b (채택)** | **`(status, created_at DESC, id DESC)`** | **0.096** | **약 781배** | **`Index lookup` → `Limit` (단일 단계, Filter/Sort 모두 제거)** |
| idx_c | `(created_at DESC, status)` | 57.6 | 약 1.3배 (사실상 무효) | `Table scan` → `Filter` → `Sort` (옵티마이저가 인덱스를 아예 사용하지 않음) |

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — 베이스라인 (6회 측정: 89.9, 78.8, 72.7, 70.7, 77.2, 64.8ms)</summary>

```
-- Run 1 (89.9ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=89.9..89.9 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=89.9..89.9 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.063..70.1 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0588..53.7 rows=100000 loops=1)

-- Run 2 (78.8ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=78.8..78.8 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=78.8..78.8 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0248..62.2 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0224..47.4 rows=100000 loops=1)

-- Run 3 (72.7ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=72.7..72.7 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=72.7..72.7 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0189..52.7 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0172..40.1 rows=100000 loops=1)

-- Run 4 (70.7ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=70.7..70.7 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=70.7..70.7 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0158..55.9 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0145..43 rows=100000 loops=1)

-- Run 5 (77.2ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=77.2..77.2 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=77.2..77.2 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0238..60.2 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0216..46.5 rows=100000 loops=1)

-- Run 6 (64.8ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=64.8..64.8 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=64.8..64.8 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0197..51.1 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0176..39.2 rows=100000 loops=1)
```
</details>

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — idx_a (6회 측정: 4.09*, 0.244, 0.135, 0.221, 0.111, 0.132ms / *첫 실행은 캐시 워밍업으로 중앙값 계산에서 제외)</summary>

```
-- Run 1 (4.09ms, 워밍업 - 중앙값 계산 제외)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=4.02..4.09 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=4.02..4.09 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.478..0.568 rows=35 loops=1)

-- Run 2 (0.244ms)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=0.0599..0.244 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=0.0583..0.24 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.0348..0.227 rows=35 loops=1)

-- Run 3 (0.135ms, 중앙값)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=0.0422..0.135 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=0.0409..0.133 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.025..0.125 rows=35 loops=1)

-- Run 4 (0.221ms)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=0.0775..0.221 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=0.0761..0.219 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.0465..0.201 rows=35 loops=1)

-- Run 5 (0.111ms)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=0.0377..0.111 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=0.0367..0.109 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.0213..0.103 rows=35 loops=1)

-- Run 6 (0.132ms)
-> Limit: 20 row(s)  (cost=1.85 rows=2) (actual time=0.0425..0.132 rows=20 loops=1)
    -> Filter: (posts.`status` = 'PUBLISHED')  (cost=1.85 rows=2) (actual time=0.0412..0.129 rows=20 loops=1)
        -> Index scan on posts using idx_a  (cost=1.85 rows=20) (actual time=0.0259..0.121 rows=35 loops=1)
```
</details>

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — idx_b (6회 측정: 0.127, 0.0679, 0.0913, 0.111, 0.101, 0.0795ms)</summary>

```
-- Run 1 (0.127ms)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.045..0.127 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0431..0.123 rows=20 loops=1)

-- Run 2 (0.0679ms)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.014..0.0679 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0133..0.0661 rows=20 loops=1)

-- Run 3 (0.0913ms, 중앙값에 가장 근접)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.0368..0.0913 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0361..0.0892 rows=20 loops=1)

-- Run 4 (0.111ms)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.0258..0.111 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0246..0.108 rows=20 loops=1)

-- Run 5 (0.101ms)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.0201..0.101 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0191..0.0977 rows=20 loops=1)

-- Run 6 (0.0795ms)
-> Limit: 20 row(s)  (cost=5657 rows=20) (actual time=0.0157..0.0795 rows=20 loops=1)
    -> Index lookup on posts using idx_b (status='PUBLISHED')  (cost=5657 rows=49598) (actual time=0.0151..0.0777 rows=20 loops=1)
```
</details>

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — idx_c (7회 측정: 57.0, 58.7, 57.6, 56.2, 54.6, 58.1, 58.4ms)</summary>

```
-- Run 1 (57.0ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=57..57 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=57..57 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0228..45.3 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0207..34.4 rows=100000 loops=1)

-- Run 2 (58.7ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=58.7..58.7 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=58.7..58.7 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0206..46.7 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0191..35.8 rows=100000 loops=1)

-- Run 3 (57.6ms, 중앙값)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=57.6..57.6 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=57.6..57.6 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0194..45.7 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0181..34.5 rows=100000 loops=1)

-- Run 4 (56.2ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=56.2..56.2 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=56.2..56.2 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0175..44.7 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0159..33.9 rows=100000 loops=1)

-- Run 5 (54.6ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=54.6..54.6 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=54.6..54.6 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.033..43.6 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0292..33 rows=100000 loops=1)

-- Run 6 (58.1ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=58.1..58.1 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=58.1..58.1 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0133..46 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0122..35.1 rows=100000 loops=1)

-- Run 7 (58.4ms)
-> Limit: 20 row(s)  (cost=10152 rows=20) (actual time=58.4..58.4 rows=20 loops=1)
    -> Sort: posts.created_at DESC, posts.id DESC, limit input to 20 row(s) per chunk  (cost=10152 rows=99196) (actual time=58.4..58.4 rows=20 loops=1)
        -> Filter: (posts.`status` = 'PUBLISHED')  (cost=10152 rows=99196) (actual time=0.0202..46.5 rows=90171 loops=1)
            -> Table scan on posts  (cost=10152 rows=99196) (actual time=0.0187..35.3 rows=100000 loops=1)
```
</details>

## 왜 빨라졌거나 빨라지지 않았는지 (5문장)

1. 베이스라인은 `posts`에 PK 외 인덱스가 없어 옵티마이저가 `Table scan`으로 100,000건 전체를 읽고, `status` 필터링(90,171건 통과) 후 `created_at DESC, id DESC` 정렬을 위한 filesort까지 수행해 중앙값 75ms가 걸렸다.
2. idx_a(`created_at, id`)는 정렬 순서를 인덱스로 해결해 filesort는 사라졌지만, `status` 조건은 인덱스에 없어 스캔하며 건별로 다시 확인해야 했고, 그래도 35건만 읽고 20개를 채워 0.135ms로 크게 빨라졌다.
3. idx_b(`status, created_at, id`)는 WHERE 절의 등치 조건인 `status`를 인덱스 맨 앞에 둬서 "PUBLISHED 구간"으로 바로 점프한 뒤 이미 정렬된 순서대로 20개를 그대로 반환할 수 있었고, 이 덕분에 idx_a에는 남아있던 `Filter` 단계까지 실행계획에서 완전히 사라졌다. idx_a(0.135ms)와 idx_b(0.096ms)의 절대 시간 차이(0.039ms)는 이 정도 스케일에서는 측정 노이즈 범위 안이라 그 자체로 우열의 근거는 아니지만, `Filter` 단계 유무라는 실행계획 구조 차이는 등치 조건을 앞에 두는 원칙이 실제로 효과가 있었다는 근거가 된다.
4. idx_c(`created_at, status`)는 `id`가 인덱스에 빠져 있어 옵티마이저가 이 인덱스로 정렬 순서(`created_at DESC, id DESC`)를 보장할 수 없다고 판단해 인덱스 자체를 사용하지 않았고, 그 결과 베이스라인과 거의 같은 57.6ms가 나왔다 — 인덱스가 있다고 무조건 빨라지는 게 아니라 컬럼 순서와 쿼리의 WHERE/ORDER BY 조건이 정확히 맞물려야 효과가 있다는 것을 보여준다. 다만 idx_c(57.6ms)가 베이스라인 최초 측정값(75.0ms, 반복 범위 64.8~89.9ms)보다 낮게 나온 것은 인덱스 효과가 아니라, 앞선 idx_a/idx_b 실험을 거치며 InnoDB 버퍼 풀에 관련 데이터 페이지가 이미 캐싱되어 있었을 가능성이 크다 — idx_c 자체의 측정값이 54.6~58.7ms로 촘촘하게 모여 있고(베이스라인의 25ms 편차보다 훨씬 안정적) 인덱스 사용 흔적도 실행계획에 없어서, 이 차이는 무작위 노이즈보다는 측정 순서에 따른 캐시 상태 차이로 보는 것이 더 정확하다.
5. `status='PUBLISHED'`의 선택도가 낮은(전체의 90%) 이번 데이터에서는 idx_a도 충분히 빨랐지만, 만약 이 값의 비율이 훨씬 낮았다면(예: 1%) idx_a는 20개를 채우기 위해 훨씬 많은 행을 순서대로 훑어야 해 성능 차이가 더 벌어졌을 것이므로, 실제 데이터 분포를 고려했을 때 등치 조건을 선행하는 idx_b가 더 일반적으로 안전한 선택이다.


## 면접 답변 정리
Q1. 인덱스는 언제 추가하시나요?

인덱스는 주로 조회 속도가 저하되었을 때 추가하여 조회 속도를 향상시키기 위해서 추가합니다. 다만 단순히 컬럼이 조회 조건에 있다는 이유만으로 걸지는 않고, 실행계획을 먼저 확인해서 실제로 풀스캔이나 정렬 비용이 발생하는지 보고 판단합니다. **인덱스를 설계할 때는 선택도, 쓰기 빈도, 기존 인덱스와의 중복까지 함께 고려해서 최소한으로 만듭니다.**

Q2. 복합 인덱스의 컬럼 순서는 어떻게 정하나요?

일반적으로 등치 조건으로 사용되는 컬럼을 우선적으로 배치합니다. **컬럼 순서는 임의로 정하는 게 아니라, 가장 빈번하게 실행되는 실제 쿼리를 기준으로 정합니다.** 등치 조건이 앞에 있으면 탐색 범위를 먼저 좁힐 수 있고, 그 안에서 나머지 정렬 조건이 이미 정렬된 상태로 남아있어서 별도 정렬 작업이 필요 없어지기 때문입니다. 다만 이게 고정된 공식은 아니라서, 실제로 컬럼 순서를 정한 뒤엔 실행계획으로 검증하는 과정이 필요하다고 생각합니다.

Q3. status처럼 값이 적은 컬럼에도 인덱스를 만들 수 있나요?

만들 수는 있으나 옵티마이저에서 사용할 가능성이 적을 수 있어 인덱스 생성 시 확인이 필요합니다. 값의 종류가 적고 특정 값에 데이터가 몰려 있는(선택도가 낮은) 컬럼은 단독 인덱스로 걸어도 결국 많은 행을 읽어야 해서 효과가 작기 때문입니다. 다만 이런 컬럼도 다른 조건(범위 조건이나 정렬 조건)과 함께 복합 인덱스로 묶이면 의미가 있을 수 있다고 알고 있습니다.

Q4. OFFSET 대신 커서 페이지네이션을 택한 이유는 무엇인가요?

OFFSET은 "몇 번째부터 보여줘"가 아니라, 앞부분을 다 읽고 버린 다음 나머지를 보여주는 방식이라, 페이지가 뒤로 갈수록 읽고 버리는 양이 계속 늘어난다고 알고 있습니다. 커서 방식은 마지막으로 본 위치 이후만 읽으면 돼서, 페이지가 깊어져도 성능이 크게 나빠지지 않는다는 게 핵심 차이라고 이해하고 있습니다. **다만 커서 방식은 페이지 번호로 임의의 페이지에 바로 이동하기 어렵고, 정렬 기준이 안정적이어야 한다는 트레이드오프가 있어서 created_at뿐 아니라 id까지 커서와 정렬 기준에 함께 포함해야 합니다.**

Q5. 인덱스를 만들었는데 DB가 사용하지 않습니다. 무엇을 보시겠습니까?

실행계획을 먼저 확인하고, 인덱스가 실행계획에 아예 안 나오는지를 봅니다. 조건이 너무 넓어서 옵티마이저가 테이블 스캔이 더 싸다고 판단했을 수도 있고, 복합 인덱스의 왼쪽 컬럼을 조건에 안 썼을 수도 있습니다. 그 외에 컬럼 타입 불일치나 함수를 적용한 조건도 인덱스를 못 타게 만드는 원인이라, 이런 부분들을 하나씩 확인해봐야합니다. **통계 정보가 오래돼서 옵티마이저가 잘못 판단했을 가능성도 함께 확인하고, 힌트로 인덱스 사용을 강제하기보다는 쿼리와 통계를 먼저 점검한 뒤에 판단합니다.**

Q6. 모든 조회 컬럼에 인덱스를 걸면 안 되는 이유는 무엇인가요?

모든 조회 컬럼에 인덱스를 건다고 조회 속도가 빨라지지 않고, 인덱스가 추가될 때마다 용량을 차지하기 때문에 모든 컬럼에 인덱스를 거는 것은 좋지 않습니다. 게다가 인덱스가 많아지면 데이터를 쓸 때마다(INSERT, UPDATE, DELETE) 그 인덱스들을 같이 갱신해야 해서 쓰기 성능도 떨어집니다. **비슷한 인덱스가 여러 개 있으면 옵티마이저가 실행계획을 판단하기도 복잡해지고, 운영 관리 부담도 늘어납니다.** 그래서 조회 빈도와 성능 효과가 실제로 검증된 쿼리에 한정해서 인덱스를 만드는 게 맞다고 생각합니다.




## 커버링 인덱스 검증

`SELECT *` 대신, 인덱스에 이미 포함된 컬럼(`id`, `status`, `created_at`)만 SELECT해서 실제로
"Covering index"로 인식되는지, 그리고 시간이 달라지는지 확인했다.

```sql
SELECT id, status, created_at FROM posts WHERE status='PUBLISHED'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0;
```

| 항목 | `SELECT *` (idx_b) | `SELECT id, status, created_at` (커버링) |
| --- | --- | --- |
| 중앙값 | 0.096ms | 0.0803ms (측정값: 0.137, 0.127, 0.045, 0.0803, 0.0371) |
| 실행계획 | `Index lookup on posts using idx_b` | `Covering index lookup on posts using idx_posts_status_created_at_id` |

실행계획에 `Covering`이라는 단어가 실제로 붙는 것을 확인했다 — 테이블 원본 행을 찾아가는 lookup을
생략하고 인덱스만으로 끝났다는 증거다. 다만 시간 자체는 눈에 띄게 빨라지지 않았는데(둘 다 노이즈
범위 내), 이는 어차피 20건만 조회하는 쿼리라 lookup 자체의 비용이 원래도 작았기 때문으로 보인다.
즉 커버링 인덱스는 "읽어야 할 행 수가 많을 때(수백~수천 건 이상)" 효과가 크게 드러나는 것이지,
지금처럼 LIMIT으로 적은 행만 다루는 쿼리에서는 구조적으로는 맞지만 체감 성능 차이는 작다.

<details>
<summary>Raw EXPLAIN ANALYZE 출력 — 커버링 인덱스 (5회 측정: 0.137, 0.127, 0.045, 0.0803, 0.0371ms)</summary>

```
-- Run 1 (0.137ms)
-> Limit: 20 row(s)  (cost=5583 rows=20) (actual time=0.134..0.137 rows=20 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5583 rows=49619) (actual time=0.118..0.12 rows=20 loops=1)

-- Run 2 (0.127ms)
-> Limit: 20 row(s)  (cost=5583 rows=20) (actual time=0.123..0.127 rows=20 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5583 rows=49619) (actual time=0.122..0.125 rows=20 loops=1)

-- Run 3 (0.045ms)
-> Limit: 20 row(s)  (cost=5583 rows=20) (actual time=0.0422..0.045 rows=20 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5583 rows=49619) (actual time=0.0414..0.0435 rows=20 loops=1)

-- Run 4 (0.0803ms, 중앙값)
-> Limit: 20 row(s)  (cost=5583 rows=20) (actual time=0.0501..0.0803 rows=20 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5583 rows=49619) (actual time=0.0494..0.0787 rows=20 loops=1)

-- Run 5 (0.0371ms)
-> Limit: 20 row(s)  (cost=5583 rows=20) (actual time=0.0345..0.0371 rows=20 loops=1)
    -> Covering index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED')  (cost=5583 rows=49619) (actual time=0.0339..0.0357 rows=20 loops=1)
```
</details>

## 참고 — 마이그레이션 이력

- `V1__baseline_schema.sql`: PK만 존재하는 베이스라인 (0주차)
- `V2__add_posts_feed_index.sql`: 실험으로 검증한 `(status, created_at DESC, id DESC)` 복합 인덱스 추가

## 자가 검증 (제출 전 스스로 확인)

문서를 닫고, 아래 두 질문에 막힘없이 답할 수 있는지 확인했다.

### Q. idx_a(0.135ms)와 idx_b(0.096ms) 차이가 숫자상 의미 있나?

**처음 답변:** "쿼리에 `WHERE status='PUBLISHED'` 조건이 있으니까, 그 `status`를 인덱스에 포함시킨
idx_b가 더 나은 선택"이라고 답했다. 이 설계 논리 자체는 정확했다 — idx_b가 idx_a보다 나은 선택이라는
결론은 맞다.

**보완한 부분:** 다만 질문의 핵심은 "그 선택이 맞다"가 아니라 "0.135ms와 0.096ms라는 **시간 차이
자체가 그 선택을 증명하는 근거가 되는가"였다. 이 절대 시간 차이(0.039ms)는 노이즈 범위라 그 자체로는
증거가 되지 않는다. 진짜 증거는 실행계획 구조다 — idx_a는 `status`가 인덱스에 없어 읽어온 행에서
`Filter` 단계로 다시 걸러내야 하지만, idx_b는 `status`가 인덱스 맨 앞에 있어 이 `Filter` 단계 자체가
사라진다. 지금 데이터(PUBLISHED 90%)에서는 이 구조 차이가 시간으로 뚜렷하게 드러나지 않았지만,
PUBLISHED 비율이 낮을수록(예: 1%) idx_a는 훨씬 많은 행을 읽어야 해 격차가 커질 것이다.

### Q. `(status, created_at ASC, id ASC)`로 걸었으면 어떻게 됐을까?

**처음 답변(수정 전):** "같은 실험 결과가 나오지 않았을까?"라고 추측만 하고 이유를 설명하지 못했다.

**직접 실험해서 확인:** 실제로 인덱스를 `(status, created_at ASC, id ASC)`로 다시 만들고 동일 쿼리를
`EXPLAIN ANALYZE`로 3회 측정했다.

```
-- Run 1 (1.39ms, 워밍업 - 방금 재생성한 인덱스라 버퍼 풀에 데이터가 없어 느림)
-> Limit: 20 row(s)  (cost=5659 rows=20) (actual time=0.953..1.39 rows=20 loops=1)
    -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED') (reverse)  (cost=5659 rows=49619) (actual time=0.721..1.15 rows=20 loops=1)

-- Run 2 (0.0982ms)
-> Limit: 20 row(s)  (cost=5659 rows=20) (actual time=0.0242..0.0982 rows=20 loops=1)
    -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED') (reverse)  (cost=5659 rows=49619) (actual time=0.023..0.0957 rows=20 loops=1)

-- Run 3 (0.0977ms)
-> Limit: 20 row(s)  (cost=5659 rows=20) (actual time=0.0246..0.0977 rows=20 loops=1)
    -> Index lookup on posts using idx_posts_status_created_at_id (status='PUBLISHED') (reverse)  (cost=5659 rows=49619) (actual time=0.0235..0.0953 rows=20 loops=1)
```

워밍업을 제외한 두 값(0.0982ms, 0.0977ms)의 평균은 약 0.098ms로, 원래 `DESC, DESC`로 걸었을 때의
중앙값(0.096ms)과 사실상 동일했다 — 노이즈 범위 안의 차이다.

**확인한 이유:** MySQL 8.0부터 지원하는 `(reverse)`(EXPLAIN ANALYZE 트리 표기 기준. 전통적인 표
형태 EXPLAIN에서는 `Extra` 컬럼에 `Backward index scan`으로 표시된다) 덕분에, 인덱스를 `ASC`로
걸어도 옵티마이저가 인덱스를 거꾸로 읽어서 원하는 정렬 순서(`DESC`)를 그대로 만들어낼 수 있다.
그래서 `ASC`로 걸든 `DESC`로 걸든 이번 쿼리에서는 성능 차이가 없었다.

**그럼 인덱스를 `DESC`로 만든 이유는?** 이번 케이스에서는 사실 `DESC`가 필수는 아니었다. `DESC`
방향이 실제로 필요해지는 경우는 정렬 방향이 컬럼마다 섞일 때다 — 예를 들어
`ORDER BY created_at DESC, id ASC`처럼 한쪽은 내림차순, 한쪽은 오름차순이면, 단순히 인덱스를
거꾸로 읽는 것만으로는 두 컬럼의 정렬 방향을 동시에 만족시킬 수 없어, 인덱스 자체를
`(created_at DESC, id ASC)`처럼 컬럼마다 다른 방향으로 만들어야 한다. (참고로 MySQL 5.7 이전
버전에서는 `CREATE INDEX`에 `DESC`를 명시해도 무시되고 항상 오름차순으로 생성됐다.)

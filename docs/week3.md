# 3주차 — 트랜잭션 · 락 · 동시성

## 기본 루프

이번 주도 실험 전에 먼저 예측을 세우고, 실행 후 결과와 비교하는 방식으로 진행한다.

---

## 실험 1 — 격리수준 차이 재현 (READ COMMITTED vs REPEATABLE READ)

### 실험 전 예측

**질문**: 기본 격리수준(REPEATABLE READ)인 상태에서, 세션 A가 트랜잭션을 시작하고 조회한 뒤,
세션 B가 그 행을 수정하고 커밋을 완료했다. 이후 세션 A가 같은 조회를 다시 하면 변경된 값이
보일까?

**예측**: REPEATABLE READ는 트랜잭션 시작 시점(첫 조회 시점)의 스냅샷을 조회하는 것이므로,
세션 B가 그 이후에 커밋을 해도 세션 A의 스냅샷에는 반영되어 있지 않았을 것이다. 따라서 변경된
값은 조회되지 않을 것이라고 예상했다.

### 실제 결과

```
[세션 A]
mysql> SELECT @@transaction_isolation;
+-------------------------+
| @@transaction_isolation |
+-------------------------+
| REPEATABLE-READ         |
+-------------------------+

mysql> START TRANSACTION;
mysql> SELECT id, status FROM posts WHERE id = 1;
+----+-----------+
| id | status    |
+----+-----------+
|  1 | PUBLISHED |
+----+-----------+

[세션 B]
mysql> UPDATE posts SET status = 'DRAFT' WHERE id = 1;
mysql> COMMIT;

[세션 A] (세션 B의 COMMIT 이후 재조회)
mysql> SELECT id, status FROM posts WHERE id = 1;
+----+-----------+
| id | status    |
+----+-----------+
|  1 | PUBLISHED |    ← 여전히 PUBLISHED. 세션 B의 커밋이 반영되지 않음.
+----+-----------+
```

### 예측과 비교

세션 B가 커밋을 완료했음에도, 세션 A는 트랜잭션 시작(첫 조회) 시점에
찍힌 스냅샷을 계속 보고 있어서 변경 사항이 전혀 반영되지 않았다.

---

## 실험 2 — READ COMMITTED로 같은 실험 재현 (비교용)

### 실험 전 예측

실험 1과 반대로, READ COMMITTED는 매 SELECT마다 그 시점의 최신 커밋된 데이터를 새로 읽으므로,
세션 B가 커밋한 이후 세션 A가 재조회하면 변경된 값이 바로 보일 것이라고 예상했다.

### 실제 결과

```
[세션 A]
mysql> SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
mysql> START TRANSACTION;
mysql> SELECT id, status FROM posts WHERE id = 1;
+----+--------+
| id | status |
+----+--------+
|  1 | DRAFT  |
+----+--------+

[세션 B]
mysql> UPDATE posts SET status = 'PUBLISHED' WHERE id = 1;
mysql> COMMIT;

[세션 A] (재조회)
mysql> SELECT id, status FROM posts WHERE id = 1;
+----+-----------+
| id | status    |
+----+-----------+
|  1 | PUBLISHED |    ← 바뀐 값이 바로 보임
+----+-----------+
```

### 예측과 비교

예측이 일치했다. REPEATABLE READ(실험 1)에서는 스냅샷이 고정되어 변경 사항이 안
보였지만, READ COMMITTED(실험 2)에서는 매 조회마다 최신 커밋 데이터를 다시 읽어서 바뀐 값이
즉시 보였다. 두 실험을 나란히 놓고 보면 "스냅샷을 언제 찍는가"(트랜잭션 시작 시 한 번 vs 매
조회마다)의 차이가 실제 조회 결과 차이로 이어진다는 것을 직접 확인했다.

---

## 실험 3 — 갭 락으로 INSERT 대기 재현

### 실험 전 예측

세션 A가 범위 조건(`FOR UPDATE`)으로 잠금을 걸어두면, 세션 B가 그 범위 안에 들어가는 새로운
행을 INSERT하려고 할 때 세션 A의 트랜잭션이 끝날 때까지 대기 상태로 멈출 것이라고 예상했다.

### 시행착오 과정

곧바로 성공하지 않고 원인을 한 번 잘못 짚었다. 그 과정 자체가 갭 락의 동작 조건을 이해하는 데
중요했다.

1. **1차 시도**: `id BETWEEN 100000 AND 100010` 범위와 `id > 100005` 범위 둘 다로 시도했으나
   세션 B의 INSERT가 매번 즉시 성공했다. 원인은 세션 A가 실험 2에서 설정한 `READ COMMITTED`를
   리셋하지 않고 그대로 물고 있었던 것 — READ COMMITTED에서는 인덱스 스캔 시 갭 락 자체가
   완전히 비활성화되므로, 범위가 비어 있든 아니든 갭 락이 걸리지 않는다. 실제로 `data_locks`로
   확인해도 레코드 락(`REC_NOT_GAP`) 또는 아무 락도 안 잡힌 상태(`TABLE IX`만)만 확인됐다.
2. **정상 재현**: `SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;`로 격리수준을
   되돌리고, `SELECT id FROM posts WHERE id BETWEEN 100000 AND 100010;`으로 실제 존재하는
   값을 먼저 확인(`100000`, `100003`만 존재)한 뒤, 완전히 빈 값인 `id = 100002`로 등치 조회
   (`FOR UPDATE`)했다. 세션 B의 INSERT는 43초간 대기하다가, 세션 A가 `COMMIT`하는 순간 즉시
   성공했다 — 갭 락이 걸려 있다가 트랜잭션 종료와 동시에 풀리는 것을 직접 확인했다.

### 실제 결과

```
[세션 A]
mysql> SELECT @@transaction_isolation;
+-------------------------+
| @@transaction_isolation |
+-------------------------+
| REPEATABLE-READ         |
+-------------------------+

mysql> START TRANSACTION;
mysql> SELECT * FROM posts WHERE id = 100002 FOR UPDATE;
Empty set (0.00 sec)

[세션 B]
mysql> INSERT INTO posts (id, user_id, status, title, content, created_at)
    -> VALUES (100002, 1, 'PUBLISHED', 'my gap lock test', 'test', NOW());
(여기서 프롬프트가 반환되지 않고 대기)

[세션 A]
mysql> COMMIT;
Query OK, 0 rows affected (0.00 sec)

[세션 B] (세션 A의 COMMIT 직후 즉시 반환됨)
Query OK, 1 row affected (43.07 sec)
```

### 예측과 비교

예측대로 정확히 맞았다 — 세션 A가 잠금을 걸어둔 상태에서 세션 B의 INSERT는 세션 A가
COMMIT할 때까지 대기했다. 다만 실제로는 예측 하나가 맞았다는 것을 확인하기까지 한 번의
시행착오가 필요했다 — 이전 실험에서 남은 세션 설정(격리수준)이 실험 결과를 왜곡시킬 수 있다는
것을 직접 겪었다.

---

## 참고 — `FOR UPDATE`란

`SELECT ... FOR UPDATE`는 일반 SELECT와 달리 읽은 행(또는 존재하지 않음을 확인한 갭)에
배타적 락(X)을 실제로 거는 "잠금 읽기(Locking Read)" 구문이다.

**일반 SELECT와의 차이**: InnoDB는 기본적으로 MVCC 방식이라, 평범한 SELECT는 그 시점의
스냅샷만 보여주고 아무 락도 걸지 않는다. 다른 트랜잭션의 변경을 전혀 막지 않는다. `FOR UPDATE`를
붙이면 "이 데이터를 곧 수정할 것이니 다른 트랜잭션이 못 건드리게 막아달라"고 명시적으로 요청하는
것이 된다.

**왜 필요한가**: "조회 → 그 값으로 판단 → 수정"이 하나의 트랜잭션 안에서 분리된 여러 문장으로
이어질 때, 그 사이의 틈에 다른 트랜잭션이 끼어드는 것(Race Condition)을 막기 위해 쓴다. 예:
재고 확인 후 차감하는 로직에서 `FOR UPDATE` 없이 조회하면, 두 트랜잭션이 동시에 "재고 있음"을
확인하고 둘 다 차감을 시도해 재고가 음수가 되는 문제가 생길 수 있다.

**`FOR SHARE`와의 차이**: `FOR UPDATE`는 배타적 락(X, 다른 트랜잭션의 읽기 잠금 요청도 막음)을,
`FOR SHARE`(구 `LOCK IN SHARE MODE`)는 공유 락(S, 다른 트랜잭션의 읽기는 허용하되 쓰기는 막음)을
건다.

## 참고 — `SELECT ... FOR UPDATE` vs `INSERT ... ON DUPLICATE KEY UPDATE`

MySQL은 표준 SQL의 `MERGE` 대신 `INSERT ... ON DUPLICATE KEY UPDATE`로 upsert(삽입 또는 갱신)를
지원한다.

```sql
INSERT INTO products (id, stock) VALUES (1, 9)
ON DUPLICATE KEY UPDATE stock = stock - 1;
```

**핵심 차이는 "몇 개의 SQL 문장으로 처리하느냐"다.**

- **`SELECT ... FOR UPDATE` + 별도 UPDATE**: 조회와 수정이 별개의 두 문장으로 분리되어 있다.
  그 사이에 애플리케이션 코드가 값을 보고 복잡한 판단을 내려야 할 때 적합하다. 그 틈을 안전하게
  지키기 위해 명시적으로 락을 걸어야 한다.
- **`INSERT ... ON DUPLICATE KEY UPDATE`**: "있으면 이렇게, 없으면 이렇게"를 단일 문장으로
  DB 엔진이 원자적으로 처리한다. 애플리케이션이 별도로 판단할 필요가 없는 단순한 upsert
  상황에 적합하며, 락을 직접 신경 쓸 필요가 없다(내부적으로는 여전히 락을 쓰지만 겉으로 드러나지
  않는다).

이번에 재현한 갭 락 실험은 "존재 여부를 미리 확인하고 잠가서 그 사이에 다른 트랜잭션이 못
끼어들게 막는다"는 범용적인 동시성 제어 원리를 보여주기 위한 것이었다. 단순 upsert만 필요한
상황이라면 `ON DUPLICATE KEY UPDATE`가 더 간결하고 안전한 선택이다.

---

## 실험 4 — 데드락 재현

### 실험 전 예측

두 세션이 서로 다른 순서로 같은 두 행에 락을 걸려고 하면, 서로가 서로를 기다리는 교착 상태
(데드락)가 될 것이라고 예상했다.

### 실제 결과

```
[세션 A]
mysql> START TRANSACTION;
mysql> UPDATE posts SET title = 'deadlock test A' WHERE id = 1;
Query OK, 1 row affected (0.01 sec)

mysql> UPDATE posts SET title = 'deadlock test A2' WHERE id = 2;
Query OK, 1 row affected (7.55 sec)   ← 대기 후 성공

[세션 B]
mysql> START TRANSACTION;
mysql> UPDATE posts SET title = 'deadlock test B' WHERE id = 2;
Query OK, 1 row affected (0.00 sec)

mysql> UPDATE posts SET title = 'deadlock test B2' WHERE id = 1;
ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction
```

### 예측과 비교

예측대로 데드락이 발생했다. 다만 "둘 다 그냥 영원히 멈춰버릴 것"이라는 단순한 예상과 달리,
실제로는 MySQL이 순환 대기 상태를 능동적으로 감지해서 한쪽(세션 B)을 즉시 강제 실패시키고,
그 덕분에 락을 반납받은 다른 쪽(세션 A)은 곧바로 진행될 수 있었다. 세션 A가 7.55초 대기한
뒤 성공한 것은, 그 시간 동안 세션 B의 두 번째 UPDATE가 아직 실행되지 않아 데드락 자체가
감지되지 않고 있었기 때문이다 — 세션 B의 요청이 들어온 순간 즉시 감지되어 에러가 났다.

**갭 락 실험(락 대기 타임아웃)과의 차이**: 타임아웃은 정해진 시간이 다 지나야 실패로 처리되는
수동적인 방식이지만, 데드락은 "서로가 서로를 기다리는 순환 구조"를 MySQL이 능동적으로 감지해서
그 즉시 한쪽을 희생시키는 방식이다.

---

## 실험 5 — `SHOW ENGINE INNODB STATUS`로 데드락 상세 확인

### 확인해보고 싶었던 것

`LATEST DETECTED DEADLOCK` 섹션을 통해, 실험 4에서 있었던 두 트랜잭션이 각각 어떤 락을 들고
있었고 어떤 락을 기다리고 있었는지, 그리고 MySQL이 정확히 어느 트랜잭션을 롤백시켰는지 로그
원문으로 직접 확인해보고 싶었다.

### 실제 결과

```
*** (1) TRANSACTION: TRANSACTION 2352 (세션 A)
*** (1) HOLDS THE LOCK(S): id=1 행 (X, rec but not gap)
*** (1) WAITING FOR THIS LOCK: id=2 행

*** (2) TRANSACTION: TRANSACTION 2353 (세션 B)
*** (2) HOLDS THE LOCK(S): id=2 행 (X, rec but not gap)
*** (2) WAITING FOR THIS LOCK: id=1 행

*** WE ROLL BACK TRANSACTION (2)
```

### 확인 결과 정리

두 트랜잭션이 서로의 락을 기다리는 순환 구조(A는 id=1 들고 id=2 대기, B는 id=2 들고 id=1
대기)가 로그에 그대로 나타났고, `WE ROLL BACK TRANSACTION (2)`로 트랜잭션 2353(세션 B)이
희생됐다는 것을 명확히 확인했다.

추가로 확인된 것: 두 트랜잭션이 잡은 락 모두 `locks rec but not gap`(순수 레코드 락)이었다.
`id=1`, `id=2` 둘 다 이미 존재하는 값을 등치(`=`) 조건으로 UPDATE했으므로, 실험 3에서 확인한
"존재하는 값 등치 조회는 갭 락 없이 레코드 락만 건다"는 규칙이 여기서도 동일하게 적용됐다.

---

## 실험 6 — Binlog에서 실제 변경 내용 확인

### 확인해보고 싶었던 것

Binlog가 `binlog_format=ROW`(기본값)로 설정되어 있어 SQL 문장이 아니라 행 변경 내용만
바이너리로 기록된다고 알고 있었는데, `SHOW BINLOG EVENTS`로 실제로 어떻게 보이는지, 그리고
`binlog_rows_query_log_events` 같은 설정으로 사람이 읽을 수 있는 원본 SQL까지 확인할 수 있는지
보고 싶었다.

### 시행착오 과정

1. `mysqlbinlog` 명령어가 이 Docker 이미지에 설치되어 있지 않아 실행할 수 없었다. 대신
   `SET binlog_rows_query_log_events = ON;`으로 원본 SQL 문장을 별도 이벤트로 함께 기록하는
   방법으로 전환했다.
2. 이 설정을 켜고 `UPDATE posts SET title = 'binlog readable test' WHERE id = 1;`을
   실행했는데, `SHOW BINARY LOGS;`로 확인한 파일 크기가 전혀 늘지 않았다. 처음에는 `Rows
   matched: 1  Changed: 0`이었던 것(넣으려는 값이 기존 값과 같아 실제 변경이 없었던 것)이
   원인이라고 추정했다.
3. 값이 확실히 달라지도록 `CONCAT('binlog readable test ', NOW())`로 다시 UPDATE했는데도
   파일 크기가 여전히 그대로였다. 이 시점에 이전 추정(Changed: 0 때문)이 틀렸다는 것을 알게
   됐다 — 진짜 원인은 실험 4(데드락)에서 세션 A가 `COMMIT`을 하지 않은 채로 트랜잭션을 계속
   열어두고 있었던 것이었다(`SHOW ENGINE INNODB STATUS`에서 `ACTIVE 984 sec`로 확인됨).
   Binlog는 커밋 시점에 한꺼번에 flush되므로, 커밋 전까지는 아무리 UPDATE를 실행해도 파일에
   반영되지 않는다.
4. 그 세션에서 `COMMIT;`을 실행하자 파일 크기가 `5698` → `7429`로 즉시 증가했다.

### 실제 결과

```
Rows_query: # UPDATE posts SET title = 'binlog readable test' WHERE id = 1
Rows_query: # UPDATE posts SET title = CONCAT('binlog readable test ', NOW()) WHERE id = 1
Xid COMMIT
```

### 확인 결과 정리

`binlog_rows_query_log_events`로 원본 SQL을 확인할 수 있다는 것을 직접 확인했다. 다만 과정에서
두 가지를 새로 알게 됐다: (1) 처음 세운 "값이 안 바뀌면(Changed: 0) binlog에 안 남는다"는
추정은 틀렸다 — 실제로는 `Changed: 0`이었던 UPDATE도 커밋만 되면 이벤트로 기록됐다.
(2) 진짜 원인은 "커밋되지 않은 트랜잭션은 아무리 많은 변경을 담고 있어도 binlog에 전혀
반영되지 않는다"는 것이었다. 오래 열려있는 트랜잭션은 복제(replication)를 그만큼 지연시킬 수 있고, `SHOW ENGINE INNODB STATUS`에서 `ACTIVE` 시간이
비정상적으로 긴 트랜잭션은 항상 의심해봐야 한다는 것을 직접 겪어서 확인했다.

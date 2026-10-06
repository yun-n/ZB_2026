# 4주차 — 백업과 복구: 논리 백업 vs 물리 백업

완료 기준: 백업 → 빈 DB 복구 실습, 결과를 "문제–가설–측정–결론–트레이드오프" 보고서와 60초 답변으로 정리

## 기본 루프

이번 주도 실험 전에 먼저 예측을 세우고, 실행 후 결과와 비교하는 방식으로 진행했다.

## 요약표

| 항목 | 내용 |
| --- | --- |
| 환경 | MySQL 8.4 (Docker Compose, 컨테이너 `zb-mysql`, 데이터는 Docker 볼륨 `mysql-data`), posts 100,002건 / likes 500,000건, Windows + Git Bash |
| 문제 | 장애가 났을 때 백업이 있는 것만으로는 부족하고, 빈 DB로 실제 복구가 되는지와 얼마나 걸리는지를 알아야 한다 |
| 가설 | 논리 백업은 1~3분, 파일은 2KB(seed.sql)보다 작고, 복구는 백업보다 오래 걸릴 것이다. 물리 백업은 논리 백업보다 클 것이다 |
| 변경 | `mysqldump --single-transaction` 논리 백업 → 빈 DB(`blogapi_restore_test`)에 복구, 서비스를 멈추고 볼륨 전체를 복사하는 물리 백업 → 복사본 볼륨으로 임시 MySQL 기동 |
| 측정 | 논리: 백업 1.96초 / 32MB / 복구 7.78초. 물리: 백업 2.42초 / 413.6MB / 복구 약 1.1초(`docker run` 반환 기준). 두 방식 모두 건수 일치 |
| 트레이드오프 | 논리는 작고 이식성이 좋고 서비스 중단이 없지만 복구가 느리다. 물리는 복구가 빠르지만 용량이 13배 크고, 같은 MySQL 버전에 묶이며 서비스를 멈추고 복사해야 한다 |

---

## 실험 1 — 논리 백업 (mysqldump)

### 실험 전 예측

**질문**: 현재 데이터(posts 10만 / likes 50만)를 `mysqldump`로 논리 백업하면 몇 초/몇 분 걸리고, `backup.sql` 파일 크기는 얼마나 될까?

**예측**: "실제로 얼마나 걸렸는지 경험이 없어서 모르겠는데 백업은 1~3분? 파일 크기도 본 적이 없어서 모르겠는데, seed.sql이 2KB인데 이것보다 더 작지 않을까?"
근거는 직접 해본 경험이 없어서 감으로 추정한 것이다.

### 실제 결과

```
$ time docker compose exec mysql mysqldump -uroot -proot --single-transaction blogapi > backup.sql
mysqldump: [Warning] Using a password on the command line interface can be insecure.

real    0m1.962s
user    0m0.030s
sys     0m0.045s

$ ls -lh backup.sql
-rw-r--r-- 1 user 197121 32M 10월  6 22:56 backup.sql
```

### 예측과 비교

- 시간: 1~3분 예측 → 1.96초. 크게 과대평가했다.
- 크기: 2KB 미만 예측 → 32MB. 약 1만 6천 배 과소평가했다. seed.sql은 샘플 몇 줄이지만 mysqldump는 테이블 구조와 실제 데이터 60만 행을 INSERT문 텍스트로 전부 풀어 쓰기 때문에 이 정도 크기가 나온다.
- `--single-transaction`은 InnoDB에서 테이블 잠금 없이 일관된 스냅샷을 뜨기 위한 옵션이라 서비스를 멈추지 않고 백업할 수 있었다.

---

## 실험 2 — 빈 DB에 논리 백업 복구 + 건수 검증

### 실험 전 예측

**질문**: 복구 시간은 백업(1.96초)보다 짧을까, 비슷할까, 길까?

**예측**: "당연히 백업보다 실제가 더 오래 걸리겠지" 이유까지 안 것은 아니고 감이었다.

### 실제 결과

```
$ docker compose exec mysql mysql -uroot -proot -e "CREATE DATABASE blogapi_restore_test;"
$ time docker compose exec -T mysql mysql -uroot -proot blogapi_restore_test < backup.sql
mysql: [Warning] Using a password on the command line interface can be insecure.
mysql: [Warning] Using a password on the command line interface can be insecure.

real    0m7.779s
user    0m0.076s
sys     0m0.076s

$ docker compose exec mysql mysql -uroot -proot -e "
SELECT 'orig posts' AS t, COUNT(*) FROM blogapi.posts
UNION ALL SELECT 'restore posts', COUNT(*) FROM blogapi_restore_test.posts
UNION ALL SELECT 'orig likes', COUNT(*) FROM blogapi.likes
UNION ALL SELECT 'restore likes', COUNT(*) FROM blogapi_restore_test.likes;"
+---------------+----------+
| t             | COUNT(*) |
+---------------+----------+
| orig posts    |   100002 |
| restore posts |   100002 |
| orig likes    |   500000 |
| restore likes |   500000 |
+---------------+----------+
```

### 예측과 비교

- 복구 7.78초로 백업(1.96초)의 약 4배였다. "백업보다 길다"는 방향은 맞았다.
- 이유는 몰랐다. 백업은 읽어서 텍스트로 쏟아내면 되지만, 복구는 INSERT문을 하나씩 파싱·실행하면서 데이터를 쓰고 PK와 인덱스까지 다시 만들어야 한다. 읽기보다 쓰기가 비싸다는 것을 실측 후에 이해했다.
- 건수가 원본과 같아 복구는 성공했다. (posts가 10만이 아니라 100,002건인 것은 시드 데이터 2건이 더해진 것으로 보이며, 원본과 복구본이 같아서 검증에는 문제가 없다.) 건수 일치는 최소한의 검증이다.

---

## 실험 3 — 물리 백업 (데이터 볼륨 통째로 복사)

물리 백업은 DB가 디스크에 저장한 실제 파일(`/var/lib/mysql`)을 그대로 복사하는 방식이다.
데이터가 Docker 이름 있는 볼륨(`mysql-data`)에 있어서, 임시 컨테이너로 볼륨을 다른 볼륨에 복사했다.
파일이 쓰이는 중에 복사되면 불일치가 생길 수 있어서 `docker compose stop`으로 먼저 멈췄다.

### 실험 전 예측

**질문**: 물리 백업 폴더 크기는 논리 백업(32MB)보다 클까, 작을까?

**예측**: 물리가 더 클 것이다. (InnoDB 파일에 인덱스, 시스템 테이블스페이스, 로그가 같이 들어 있을 것이므로)

### 실제 결과

```
$ docker volume ls | grep mysql-data
local     zb_2026_mysql-data

$ docker compose stop
[+] stop 2/2
 ✔ Container zb-app   Stopped       0.0s
 ✔ Container zb-mysql Stopped       1.9s

$ docker volume create mysql-data-physical-backup
$ time MSYS_NO_PATH_CONV=1 docker run --rm -v zb_2026_mysql-data:/from -v mysql-data-physical-backup:/to alpine sh -c "cp -a /from/. /to/"
Unable to find image 'alpine:latest' locally
latest: Pulling from library/alpine
Status: Downloaded newer image for alpine:latest

real    0m9.210s
user    0m0.000s
sys     0m0.000s
```

```
$ docker run --rm -v mysql-data-physical-backup://vol alpine du -sh //vol
413.6M  /vol
```

```
$ docker compose start
[+] start 2/2
 ✔ Container zb-mysql Healthy       5.7s
 ✔ Container zb-app   Started       0.2s
```

이미지를 받은 뒤, 같은 복사를 다시 재서 순수 복사 시간을 확인했다. (이번에는 MySQL이 켜진 상태에서 복사한 시간 측정용이라 복구 검증에는 쓰지 않았고, 측정 후 볼륨은 삭제했다.)

```
$ docker volume create mysql-data-physical-backup2
$ time docker run --rm -v zb_2026_mysql-data:/from -v mysql-data-physical-backup2:/to alpine sh -c "cp -a /from/. /to/"

real    0m2.418s
user    0m0.030s
sys     0m0.045s

$ docker volume rm mysql-data-physical-backup2
```

### 예측과 비교

- 용량: 413.6MB로 논리 백업(32MB)의 약 13배였다. 예측 적중. 논리 백업은 데이터 행만 텍스트로 뽑은 것이고, 물리 백업은 인덱스, 시스템 테이블스페이스, 리두/언두 로그, mysql 시스템 DB까지 전부 들어 있다. 같은 볼륨에 논리 복구 DB(`blogapi_restore_test`)도 들어 있어서 그만큼 더 커졌을 수 있다.
- 시간: 최초 측정 9.21초에는 `alpine` 이미지 다운로드가 섞여 있었고, 순수 복사는 2.42초로 논리 백업(1.96초)과 비슷했다. 이 규모에서는 백업 속도 차이가 거의 없다.
- 서비스 중단: 컨테이너 중지에 1.9초, 재기동에 5.7초가 걸렸다. 논리 백업과 달리 서비스를 멈춰야 했다.

---

## 실험 4 — 물리 백업 복구 검증 (복사본 볼륨으로 임시 MySQL 기동)

### 실제 결과

```
$ time docker run -d --name mysql-physical-test -e MYSQL_ROOT_PASSWORD=root -v mysql-data-physical-backup:/var/lib/mysql -p 3308:3306 mysql:8.4
f72e273bb8dbae81662c6db11216f0df9d2e9e2e8b876f9f6a58292a1e2e628f

real    0m1.126s
user    0m0.045s
sys     0m0.106s

$ docker exec mysql-physical-test mysql -uroot -proot -e "SELECT COUNT(*) FROM blogapi.posts; SELECT COUNT(*) FROM blogapi.likes;"
mysql: [Warning] Using a password on the command line interface can be insecure.
COUNT(*)
100002
COUNT(*)
500000
```

### 확인 결과 정리

- 건수가 원본과 같아(100,002 / 500,000) 물리 백업 복구도 성공했다.
- 복구 1.1초는 `docker run -d`가 반환된 시간이라서, MySQL 내부 기동이 끝나기까지는 몇 초 더 걸렸을 것이다. 그래도 SQL 60만 행을 다시 실행하는 논리 복구(7.78초)와는 방식이 다르다. 물리 복구는 데이터를 다시 쓰지 않고 파일을 제자리에 두고 기동하기 때문에 데이터가 커질수록 격차가 벌어질 것이다.

---

## 시행착오 — Git Bash 경로 변환

물리 백업 용량을 재는 `du` 명령이 두 번 실패했다.

```
$ MSYS_NO_PATH_CONV=1 docker run --rm -v mysql-data-physical-backup:/data alpine du -sh /data
du: C:/Program Files/Git/data: No such file or directory

$ MSYS_NO_PATH_CONV=1 docker run --rm -v mysql-data-physical-backup:/vol alpine du -sh /vol
du: C:/Program Files/Git/vol: No such file or directory
```

- 원인: Git Bash(MSYS)가 `/`로 시작하는 인자를 Windows 경로(`C:/Program Files/Git/...`)로 자동 변환해서 컨테이너 안의 경로가 아니라 호스트 경로가 넘어갔다.
- 처음에는 `/data`가 한 번 더 변환된 것이라고 봤지만, `MSYS_NO_PATH_CONV=1`을 붙여도 같은 결과가 나왔다. 변수가 이 줄에서 효과가 없었다.
- 해결: 슬래시를 두 번 써서(`//vol`) 변환을 피하자 정상적으로 `413.6M`이 나왔다.

---

## 요약 — 예측 vs 실측

| 항목 | 예측 | 실측 | 판정 |
| --- | --- | --- | --- |
| 논리 백업 시간 | 1~3분 | 1.96초 | 과대평가 |
| 논리 백업 크기 | 2KB 미만 | 32MB | 과소평가 |
| 복구 시간 | 백업보다 길 것 | 백업의 약 4배 (7.78초) | 방향은 적중, 이유는 몰랐음 |
| 물리 백업 크기 | 논리보다 클 것 | 413.6MB (13배) | 적중 |

| 항목 | 논리 백업 (mysqldump) | 물리 백업 (볼륨 복사) |
| --- | --- | --- |
| 백업 시간 | 1.96초 | 2.42초 |
| 백업 용량 | 32MB | 413.6MB (약 13배) |
| 복구 시간 | 7.78초 (SQL 재실행) | 약 1.1초 (`docker run` 반환 기준) |
| 서비스 중단 | 없음 (`--single-transaction`) | 있음 (중지 1.9초 + 재기동 5.7초) |
| 건수 검증 | posts 100,002 / likes 500,000 일치 | 동일 값 일치 |

## 결론

1. 논리 백업은 데이터를 INSERT문 텍스트로 풀어 쓰므로 생각보다 크고(32MB), 복구는 SQL을 다시 실행하는 쓰기 작업이라 백업보다 약 4배 느렸다.
2. 이 규모에서는 백업 시간이 두 방식 모두 2초대로 비슷했고, 차이는 복구에서 났다. 논리는 데이터를 다시 쓰고 인덱스를 재구성하지만, 물리는 파일을 제자리에 두고 기동하면 된다.
3. 데이터가 수백 GB가 되면 논리 복구 시간이 RTO(복구 목표 시간)를 넘길 수 있어서, 그 규모에서는 물리 백업(xtrabackup 등)이 현실적인 선택이 된다. 다만 이번 실험은 몇 십 MB 규모라 이 부분은 실측이 아니라 원리에 따른 추론이다.
4. 건수 일치는 최소한의 검증이다. 실무에서는 체크섬이나 샘플 행 비교까지 한다.

## 트레이드오프

| | 논리 백업 | 물리 백업 |
| --- | --- | --- |
| 장점 | 작고 읽기 쉬움, 다른 버전·서버로 이식이 쉬움, 서비스 중단 없이 가능 | 복구가 빠름, 데이터가 커져도 복구 시간 증가가 완만 |
| 단점 | 데이터가 커질수록 복구가 오래 걸림 | 용량이 큼, 같은 MySQL 버전·환경에 묶임, 실행 중 복사하면 불일치 위험(이번엔 중지 후 복사) |
| 어울리는 상황 | 소규모 데이터, 버전 마이그레이션, 일부 테이블만 복구 | 대용량 DB, 복구 목표 시간(RTO)이 짧은 장애 대응 |

## 60초 답변

"4주차에 백업과 복구를 직접 실습했습니다. 10만 건 게시글과 50만 건 좋아요 데이터를 mysqldump로 논리 백업했더니 2초 만에 끝났고 파일은 32MB였습니다. 빈 DB에 복구하니 7.8초가 걸렸고 건수가 원본과 일치했습니다. 복구가 백업보다 4배 느린 이유는 SQL을 다시 실행하면서 데이터를 쓰고 인덱스를 만들기 때문입니다. 비교를 위해 데이터 볼륨을 통째로 복사하는 물리 백업도 해봤는데, 백업 시간은 2.4초로 비슷했지만 용량은 413MB로 13배 컸고, 복구는 SQL 재실행 없이 컨테이너 기동만으로 약 1초 만에 끝났습니다. 대신 물리 백업은 서비스를 멈추고 복사해야 했고 같은 버전 환경에 묶입니다. 그래서 작은 데이터와 이식성이 필요하면 논리, 대용량에서 복구 시간이 중요하면 물리를 고르겠다고 정리했습니다. 처음 예측은 복구가 더 오래 걸린다는 방향만 맞았고 백업 시간과 파일 크기는 크게 빗나갔는데, 감으로 짐작하지 않고 직접 재봐야 한다는 걸 배웠습니다."

## 정리 (실험 후 삭제할 임시 리소스)

```bash
docker rm -f mysql-physical-test
docker volume rm mysql-data-physical-backup
docker exec zb-mysql mysql -uroot -proot -e "DROP DATABASE blogapi_restore_test;"
```

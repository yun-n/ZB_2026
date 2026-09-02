# ZB_2026

제로베이스 백엔드 스쿨 2026년 "경험 기획" 과제용 프로젝트.
게시글(Post) + 좋아요(Like) 도메인 하나로 세 가지 파트를 순서대로 진행합니다.

1. **DB 최적화** — 인덱스 설계, 쿼리 튜닝, 트랜잭션/락, 백업·복구
2. **CI & CD / 배포** — Docker/ECR, VPC, Kubernetes, Redis HA
3. **에러 로그 / 모니터링** — ELK, Prometheus/Grafana, OpenTelemetry, 장애 대응

## 기술 스택

- Java 21 (LTS)
- Spring Boot 3.5.x
- Gradle
- MySQL 8.4
- Flyway (스키마 마이그레이션)

Java 21 + Spring Boot 3.5.x 조합으로 정했습니다. Spring Boot 4.0/4.1(Jakarta EE 11)도 Java 25를 지원하지만
나온 지 얼마 안 돼 마이그레이션 이슈가 종종 보고되고 있어, 학습 목적에는 검증된 3.5.x LTS 조합이 더 안정적입니다.

## 0주차 — 실습 환경 고정

- [x] GitHub 저장소 생성 (이 저장소)
- [x] 스키마 고정: `api/src/main/resources/db/migration/V1__baseline_schema.sql`
  - **의도적으로 조회 최적화 인덱스가 하나도 없는 베이스라인 상태**입니다.
  - `posts.status`, `posts.created_at`, `likes.post_id` 등에 인덱스를 아직 걸지 않았습니다.
  - `likes.post_id`, `posts.user_id`도 FK 제약을 걸지 않아 InnoDB가 자동으로 인덱스를 만들지 않도록 했습니다.
    (FK를 걸면 MySQL이 자동으로 인덱스를 생성해서 "인덱스 없는 상태"라는 베이스라인이 깨지기 때문)
  - 1주차에 인덱스를 추가할 때는 `V2__add_indexes.sql`처럼 새 마이그레이션 파일로 추가해서,
    "V1 커밋(인덱스 전) vs V2 커밋(인덱스 후)"으로 항상 재현 가능하게 비교합니다.
- [x] 시드 데이터 스크립트: `db/seed.sql` (users 1,000 / posts 100,000 / likes 500,000, 기본값 — 스크립트 상단 변수로 규모 조절 가능)
- [x] 재현/초기화 스크립트: `scripts/reset.sh`
- [x] 현재 인덱스 확인 스크립트: `scripts/check_indexes.sql`

## 실행 방법

```bash
# 1. 전체 스택 실행 (app + mysql, 스키마 마이그레이션은 Flyway가 앱 기동 시 자동 적용)
docker compose up -d --build

# 2. 시드 데이터 채우기 (최초 1회, 몇 분 걸릴 수 있습니다)
docker compose exec -T mysql mysql -uroot -proot blogapi < db/seed.sql

# 3. 헬스체크
curl http://localhost:8080/actuator/health

# 4. 현재 인덱스 상태 확인 (0주차 기준 = posts/likes 모두 PK 인덱스만 있어야 정상)
docker compose exec -T mysql mysql -uroot -proot blogapi < scripts/check_indexes.sql
```

## 재현/초기화 (완전히 처음 상태로 리셋)

```bash
./scripts/reset.sh
```

DB 볼륨을 지우고, 컨테이너를 다시 올리고, 스키마 마이그레이션 + 시드 데이터까지 한 번에 재실행합니다.
"몇 번 실행해도 항상 같은 상태"를 보장하기 위한 스크립트입니다.

## 다음 단계

- 1주차(DB 최적화): `posts` 피드 조회 API를 골라 `(status, created_at, id)` 복합 인덱스 적용 전/후 EXPLAIN ANALYZE 비교

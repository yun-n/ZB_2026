#!/usr/bin/env bash
# 완전히 처음 상태로 리셋: 볼륨 삭제 -> 재기동(Flyway 스키마 자동 적용) -> 시드 데이터 재생성
set -euo pipefail

cd "$(dirname "$0")/.."

echo "1) 컨테이너 + 볼륨 정리"
docker compose down -v

echo "2) 재기동 (mysql 준비될 때까지 대기)"
docker compose up -d --build
until docker compose exec -T mysql mysqladmin ping -h localhost -uroot -proot --silent; do
  echo "   mysql 대기 중..."
  sleep 2
done

echo "3) 앱 헬스체크 대기 (Flyway 마이그레이션 적용 시점)"
until curl -sf http://localhost:8080/actuator/health > /dev/null; do
  echo "   app 대기 중..."
  sleep 2
done

echo "4) 시드 데이터 투입"
docker compose exec -T mysql mysql -uroot -proot blogapi < db/seed.sql

echo "5) 인덱스 상태 확인 (0주차 기준: PRIMARY만 있어야 정상)"
docker compose exec -T mysql mysql -uroot -proot blogapi < scripts/check_indexes.sql

echo "완료."

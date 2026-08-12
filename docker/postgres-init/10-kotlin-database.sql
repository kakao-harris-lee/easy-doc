-- Kotlin 런타임용 별도 데이터베이스.
--
-- 절체(Phase 7) 전까지 Python(Alembic)과 Kotlin(Flyway)이 나란히 돈다. 계획 §4.2-6은
-- "한 환경에서 Alembic과 Flyway를 함께 실행하지 않는다"고 요구하므로, 같은 PostgreSQL
-- 인스턴스 안에서 **데이터베이스를 갈라** 두 마이그레이션 도구가 서로의 스키마를 밟지
-- 않게 한다. Python 은 계속 `easydoc` 을, Kotlin 은 `easydoc_kotlin` 을 쓴다.
--
-- 절체 시점에 Kotlin 의 접속 대상을 `easydoc` 으로 바꾸면, 그 DB에는 Flyway 이력이 없고
-- Alembic 이 만든 스키마만 있으므로 FlywayBaselineGuard 가 지문을 대조해 baseline 을
-- 기록한다. 그 경로는 ApiStartupOnPythonSnapshotTest 가 이미 검증한다.
--
-- **주의**: PostgreSQL 공식 이미지의 initdb 스크립트는 **데이터 디렉터리가 빈 경우에만**
-- 실행된다. 이미 볼륨이 있는 개발 환경에서는 이 파일이 돌지 않으므로 직접 만들어야 한다.
--     docker compose exec postgres createdb -U postgres easydoc_kotlin
-- 또는 볼륨을 비우고 다시 올린다: docker compose down -v && docker compose up -d

CREATE DATABASE easydoc_kotlin;

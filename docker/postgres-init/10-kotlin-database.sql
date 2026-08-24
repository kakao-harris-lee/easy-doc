-- Kotlin 런타임용 별도 데이터베이스. 같은 PostgreSQL 인스턴스 안에서 기본 `easydoc`
-- 데이터베이스와 분리해 둔다.
--
-- **주의**: PostgreSQL 공식 이미지의 initdb 스크립트는 **데이터 디렉터리가 빈 경우에만**
-- 실행된다. 이미 볼륨이 있는 개발 환경에서는 이 파일이 돌지 않으므로 직접 만들어야 한다.
--     docker compose exec postgres createdb -U postgres easydoc_kotlin
-- 또는 볼륨을 비우고 다시 올린다: docker compose down -v && docker compose up -d

CREATE DATABASE easydoc_kotlin;

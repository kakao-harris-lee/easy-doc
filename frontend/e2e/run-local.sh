#!/usr/bin/env bash
# Phase 3 브라우저 E2E 로컬 러너 — CI 잡 `e2e` 와 **같은 절차**를 한 명령으로 재현한다.
#
#   frontend/e2e/run-local.sh              # 전체
#   frontend/e2e/run-local.sh --grep E11   # 인자는 그대로 playwright 로 넘어간다
#
# 세우는 것: 일회용 PostgreSQL 컨테이너 → Flyway 마이그레이션 → Kotlin API(bootJar) →
# Kotlin worker(bootJar, fake LLM) → Vite dev 서버(Playwright 의 webServer 가 띄운다) → Playwright.
#
# ## 게이트 러너 규약
#
# 종료 코드를 삼키지 않는다. 파이프로 잇지 않고(`| tee` 는 파이프라인 마지막 명령의
# 코드를 내놓는다), Playwright 의 코드를 변수에 받아 그대로 `exit` 한다.
# `set -euo pipefail` 을 걸고, 정리는 `trap` 이 맡아 실패 경로에서도 컨테이너가 남지 않는다.
#
# 기본 모드는 빠른 로컬 진단을 위해 API·worker·DB를 직접 띄운다. CI와 전체 프로젝트 검증은
# `compose.yml` + `compose.e2e.yml` 로 스택을 먼저 띄운 뒤 아래처럼 브라우저 단계만 사용한다.
#   E2E_SKIP_STACK=1 E2E_API_BASE_URL=http://localhost:8100 frontend/e2e/run-local.sh

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
frontend_dir="$(dirname "$here")"
repo_root="$(dirname "$frontend_dir")"

# --- 설정 --------------------------------------------------------------------
PG_PORT="${E2E_PG_PORT:-55432}"
PG_CONTAINER="${E2E_PG_CONTAINER:-easydoc-e2e-pg}"
PG_IMAGE="${E2E_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_DB="${E2E_PG_DB:-easydoc}"
API_PORT="${E2E_API_PORT:-8100}"
API_BASE_URL="${E2E_API_BASE_URL:-http://localhost:${API_PORT}}"
# 브라우저 출처. 계약 `x-cors.allow_origins` 의 값과 같아야 프리플라이트가 통과한다.
FRONTEND_ORIGIN="${E2E_FRONTEND_ORIGIN:-http://localhost:5173}"
LOG_DIR="${E2E_LOG_DIR:-${frontend_dir}/test-results}"
API_JAR="${repo_root}/backend-kotlin/api/build/libs/easy-doc-api.jar"
WORKER_JAR="${repo_root}/backend-kotlin/worker/build/libs/easy-doc-worker.jar"

# 스택을 이미 띄워 두었으면(compose 등) 이 스크립트는 Playwright 만 돌린다.
SKIP_STACK="${E2E_SKIP_STACK:-0}"
SKIP_BUILD="${E2E_SKIP_BUILD:-0}"

api_pid=""
worker_pid=""
# 저장 암호화 키가 잠깐 머무는 파일. 읽고 곧바로 지우지만, 실패 경로에서도 남지 않게
# 정리 대상에 넣는다 — 비밀이 디스크에 남는 시간은 짧을수록 좋다.
key_env_file=""

cleanup() {
  local status=$?
  if [ -n "$worker_pid" ] && kill -0 "$worker_pid" 2>/dev/null; then
    kill "$worker_pid" 2>/dev/null || true
    wait "$worker_pid" 2>/dev/null || true
  fi
  if [ -n "$api_pid" ] && kill -0 "$api_pid" 2>/dev/null; then
    kill "$api_pid" 2>/dev/null || true
    wait "$api_pid" 2>/dev/null || true
  fi
  if [ -n "$key_env_file" ]; then
    rm -f "$key_env_file"
  fi
  if [ "$SKIP_STACK" != "1" ]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  return $status
}
trap cleanup EXIT

log() { printf '[e2e] %s\n' "$*"; }

if [ "$SKIP_STACK" = "1" ]; then
  log "스택 기동을 건너뛴다 (E2E_SKIP_STACK=1). API=${API_BASE_URL}"
else
  # --- ① 일회용 PostgreSQL ---------------------------------------------------
  # Testcontainers 를 쓰지 않는다 — 그것은 Kotlin 테스트 프로세스의 수명에 묶여 있고,
  # 여기서는 별도 프로세스로 뜨는 API 가 붙을 DB 가 필요하다.
  log "PostgreSQL 컨테이너 기동 (${PG_IMAGE}, 127.0.0.1:${PG_PORT})"
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$PG_CONTAINER" \
    -e POSTGRES_DB="$PG_DB" \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    -p "127.0.0.1:${PG_PORT}:5432" \
    "$PG_IMAGE" >/dev/null

  ready=0
  for _ in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" pg_isready -h 127.0.0.1 -p 5432 -U postgres -d "$PG_DB" \
      >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  if [ "$ready" -ne 1 ]; then
    echo "::error::PostgreSQL 이 60초 안에 준비되지 않았다." >&2
    docker logs "$PG_CONTAINER" >&2 || true
    exit 1
  fi
  log "PostgreSQL 준비 완료"

  # --- ② bootJar -------------------------------------------------------------
  if [ "$SKIP_BUILD" = "1" ] && [ -f "$API_JAR" ] && [ -f "$WORKER_JAR" ]; then
    log "bootJar 빌드를 건너뛴다 (E2E_SKIP_BUILD=1)"
  else
    log "Kotlin API·worker bootJar 빌드"
    (cd "${repo_root}/backend-kotlin" && ./gradlew :api:bootJar :worker:bootJar --no-daemon -q)
  fi
  if [ ! -f "$API_JAR" ]; then
    echo "::error::${API_JAR} 이 없다 — bootJar 가 만들어지지 않았다." >&2
    exit 1
  fi
  if [ ! -f "$WORKER_JAR" ]; then
    echo "::error::${WORKER_JAR} 이 없다 — worker bootJar 가 만들어지지 않았다." >&2
    exit 1
  fi

  # --- ③ 기동 환경 ------------------------------------------------------------
  # 비밀은 **매 실행 새로 만든다.** 저장소에 고정 값을 적으면 그것이 곧 커밋된 비밀이 되고,
  # 테스트용이라는 사실은 파일을 읽는 사람에게만 보인다. 계약 `x-auth.min_secret_bytes`
  # 가 32바이트를 요구하므로 hex 64자를 만든다.
  jwt_secret="$(openssl rand -hex 32)"

  export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
  export SPRING_DATASOURCE_USERNAME=postgres
  export SPRING_DATASOURCE_PASSWORD=postgres
  export SERVER_PORT="$API_PORT"
  export EASYDOC_AUTH_JWT_SECRET="$jwt_secret"

  # 저장 암호화 키도 매 실행 새로 만든다 (게이트 28 C-3). `CryptoConfiguration` 의 기동
  # 자기점검이 키와 **검사값(KCV)** 을 함께 요구해서, 둘 중 하나만 있어도 API 가 뜨지 않는다.
  # KCV 는 AES-256-GCM 인증 태그라 openssl 로 계산할 수 없고, 계산을 여기 옮겨 적으면
  # 저장소에 KCV 계산이 둘이 된다. 그래서 **제품 코드를 실행한다** — CI `e2e` 잡이 부르는
  # 것과 **같은 Gradle 태스크**이고, 그것이 제품 `KeyCheckValue.of` 로 검사값을 구한다.
  log "저장 암호화 키 생성 (제품 KeyCheckValue 로 검사값 계산)"
  key_env_file="$(mktemp)"
  (cd "${repo_root}/backend-kotlin" \
    && ./gradlew --no-daemon -q :infrastructure:writeEncryptionKeyEnv \
      "-Peasydoc.encryptionEnvOut=${key_env_file}")
  set -a
  # shellcheck disable=SC1090 -- 실행 시점에 만들어지는 임시 파일이라 경로가 고정이 아니다.
  . "$key_env_file"
  set +a
  rm -f "$key_env_file"
  key_env_file=""

  mkdir -p "$LOG_DIR"

  # --- ④ API 기동 (Flyway 는 api 기동 시 자동 적용) ------------------------------
  log "Kotlin API 기동 (profile=api, ${API_BASE_URL})"
  java -jar "$API_JAR" --spring.profiles.active=api >"${LOG_DIR}/backend-api.log" 2>&1 &
  api_pid=$!

  healthy=0
  for _ in $(seq 1 60); do
    if curl --fail --silent --output /dev/null "${API_BASE_URL}/health"; then
      healthy=1
      break
    fi
    if ! kill -0 "$api_pid" 2>/dev/null; then
      echo "::error::Kotlin API 프로세스가 죽었다. ${LOG_DIR}/backend-api.log 를 보라." >&2
      exit 1
    fi
    sleep 1
  done
  if [ "$healthy" -ne 1 ]; then
    echo "::error::Kotlin API 가 60초 안에 /health 200 을 내지 않았다." >&2
    exit 1
  fi
  log "Kotlin API 준비 완료"

  # --- ⑤ worker 기동 ----------------------------------------------------------
  # E13 수직 흐름은 lease 큐를 소비하는 프로세스가 있어야 한다. fake LLM 은 local
  # 프로필에서만 조립되므로 worker,local 을 켠다.
  export EASYDOC_LLM_PROVIDER=fake
  log "Kotlin worker 기동 (profile=worker,local, fake LLM)"
  java -jar "$WORKER_JAR" --spring.profiles.active=worker,local >"${LOG_DIR}/backend-worker.log" 2>&1 &
  worker_pid=$!
  sleep 2
  if ! kill -0 "$worker_pid" 2>/dev/null; then
    echo "::error::Kotlin worker 프로세스가 죽었다. ${LOG_DIR}/backend-worker.log 를 보라." >&2
    exit 1
  fi
  log "Kotlin worker 준비 완료"
fi

# --- ⑥ Playwright -------------------------------------------------------------
# Vite dev 서버는 Playwright 의 `webServer` 가 띄운다 — 설정이 한 곳에 있어야
# CI 와 로컬이 같은 출처·같은 `VITE_API_BASE_URL` 로 돈다.
export E2E_API_BASE_URL="$API_BASE_URL"
export E2E_FRONTEND_ORIGIN="$FRONTEND_ORIGIN"

log "Playwright 실행"
status=0
(cd "$frontend_dir" && npx playwright test "$@") || status=$?

if [ "$status" -ne 0 ]; then
  log "실패 (종료 코드 ${status}). 보고서: ${frontend_dir}/playwright-report"
else
  log "통과"
fi
exit "$status"

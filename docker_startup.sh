#!/usr/bin/env bash
# easy-doc Compose 기동 스크립트.
#
#   ./docker_startup.sh            # 빌드 후 기동(--build --wait), 상태·주소 출력
#   ./docker_startup.sh logs       # backend-api·backend-worker 로그 추적
#   ./docker_startup.sh status     # 컨테이너 상태
#   ./docker_startup.sh restart    # 재빌드 후 재기동
#   ./docker_startup.sh down       # 중지 (DB 데이터 유지)
#   ./docker_startup.sh reset      # 중지 + DB 볼륨 삭제(확인 질문 있음)
#
# EASYDOC_DEPLOYMENT_MODE=pilot이면 compose.pilot.yml을 함께 적용한다. mode가 auto이거나
# 비어 있으면 EASYDOC_APP_PUBLIC_BASE_URL이 localhost인지 보고 local/pilot을 선택한다.
# 전제: Docker 실행 중, 저장소 루트에 .env 존재(.env.example 참고).
# 비밀값은 .env 에만 둔다 — 이 스크립트는 값을 출력하지 않는다.
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE=(docker compose -f compose.yml)

dotenv_value() {
  local key="$1"
  local value

  [ -f .env ] || return 0
  value="$(sed -n "s/^${key}=//p" .env | tail -n 1)"
  value="${value%$'\r'}"
  if [[ "${value}" == \"*\" ]] || [[ "${value}" == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "${value}"
}

PUBLIC_BASE_URL="${EASYDOC_APP_PUBLIC_BASE_URL:-$(dotenv_value EASYDOC_APP_PUBLIC_BASE_URL)}"
DEPLOYMENT_MODE="${EASYDOC_DEPLOYMENT_MODE:-$(dotenv_value EASYDOC_DEPLOYMENT_MODE)}"
DEPLOYMENT_MODE="${DEPLOYMENT_MODE:-auto}"

if [[ "${DEPLOYMENT_MODE}" == "auto" ]]; then
  case "${PUBLIC_BASE_URL}" in
    ""|http://localhost*|https://localhost*|http://127.0.0.1*|https://127.0.0.1*)
      DEPLOYMENT_MODE=local
      ;;
    *)
      DEPLOYMENT_MODE=pilot
      ;;
  esac
fi

case "${DEPLOYMENT_MODE}" in
  local)
    ;;
  pilot)
    [ -f compose.pilot.yml ] || { echo "compose.pilot.yml이 없습니다." >&2; exit 1; }
    COMPOSE+=(-f compose.pilot.yml)
    ;;
  *)
    echo "EASYDOC_DEPLOYMENT_MODE는 auto, local, pilot 중 하나여야 합니다." >&2
    exit 1
    ;;
esac

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "필요한 명령이 없습니다: $1" >&2; exit 1; }
}

preflight() {
  require docker
  docker info >/dev/null 2>&1 || { echo "Docker 데몬에 연결할 수 없습니다. Docker Desktop 을 먼저 실행하세요." >&2; exit 1; }
  [ -f .env ] || { echo ".env 가 없습니다. .env.example 을 복사해 값을 채우세요." >&2; exit 1; }
  # 누락·오타를 기동 전에 잡는다(값은 출력하지 않는다).
  "${COMPOSE[@]}" config -q
  echo "Compose 실행 모드: ${DEPLOYMENT_MODE}"
}

print_endpoints() {
  if [[ "${DEPLOYMENT_MODE}" == "pilot" ]]; then
    cat <<EOF

기동 완료 (pilot).
  프런트          ${PUBLIC_BASE_URL:-EASYDOC_APP_PUBLIC_BASE_URL을 설정하세요}
  API 헬스체크    ${PUBLIC_BASE_URL:-EASYDOC_APP_PUBLIC_BASE_URL을 설정하세요}/api/health
  PostgreSQL      Docker 내부 네트워크 전용 (호스트 포트 없음)

로그: ./docker_startup.sh logs   중지: ./docker_startup.sh down
EOF
    return
  fi

  cat <<'EOF'

기동 완료 (local).
  프런트          http://localhost:8080
  API 헬스체크    http://localhost:8100/health
  PostgreSQL      127.0.0.1:5432 (호스트 전용 바인딩)

구글 로그인 콜백은 프런트 주소 기준 http://localhost:8080/auth/google/callback 입니다.
Google Cloud Console 의 redirect URI 와 .env 의 EASYDOC_OAUTH_GOOGLE_REDIRECT_URIS 에
그 주소가 있어야 합니다(Vite 개발 서버로 띄우면 5173).
로그: ./docker_startup.sh logs   중지: ./docker_startup.sh down
EOF
}

case "${1:-up}" in
  up)
    preflight
    # --build 를 빼지 않는다: 코드를 고친 뒤 up 만 하면 옛 이미지가 그대로 뜬다(README).
    "${COMPOSE[@]}" up -d --build --wait
    "${COMPOSE[@]}" ps
    print_endpoints
    ;;
  restart)
    preflight
    "${COMPOSE[@]}" down
    "${COMPOSE[@]}" up -d --build --wait
    "${COMPOSE[@]}" ps
    print_endpoints
    ;;
  logs)
    "${COMPOSE[@]}" logs -f backend-api backend-worker
    ;;
  status)
    "${COMPOSE[@]}" ps
    ;;
  down)
    "${COMPOSE[@]}" down
    ;;
  reset)
    read -r -p "DB 볼륨(easy-doc_postgres_data)까지 삭제합니다. 계속할까요? [y/N] " answer
    [[ "${answer}" =~ ^[Yy]$ ]] || { echo "취소했습니다."; exit 0; }
    "${COMPOSE[@]}" down -v
    ;;
  *)
    echo "사용법: $0 [up|restart|logs|status|down|reset]" >&2
    exit 2
    ;;
esac

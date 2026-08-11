#!/usr/bin/env bash
# codex 독립 리뷰 실행기.
#
# codex 플러그인의 codex-companion.mjs 헬퍼를 "동적으로" 찾아 호출한다.
# 헬퍼 경로에는 플러그인 버전(예: 1.0.6)이 들어가므로 하드코딩하면
# 플러그인이 올라가는 순간 스킬 전체가 조용히 깨진다. 그래서 매 실행마다 탐색한다.
#
# 탐색 순서: $CODEX_COMPANION → 플러그인 cache 최신 버전 → marketplaces → codex CLI 직접 호출
#
# 사용법: scripts/codex-review.sh <review|adversarial> [옵션] [focus text...]
set -euo pipefail

CLAUDE_HOME="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
CACHE_ROOT="$CLAUDE_HOME/plugins/cache/openai-codex/codex"
MARKET_HELPER="$CLAUDE_HOME/plugins/marketplaces/openai-codex/plugins/codex/scripts/codex-companion.mjs"

usage() {
  cat <<'USAGE'
사용법: codex-review.sh <review|adversarial> [옵션] [focus text...]

모드
  review        codex 내장 리뷰어. focus text를 받지 않는다(헬퍼가 거부함).
  adversarial   focus text를 붙일 수 있는 적대적 리뷰. parity·보안·계약 위험에 쓴다.

옵션
  --base <ref>       지정하면 scope와 무관하게 <ref> 대비 branch diff를 리뷰한다.
  --scope <auto|working-tree|branch>
                     auto(기본): 작업 트리가 더러우면 working-tree, 깨끗하면 기본 브랜치 대비 branch.
  --focus "<text>"   adversarial 모드의 focus text. 인자 뒤에 그냥 붙여 써도 된다.
  --json             헬퍼 출력을 JSON으로 받는다.
  --dry-run          실행하지 않고 탐색 결과와 실행할 명령만 출력한다.
  -h, --help         이 도움말.

예시
  codex-review.sh review --scope working-tree
  codex-review.sh adversarial --base main "Fernet 암호문 호환과 마스킹 선행을 집중 검증하라"
  codex-review.sh adversarial --dry-run --scope branch --focus "소유권 404 은닉 위반을 찾아라"

주의: 리뷰는 동기 실행이다. 헬퍼의 --wait/--background는 review 계열에서 파싱만 되고
      사용되지 않으므로(codex-companion.mjs handleReviewCommand 확인), 이 스크립트도 전달하지 않는다.
      Claude Code에서 호출할 때는 Bash 도구 timeout을 넉넉히(600000ms) 주거나
      run_in_background로 띄워라.
USAGE
}

die() {
  printf '%s\n' "$*" >&2
  exit 2
}

MODE=""
BASE=""
SCOPE=""
FOCUS=""
JSON=0
DRY_RUN=0

append_focus() {
  if [ -z "$FOCUS" ]; then
    FOCUS="$1"
  else
    FOCUS="$FOCUS $1"
  fi
}

need_value() {
  # $1: 옵션 이름, $2: 남은 인자 개수
  if [ "$2" -lt 2 ]; then
    die "오류: $1 옵션에 값이 없다."
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    review)
      [ -z "$MODE" ] && MODE="review" || append_focus "$1"
      shift
      ;;
    adversarial|adversarial-review)
      [ -z "$MODE" ] && MODE="adversarial-review" || append_focus "$1"
      shift
      ;;
    --base)
      need_value "--base" "$#"
      BASE="$2"
      shift 2
      ;;
    --scope)
      need_value "--scope" "$#"
      SCOPE="$2"
      shift 2
      ;;
    --focus)
      need_value "--focus" "$#"
      append_focus "$2"
      shift 2
      ;;
    --json)
      JSON=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      while [ $# -gt 0 ]; do
        append_focus "$1"
        shift
      done
      ;;
    -*)
      die "오류: 모르는 옵션 '$1'. --help로 지원 옵션을 확인하라. (헬퍼에 없는 플래그는 지어내지 않는다.)"
      ;;
    *)
      append_focus "$1"
      shift
      ;;
  esac
done

if [ -z "$MODE" ]; then
  usage >&2
  die "오류: 리뷰 모드(review | adversarial)를 첫 인자로 지정하라."
fi

case "${SCOPE:-auto}" in
  auto|working-tree|branch) ;;
  *) die "오류: --scope는 auto | working-tree | branch 중 하나여야 한다 (받은 값: '$SCOPE')." ;;
esac

# review 모드는 codex 내장 리뷰어에 그대로 매핑되며 헬퍼가 focus text를 거부한다.
# 헬퍼까지 갔다가 실패하면 job만 남고 시간을 버리므로 여기서 먼저 막는다.
if [ "$MODE" = "review" ] && [ -n "$FOCUS" ]; then
  die "오류: review 모드는 focus text를 받지 않는다(헬퍼가 거부). focus가 필요하면 adversarial 모드를 써라."
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
  die "오류: git 저장소 안에서 실행해야 한다. codex 리뷰는 git diff를 입력으로 쓴다."
fi

find_helper() {
  if [ -n "${CODEX_COMPANION:-}" ] && [ -r "${CODEX_COMPANION}" ]; then
    printf '%s\n' "$CODEX_COMPANION"
    return 0
  fi

  if [ -d "$CACHE_ROOT" ]; then
    local version candidate
    # 버전 디렉터리를 내림차순으로 훑어 실제로 스크립트가 있는 최신 버전을 고른다.
    while IFS= read -r version; do
      [ -n "$version" ] || continue
      candidate="$CACHE_ROOT/$version/scripts/codex-companion.mjs"
      if [ -r "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done <<EOF
$(ls -1 "$CACHE_ROOT" 2>/dev/null | sort -Vr)
EOF
  fi

  if [ -r "$MARKET_HELPER" ]; then
    printf '%s\n' "$MARKET_HELPER"
    return 0
  fi

  return 1
}

HELPER="$(find_helper || true)"

SOURCE=""
if [ -n "$HELPER" ]; then
  case "$HELPER" in
    "${CODEX_COMPANION:-__none__}") SOURCE="CODEX_COMPANION 환경변수" ;;
    "$MARKET_HELPER") SOURCE="marketplaces 폴백" ;;
    *) SOURCE="plugins cache (최신 버전 자동 선택)" ;;
  esac
fi

# 실행할 명령을 배열로 조립한다(focus text의 공백·따옴표 보존).
CMD=()
if [ -n "$HELPER" ]; then
  command -v node >/dev/null 2>&1 || die "오류: node를 찾을 수 없다. 헬퍼는 node로 실행된다."
  CMD=(node "$HELPER" "$MODE")
  if [ -n "$BASE" ]; then
    CMD+=(--base "$BASE")
  fi
  if [ -n "$SCOPE" ]; then
    CMD+=(--scope "$SCOPE")
  fi
  if [ "$JSON" -eq 1 ]; then
    CMD+=(--json)
  fi
  if [ -n "$FOCUS" ]; then
    CMD+=("$FOCUS")
  fi
elif command -v codex >/dev/null 2>&1; then
  # 헬퍼 부재 폴백: codex CLI의 review 서브커맨드를 직접 쓴다.
  # (codex review [PROMPT] --uncommitted | --base <BRANCH> — codex-cli 0.147.0에서 확인)
  SOURCE="codex CLI 직접 호출 (헬퍼 부재 폴백)"
  CMD=(codex review)
  if [ -n "$FOCUS" ]; then
    CMD+=("$FOCUS")
  fi
  if [ -n "$BASE" ]; then
    CMD+=(--base "$BASE")
  elif [ "${SCOPE:-auto}" = "branch" ]; then
    die "오류: 헬퍼 없이 codex CLI로 branch 리뷰를 하려면 --base <ref>를 명시하라. CLI에는 자동 기본 브랜치 탐지가 없다."
  else
    CMD+=(--uncommitted)
  fi
else
  cat >&2 <<MISSING
오류: codex 리뷰를 실행할 수단을 찾지 못했다.

확인할 것:
  1. 헬퍼 존재 여부
       ls $CACHE_ROOT/*/scripts/codex-companion.mjs
       ls $MARKET_HELPER
  2. 위 경로가 비어 있으면 Claude Code에서 codex 플러그인을 설치·갱신하라
       (/plugin 으로 openai-codex 마켓플레이스의 codex 플러그인 설치)
  3. 헬퍼가 비표준 위치에 있으면 환경변수로 직접 지정하라
       CODEX_COMPANION=/path/to/codex-companion.mjs $0 $MODE ...
  4. codex CLI 자체 확인
       command -v codex && codex --version
       미설치면 npm install -g @openai/codex, 그 뒤 codex login

이 실패는 조용히 넘기지 마라. codex 리뷰가 없으면 교차 대조 게이트가 성립하지 않으므로,
산출물에 "codex 리뷰 누락(사유: 헬퍼 미발견)"을 반드시 명시해야 한다.
MISSING
  exit 3
fi

printf 'codex-review: 헬퍼 = %s\n' "${HELPER:-(없음 — codex CLI 폴백)}" >&2
printf 'codex-review: 출처 = %s\n' "$SOURCE" >&2
printf 'codex-review: 모드 = %s / scope = %s / base = %s\n' \
  "$MODE" "${SCOPE:-auto(미지정)}" "${BASE:-(미지정)}" >&2
if [ -n "$FOCUS" ]; then
  printf 'codex-review: focus = %s\n' "$FOCUS" >&2
fi

# bash의 %q는 한글을 $'\xxx' 형태로 뭉개서 사람이 읽을 수 없게 만든다.
# 그래서 안전한 문자로만 이뤄진 인자는 그대로, 나머지는 작은따옴표로 감싼다.
shell_quote() {
  case "$1" in
    *[!A-Za-z0-9_./:=@-]*) printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")" ;;
    "") printf "''" ;;
    *) printf '%s' "$1" ;;
  esac
}

printf 'codex-review: 실행 명령 =' >&2
for arg in "${CMD[@]}"; do
  printf ' ' >&2
  shell_quote "$arg" >&2
done
printf '\n' >&2

if [ "$DRY_RUN" -eq 1 ]; then
  printf 'codex-review: --dry-run 이므로 실행하지 않는다.\n' >&2
  exit 0
fi

cd "$REPO_ROOT"
exec "${CMD[@]}"

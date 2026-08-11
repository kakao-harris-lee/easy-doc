#!/usr/bin/env bash
# codex 독립 리뷰 실행기.
#
# codex 플러그인의 codex-companion.mjs 헬퍼를 "동적으로" 찾아 호출한다.
# 헬퍼 경로에는 플러그인 버전(예: 1.0.6)이 들어가므로 하드코딩하면
# 플러그인이 올라가는 순간 스킬 전체가 조용히 깨진다. 그래서 매 실행마다 탐색한다.
#
# 탐색 순서: $CODEX_COMPANION → 플러그인 cache 최신 버전 → marketplaces → codex CLI 직접 호출
#
# 이 스크립트는 모든 Phase의 모든 리뷰 게이트가 지나는 단일 입구다. 그래서 "리뷰가 돌았다"와
# "돌지 않았다"를 종료 코드로 구분하지 못하면 이후 어떤 통과 기록도 신뢰할 수 없다.
# 다음 여섯 가지를 강제한다:
#   1. 헬퍼를 "읽을 수 있다"는 이유로 신뢰하지 않는다. 실행 전에 handshake
#      (node <헬퍼> --help)로 실제 동작하는 리뷰 도구인지 확인한다.
#   2. 자식을 exec하지 않는다. 출력을 캡처해 **공백이 아닌 내용이 있을 때만** 0으로 끝낸다.
#      빈 출력 + exit 0 은 "지적 없음"이 아니라 "리뷰가 돌지 않았다"이다. 이 구분이
#      무너지면 빈 리뷰 파일이 통과 기록으로 남는다.
#   3. --dry-run 은 성공(0)과 **다른** 코드로 끝낸다. 실행하지 않았기 때문이다.
#   4. focus 없는 adversarial 을 거부한다(스킬이 adversarial에 focus를 필수로 규정한다).
#   5. **리뷰 대상이 0건이면 헬퍼를 부르기 전에 거부한다.** 2번만으로는 부족하다 —
#      "출력이 비어 있지 않다"가 "실제로 무언가를 리뷰했다"를 보장하지 못하기 때문이다.
#      빈 diff를 주면 codex는 실패하지 않고 "빈 diff로 확인되어 지적할 것이 없다"를
#      1KB 넘게 써서 verdict=approve로 끝낸다(실측: `adversarial --base HEAD` → exit 0,
#      1,164바이트). --base를 잘못 주거나(--base HEAD), 커밋한 뒤 --scope working-tree로
#      돌리거나, 브랜치를 착각하면 **리뷰 대상 0줄인 채 게이트 통과가 기록된다.**
#      그래서 대상 크기를 먼저 보고, 0이면 리뷰 비용(수 분 + API)을 태우기 전에 끝낸다.
#   6. 5번의 **보조**로, 결과에 "리뷰 대상이 없었다"는 신호가 있으면 성공으로 기록하지 않는다.
#      5번의 판정이 헬퍼와 100% 일치할 수는 없기 때문에 두는 사후 확인이며, 단독
#      방어선이 아니다(한계는 아래 check_no_target_signal 주석 참고).
#
# 종료 코드
#   0      리뷰가 실제로 돌았고 비어 있지 않은 출력을 남겼다 — 이때만 "리뷰를 돌렸다"이다
#   2      사용법 오류
#   3      리뷰를 실행할 수단이 없다 (헬퍼·codex CLI 모두 부재)
#   4      handshake 실패 — 파일은 있으나 동작하는 리뷰 도구가 아니다
#   5      리뷰 도구가 0으로 끝났는데 출력이 비었다 (= 리뷰가 돌지 않았다)
#   6      --dry-run (실행하지 않음)
#   7      리뷰 대상이 비어 있다 (변경 0건). 실행 전 판정(5)이거나 결과 신호(6)다.
#          **0이 아닌 이유**: 리뷰어가 아무것도 보지 않았으므로 "검토했다"는 근거가 되지
#          않는다. 0으로 돌려주면 잘못된 scope·base로 돌린 빈 리뷰가 게이트 통과 기록으로
#          남고, 그건 이 스크립트가 막으려는 실패(검증하지 않고 통과) 그 자체다.
#          5(출력 빔)와 구분하는 이유: 5는 "도구가 고장 났다", 7은 "도구는 멀쩡한데
#          내가 잘못된 대상을 줬다"이고 조치가 다르다(전자는 헬퍼·인증 점검, 후자는
#          --base/--scope 재선택).
#   그 외  리뷰 도구 자신의 종료 코드를 그대로 전파한다. 값이 위 코드와 겹칠 수는 있으나
#          어느 쪽이든 비-0 = "리뷰 근거 없음"이므로 게이트 판정은 달라지지 않는다.
#
# 사용법: scripts/codex-review.sh <review|adversarial> [옵션] [focus text...]
set -euo pipefail

CLAUDE_HOME="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
CACHE_ROOT="$CLAUDE_HOME/plugins/cache/openai-codex/codex"
MARKET_HELPER="$CLAUDE_HOME/plugins/marketplaces/openai-codex/plugins/codex/scripts/codex-companion.mjs"

# 성공(0)과 "실행하지 않음"·"출력 없음"·"도구 아님"을 같은 값으로 내보내지 않는다.
readonly EXIT_USAGE=2
readonly EXIT_NO_TOOL=3
readonly EXIT_HANDSHAKE=4
readonly EXIT_EMPTY_OUTPUT=5
readonly EXIT_DRY_RUN=6
readonly EXIT_EMPTY_TARGET=7

usage() {
  cat <<'USAGE'
사용법: codex-review.sh <review|adversarial> [옵션] [focus text...]

모드
  review        codex 내장 리뷰어. focus text를 받지 않는다(헬퍼가 거부함).
  adversarial   적대적 리뷰. focus text가 **필수**다 — 무엇을 파고들지 지정해야 한다.

옵션
  --base <ref>       지정하면 scope와 무관하게 <ref> 대비 branch diff를 리뷰한다.
  --scope <auto|working-tree|branch>
                     auto(기본): 작업 트리가 더러우면 working-tree, 깨끗하면 기본 브랜치 대비 branch.
  --focus "<text>"   adversarial 모드의 focus text. 인자 뒤에 그냥 붙여 써도 된다.
  --json             헬퍼 출력을 JSON으로 받는다.
  --dry-run          실행하지 않고 탐색 결과와 실행할 명령만 출력한다(종료 코드 6).
  -h, --help         이 도움말.

예시
  codex-review.sh review --scope working-tree
  codex-review.sh adversarial --base main "Fernet 암호문 호환과 마스킹 선행을 집중 검증하라"
  codex-review.sh adversarial --dry-run --scope branch --focus "소유권 404 은닉 위반을 찾아라"

종료 코드
  0  리뷰가 돌았고 출력이 비어 있지 않다 (이때만 리뷰를 돌린 것이다)
  2  사용법 오류   3  리뷰 수단 부재   4  handshake 실패
  5  출력이 빔(= 리뷰 미실행)   6  --dry-run
  7  리뷰 대상이 비어 있다(변경 0건) — 헬퍼를 부르지 않고 끝낸다. 0이 아닌 이유는
     아무것도 보지 않은 리뷰가 "게이트 통과"로 기록되면 안 되기 때문이다.
  그 외  리뷰 도구의 종료 코드 전파

대상 검사: 리뷰 대상(diff) 크기 검사는 헬퍼 탐색·--dry-run보다 **먼저** 돈다.
      따라서 대상이 비어 있으면 --dry-run 이어도 6이 아니라 7로 끝난다 — scope를
      확인하려고 --dry-run을 돌린 것이라면 그게 바로 알아야 할 답이기 때문이다.

주의: 리뷰는 동기 실행이다. 헬퍼의 --wait/--background는 review 계열에서 파싱만 되고
      사용되지 않으므로(codex-companion.mjs handleReviewCommand 확인), 이 스크립트도 전달하지 않는다.
      Claude Code에서 호출할 때는 Bash 도구 timeout을 넉넉히(600000ms) 주거나
      run_in_background로 띄워라.
USAGE
}

die_with() {
  # $1: 종료 코드, 나머지: 메시지
  local code="$1"
  shift
  printf '%s\n' "$*" >&2
  exit "$code"
}

die() {
  die_with "$EXIT_USAGE" "$@"
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

# 역방향 검사. codex-review 스킬은 adversarial에 focus를 **필수**로 규정한다.
# focus 없는 adversarial은 축 없는 일반 리뷰인데 산출물에는 "adversarial 리뷰를 돌렸다"고
# 남는다 — 열화된 리뷰가 가장 강한 기록을 남기는 경로다.
if [ "$MODE" = "adversarial-review" ] && [ -z "$FOCUS" ]; then
  die "오류: adversarial 모드는 focus text가 필수다. 무엇을 적대적으로 파고들지 지정하라.
  예) $0 adversarial --base main --focus \"마스킹 선행 우회 경로를 찾아라\"
  focus 없이 돌리면 축을 지정하지 않은 일반 리뷰가 'adversarial 리뷰 완료'로 기록된다."
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
  die "오류: git 저장소 안에서 실행해야 한다. codex 리뷰는 git diff를 입력으로 쓴다."
fi

# ---------------------------------------------------------------------------
# 리뷰 대상 사전 판정 — 빈 리뷰가 통과 기록이 되는 경로 자체를 없앤다 (근본 대책).
#
# 판정 규칙은 헬퍼(codex 플러그인 1.0.6, scripts/lib/git.mjs)의 resolveReviewTarget /
# getWorkingTreeState / buildBranchComparison 을 읽고 그대로 따라간 것이다:
#   --base <ref>          → scope를 **무시하고** branch 모드.
#                           대상 = `git diff --name-only $(git merge-base HEAD <ref>)..HEAD`
#                           (헬퍼 buildBranchComparison 의 commitRange 와 동일)
#   --scope working-tree  → staged(`diff --cached --name-only`)
#                           + unstaged(`diff --name-only`)
#                           + untracked(`ls-files --others --exclude-standard`)
#                           (헬퍼 getWorkingTreeState 와 동일. isDirty 정의도 이 셋의 합집합이다)
#   --scope branch        → 기본 브랜치를 탐지(detect_default_branch, 헬퍼
#                           detectDefaultBranch 와 같은 순서)한 뒤 위 branch 규칙
#   --scope auto(기본)    → 작업 트리가 더러우면 working-tree, 깨끗하면 위 branch 규칙
#
# **판정이 모호하면 통과시킨다(fail-open).** 잘못된 거부는 정상 리뷰를 막아 결국
# 게이트를 우회하게 만들므로, "명백히 0건"일 때만 막는다. 다음은 전부 판정 불가 → 통과다.
#   - merge-base를 구할 수 없다 (없는 ref, 관련 없는 히스토리)
#   - 기본 브랜치를 탐지할 수 없다 (헬퍼도 같은 이유로 자기 오류를 낼 것이다)
#   - git 명령이 실패한다
#
# 알려진 한계 (= 이 검사가 놓치는 "사실상 빈" 리뷰):
#   - untracked 파일이 있으면 "대상 있음"으로 센다. 헬퍼는 working-tree 리뷰에서
#     untracked 파일 **본문**을 컨텍스트에 싣기 때문이다(git.mjs formatUntrackedFile).
#     다만 헬퍼는 디렉터리·바이너리·24KB 초과 파일을 "(skipped: ...)"로 대체하므로,
#     untracked가 그런 파일뿐이면 실질 대상은 비었는데 이 검사는 통과시킨다.
#     그 세부까지 복제하면 판정이 헬퍼 내부 상수에 묶여 더 잘 깨지므로 복제하지 않았다.
#   - branch 모드는 **커밋된 변경만** 센다(헬퍼와 동일). 커밋하지 않은 변경만 있는
#     상태에서 `--base main`을 주면 대상은 진짜로 비어 있고, 그래서 거부가 맞다.
#   - 파일명만 바뀐 변경(rename), 모드 변경 등 diff 본문이 사실상 없는 변경도
#     "대상 있음"으로 센다.
#   - 헬퍼 부재 시 폴백인 `codex review --uncommitted`는 untracked를 포함하지 않을 수
#     있다. 이 경우에도 이 검사는 통과 방향으로 판정한다(fail-open).
# ---------------------------------------------------------------------------

detect_default_branch() {
  # 헬퍼 detectDefaultBranch(lib/git.mjs)와 같은 순서·같은 반환 형태.
  # 못 찾으면 1을 반환한다(헬퍼는 여기서 예외를 던진다 → 우리는 판정 불가로 본다).
  local head_ref candidate
  head_ref="$(git -C "$REPO_ROOT" symbolic-ref refs/remotes/origin/HEAD 2>/dev/null || true)"
  case "$head_ref" in
    refs/remotes/origin/*)
      printf '%s' "${head_ref#refs/remotes/origin/}"
      return 0
      ;;
  esac
  for candidate in main master trunk; do
    if git -C "$REPO_ROOT" show-ref --verify --quiet "refs/heads/$candidate"; then
      printf '%s' "$candidate"
      return 0
    fi
    if git -C "$REPO_ROOT" show-ref --verify --quiet "refs/remotes/origin/$candidate"; then
      printf 'origin/%s' "$candidate"
      return 0
    fi
  done
  return 1
}

git_line_count() {
  # git 하위 명령의 출력 중 공백이 아닌 줄 수를 찍는다.
  # 명령이 실패하면 1을 반환해 "판정 불가"를 알린다.
  #
  # 주의: 여기서 `${out//[[:space:]]/}` 같은 bash 패턴 치환을 쓰면 안 된다.
  # macOS 기본 bash 3.2는 큰 문자열에 대한 치환이 사실상 멈춘 것처럼 느려진다
  # (untracked 파일 수십 개면 수십 초~분). 실제로 그 구현으로 이 스크립트가 걸렸다.
  # 줄 세기는 grep에 맡긴다.
  local out
  out="$(git -C "$REPO_ROOT" "$@" 2>/dev/null)" || return 1
  # 빈 출력이면 grep -c 가 0을 찍고 1로 끝나므로 || true 로 받는다.
  printf '%s\n' "$out" | grep -c '[^[:space:]]' || true
}

TARGET_LABEL=""
TARGET_VERDICT="unknown" # empty | non-empty | unknown(=통과)
TARGET_DETAIL=""

classify_branch_target() {
  # $1: base ref
  local base="$1" merge_base changed
  TARGET_LABEL="branch diff vs $base"
  merge_base="$(git -C "$REPO_ROOT" merge-base HEAD "$base" 2>/dev/null || true)"
  if [ -z "$merge_base" ]; then
    TARGET_VERDICT="unknown"
    TARGET_DETAIL="merge-base(HEAD, $base)를 구할 수 없다 — 판정하지 않고 헬퍼에 넘긴다"
    return 0
  fi
  if ! changed="$(git_line_count diff --name-only "$merge_base..HEAD")"; then
    TARGET_VERDICT="unknown"
    TARGET_DETAIL="git diff --name-only $merge_base..HEAD 실패 — 판정하지 않는다"
    return 0
  fi
  TARGET_DETAIL="merge-base=${merge_base:0:12}, 변경 파일 ${changed}개 (branch 모드는 커밋된 변경만 센다)"
  if [ "$changed" -eq 0 ]; then
    TARGET_VERDICT="empty"
  else
    TARGET_VERDICT="non-empty"
  fi
}

classify_working_tree_target() {
  local staged unstaged untracked total
  TARGET_LABEL="working tree diff"
  if ! staged="$(git_line_count diff --cached --name-only)" ||
    ! unstaged="$(git_line_count diff --name-only)" ||
    ! untracked="$(git_line_count ls-files --others --exclude-standard)"; then
    TARGET_VERDICT="unknown"
    TARGET_DETAIL="작업 트리 상태 조회 실패 — 판정하지 않는다"
    return 0
  fi
  total=$((staged + unstaged + untracked))
  TARGET_DETAIL="staged ${staged} / unstaged ${unstaged} / untracked ${untracked}"
  if [ "$total" -eq 0 ]; then
    TARGET_VERDICT="empty"
  else
    TARGET_VERDICT="non-empty"
  fi
}

classify_review_target() {
  local detected
  if [ -n "$BASE" ]; then
    classify_branch_target "$BASE"
    return 0
  fi
  if [ "${SCOPE:-auto}" = "working-tree" ]; then
    classify_working_tree_target
    return 0
  fi
  if [ "${SCOPE:-auto}" = "branch" ]; then
    detected="$(detect_default_branch || true)"
    if [ -z "$detected" ]; then
      TARGET_LABEL="branch diff vs (기본 브랜치 탐지 실패)"
      TARGET_VERDICT="unknown"
      TARGET_DETAIL="기본 브랜치를 탐지하지 못했다 — 판정하지 않는다"
      return 0
    fi
    classify_branch_target "$detected"
    return 0
  fi

  # auto: 헬퍼는 작업 트리가 더러우면 working-tree, 깨끗하면 기본 브랜치 대비 branch를 고른다.
  classify_working_tree_target
  if [ "$TARGET_VERDICT" = "non-empty" ]; then
    TARGET_LABEL="$TARGET_LABEL (auto → 작업 트리가 더럽다)"
    return 0
  fi
  if [ "$TARGET_VERDICT" != "empty" ]; then
    return 0
  fi
  # 작업 트리가 깨끗하다 → 헬퍼는 branch 모드로 간다. 대상 판정도 그쪽으로 옮긴다.
  detected="$(detect_default_branch || true)"
  if [ -z "$detected" ]; then
    TARGET_LABEL="branch diff vs (기본 브랜치 탐지 실패)"
    TARGET_VERDICT="unknown"
    TARGET_DETAIL="작업 트리는 깨끗한데 기본 브랜치를 탐지하지 못했다 — 판정하지 않는다"
    return 0
  fi
  classify_branch_target "$detected"
  TARGET_LABEL="$TARGET_LABEL (auto → 작업 트리가 깨끗하다)"
}

classify_review_target

printf 'codex-review: 리뷰 대상 = %s\n' "$TARGET_LABEL" >&2
printf 'codex-review: 대상 판정 = %s (%s)\n' "$TARGET_VERDICT" "$TARGET_DETAIL" >&2

if [ "$TARGET_VERDICT" = "empty" ]; then
  die_with "$EXIT_EMPTY_TARGET" "오류: 리뷰 대상이 비어 있다 — 리뷰를 실행하지 않는다.
  대상: $TARGET_LABEL
  근거: $TARGET_DETAIL
  요청: 모드=$MODE / scope=${SCOPE:-auto(미지정)} / base=${BASE:-(미지정)}

  왜 여기서 막는가: codex는 빈 diff를 받아도 실패하지 않는다. '빈 diff로 확인되어
  지적할 것이 없다'는 문장을 1KB 넘게 써서 verdict=approve로 끝낸다. 그래서
  '종료 코드 0 + 비어 있지 않은 출력'이라는 조건만으로는 이 경우를 걸러내지 못하고,
  아무것도 보지 않은 리뷰가 게이트 통과 기록으로 남는다. 리뷰 비용(수 분 + API)도
  그대로 태운다.

  확인할 것:
    - --base를 잘못 주지 않았나 (예: --base HEAD 는 항상 빈 diff다)
    - 이미 커밋해 놓고 --scope working-tree 로 돌리지 않았나 → --base <시작 커밋|main>
    - 아직 커밋하지 않은 변경만 있는데 --base/--scope branch 로 돌리지 않았나
      → branch 모드는 커밋된 변경만 본다. --scope working-tree 를 써라
    - 브랜치를 착각하지 않았나 (git status / git log --oneline -5 로 확인)
    - untracked 파일뿐이라면 git add -N 로 인덱스에 올려야 diff에 잡히는 경우가 있다"
fi

require_node() {
  command -v node >/dev/null 2>&1 || die "오류: node를 찾을 수 없다. 헬퍼는 node로 실행된다."
}

# handshake — "읽을 수 있다"는 "동작한다"가 아니다.
# CODEX_COMPANION=/dev/null 은 읽을 수 있고 node로 돌려도 exit 0이지만 리뷰를 한 줄도 하지
# 않는다. 그래서 리뷰를 맡기기 전에 최소 대화를 시켜 본다:
#   ① --help가 0으로 끝나고 ② 출력이 공백만은 아니며 ③ 우리가 부를 서브커맨드 이름을 안다.
# ③까지 보는 이유: 아무 스크립트나 도움말을 뱉을 수 있지만, 우리가 실제로 부를 것은
# `review` / `adversarial-review` 서브커맨드이기 때문이다.
helper_handshake() {
  local path="$1" help_out
  help_out="$(node "$path" --help 2>&1)" || return 1
  [ -n "${help_out//[[:space:]]/}" ] || return 1
  case "$help_out" in
    *"$MODE"*) return 0 ;;
    *) return 1 ;;
  esac
}

codex_cli_handshake() {
  local version_out
  version_out="$(codex --version 2>&1)" || return 1
  [ -n "${version_out//[[:space:]]/}" ] || return 1
  return 0
}

HELPER=""
SOURCE=""

# 탐색 4단계 중 1~3단계(헬퍼). 명령 치환이 아니라 전역 변수에 쓴다 —
# `$(...)` 안에서 die를 부르면 서브셸만 죽고 본 셸이 그대로 계속 가기 때문이다.
select_helper() {
  local version candidate

  # 1) 명시 지정. 실패해도 조용히 다음 후보로 넘어가지 않는다. 사용자가 특정 헬퍼를
  #    지목했는데 그것이 동작하지 않으면 그 사실 자체가 보고돼야 한다.
  if [ -n "${CODEX_COMPANION:-}" ]; then
    [ -r "$CODEX_COMPANION" ] ||
      die_with "$EXIT_HANDSHAKE" "오류: CODEX_COMPANION 이 가리키는 파일을 읽을 수 없다: $CODEX_COMPANION"
    require_node
    helper_handshake "$CODEX_COMPANION" ||
      die_with "$EXIT_HANDSHAKE" "오류: CODEX_COMPANION 이 동작하는 codex 헬퍼가 아니다: $CODEX_COMPANION
  handshake(node <헬퍼> --help)가 실패했거나, 출력이 비었거나, '$MODE' 서브커맨드를 모른다.
  읽을 수 있다는 것과 리뷰를 돌릴 수 있다는 것은 다르다 — 여기서 통과시키면 리뷰 없이 exit 0이 나간다."
    HELPER="$CODEX_COMPANION"
    SOURCE="CODEX_COMPANION 환경변수"
    return 0
  fi

  # 2) 플러그인 cache. 버전 디렉터리를 내림차순으로 훑어 handshake까지 통과한 최신 버전을 고른다.
  if [ -d "$CACHE_ROOT" ]; then
    while IFS= read -r version; do
      [ -n "$version" ] || continue
      candidate="$CACHE_ROOT/$version/scripts/codex-companion.mjs"
      [ -r "$candidate" ] || continue
      require_node
      if helper_handshake "$candidate"; then
        HELPER="$candidate"
        SOURCE="plugins cache (최신 버전 자동 선택)"
        return 0
      fi
      printf 'codex-review: 경고 — handshake 실패로 건너뛴 헬퍼: %s\n' "$candidate" >&2
    done <<EOF
$(ls -1 "$CACHE_ROOT" 2>/dev/null | sort -Vr)
EOF
  fi

  # 3) marketplaces 폴백.
  if [ -r "$MARKET_HELPER" ]; then
    require_node
    if helper_handshake "$MARKET_HELPER"; then
      HELPER="$MARKET_HELPER"
      SOURCE="marketplaces 폴백"
      return 0
    fi
    printf 'codex-review: 경고 — handshake 실패로 건너뛴 헬퍼: %s\n' "$MARKET_HELPER" >&2
  fi

  return 1
}

select_helper || HELPER=""

# 실행할 명령을 배열로 조립한다(focus text의 공백·따옴표 보존).
CMD=()
if [ -n "$HELPER" ]; then
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
  # 4) 헬퍼 부재 폴백: codex CLI의 review 서브커맨드를 직접 쓴다.
  # (codex review [PROMPT] --uncommitted | --base <BRANCH> — codex-cli 0.147.0에서 확인)
  SOURCE="codex CLI 직접 호출 (헬퍼 부재 폴백)"
  codex_cli_handshake ||
    die_with "$EXIT_HANDSHAKE" "오류: codex CLI가 handshake(codex --version)에 실패했다.
  PATH에 codex라는 이름의 실행 파일이 있지만 codex CLI로 동작하지 않는다.
  codex --version 을 직접 실행해 확인하라."
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

헬퍼 파일이 있는데도 이 메시지가 나왔다면 위쪽 "handshake 실패로 건너뛴 헬퍼" 경고를 보라 —
파일은 있으나 동작하는 리뷰 도구가 아니라는 뜻이다.

이 실패는 조용히 넘기지 마라. codex 리뷰가 없으면 교차 대조 게이트가 성립하지 않으므로,
산출물에 "codex 리뷰 누락(사유: 헬퍼 미발견)"을 반드시 명시해야 한다.
MISSING
  exit "$EXIT_NO_TOOL"
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
  printf 'codex-review: --dry-run 이므로 실행하지 않는다 (종료 코드 %d — 성공 0과 구분된다).\n' \
    "$EXIT_DRY_RUN" >&2
  printf 'codex-review: 이 실행은 리뷰 근거가 아니다. 게이트를 닫으려면 --dry-run 없이 다시 돌려라.\n' >&2
  exit "$EXIT_DRY_RUN"
fi

cd "$REPO_ROOT"

# exec 하지 않는다. 자식의 stdout(= 리뷰 본문이 나오는 채널. codex-companion.mjs는
# process.stdout.write로만 결과를 낸다)을 tee로 흘려보내면서 동시에 파일에 담아,
# "무언가 쓰였는지"를 우리가 직접 확인한다. stderr은 손대지 않고 그대로 통과시킨다.
OUT_FILE="$(mktemp "${TMPDIR:-/tmp}/codex-review.XXXXXX")"
trap 'rm -f "$OUT_FILE"' EXIT

set +e
"${CMD[@]}" | tee "$OUT_FILE"
CHILD_STATUS="${PIPESTATUS[0]}"
set -e

if [ "$CHILD_STATUS" -ne 0 ]; then
  printf 'codex-review: 리뷰 도구가 실패했다 (종료 코드 %d). 리뷰 근거가 없다.\n' "$CHILD_STATUS" >&2
  exit "$CHILD_STATUS"
fi

if ! grep -q '[^[:space:]]' "$OUT_FILE"; then
  die_with "$EXIT_EMPTY_OUTPUT" "오류: 리뷰 도구가 종료 코드 0으로 끝났는데 출력이 비어 있다.
  실행 명령: ${CMD[*]}
  출처: $SOURCE
  빈 출력은 '지적 없음'이 아니라 '리뷰가 돌지 않았다'이다. 지적이 없으면 리뷰어는
  '지적 없음'이라고 **쓴다** — 아무것도 쓰지 않는 리뷰어는 존재하지 않는다.
  여기서 0을 돌려주면 빈 리뷰 파일이 게이트 통과 기록으로 남는다.
  확인할 것: 위 stderr 출력, 헬퍼 경로(${HELPER:-codex CLI}), codex 인증 상태(codex login)."
fi

# --- 보조 방어: 결과에서 "리뷰 대상이 없었다"는 신호를 잡는다 -----------------
#
# 이것은 위 사전 판정(EXIT_EMPTY_TARGET)의 **보조일 뿐 단독 방어선이 아니다.**
# 사전 판정이 헬퍼의 대상 결정과 100% 일치할 수는 없으므로(위 '알려진 한계' 참고)
# 사후에 한 번 더 본다.
#
# 신호는 두 종류이고 신뢰도가 다르다.
#
# (a) 구조적 신호 — 단독으로 신뢰한다.
#     `Reviewing N staged, N unstaged, and N untracked file(s).` 은 **헬퍼가** 만드는
#     문장이다(git.mjs collectWorkingTreeContext). 모델이 쓴 산문이 아니므로 모델·언어가
#     바뀌어도 유지된다. --json 일 때 payload.context.summary 로 출력에 실린다.
#     한계: 구조적 신호는 이것뿐이다. 헬퍼 payload(codex-companion.mjs executeReviewRun)는
#     target / context.{repoRoot,branch,summary} / codex.stdout / result 만 담고,
#     collectReviewContext 가 계산한 fileCount·diffBytes 는 **payload에 싣지 않는다.**
#     그래서 branch 모드에는 대응하는 구조적 신호가 없고, --json 없이 돌리면 이 신호도
#     출력에 나오지 않는다. 사전 판정을 근본 대책으로 둔 이유가 이것이다.
#
# (b) 문구 매칭 — 취약하다. 그래서 조건을 걸어 쓴다.
#     codex가 쓰는 문장은 모델·언어·프롬프트에 따라 바뀐다. 오늘 잡히는 '빈 diff'가
#     내일 'nothing to inspect'가 되면 이 검사는 조용히 무력해진다. 그러므로:
#       · **지적이 0건일 때만** 문구를 본다. 지적을 하나라도 냈다면 무언가를 읽은
#         것이므로 대상이 비었을 리 없고, 검사를 건너뛰는 편이 오탐을 크게 줄인다.
#         (예: 이 스크립트의 '빈 diff 처리' 자체를 리뷰시키면 리뷰 본문에 '빈 diff'가
#          나온다 — 지적 0건 조건이 그런 오탐을 걸러 낸다.)
#       · 패턴은 diff/대상이 비었다는 **직접 서술**로만 좁힌다. '변경 사항이 없다' 같은
#         일반 문장은 넣지 않는다.
#     이 검사가 놓쳐도(문구가 바뀌어도) 사전 판정이 남아 있고, 반대로 오탐이 나도
#     비용은 재실행 1회다. 출력은 이미 전부 화면에 찍혔으므로 사람이 확인할 수 있다.

NO_TARGET_PHRASES="$(
  cat <<'PATTERNS'
빈 diff
비어 있는 diff
diff가 비어
diff는 비어
diff 가 비어
empty diff
diff is empty
no diff to review
no changes to review
nothing to review
no files changed
변경된 파일이 없
검토할 대상이 없
리뷰할 대상이 없
PATTERNS
)"

check_no_target_signal() {
  local file="$1"
  # (a) 헬퍼가 만든 구조적 신호. --json 일 때만 나타난다.
  if grep -qF 'Reviewing 0 staged, 0 unstaged, and 0 untracked file' "$file"; then
    printf 'structural'
    return 0
  fi
  # (b) 지적 0건인 경우에만 모델 산문을 본다.
  if grep -qF 'No material findings.' "$file" ||
    grep -qE '"findings"[[:space:]]*:[[:space:]]*\[[[:space:]]*\]' "$file"; then
    if printf '%s\n' "$NO_TARGET_PHRASES" | grep -qiFf - "$file"; then
      printf 'phrase'
      return 0
    fi
  fi
  return 1
}

if NO_TARGET_KIND="$(check_no_target_signal "$OUT_FILE")"; then
  die_with "$EXIT_EMPTY_TARGET" "오류: 리뷰 도구는 종료 코드 0으로 끝났지만, 결과가
  '리뷰할 대상이 없었다'고 말한다 (신호 종류: $NO_TARGET_KIND).
    structural = 헬퍼가 만든 '0 staged, 0 unstaged, 0 untracked' 요약
    phrase     = 지적 0건 + 빈 diff를 직접 서술하는 문구
  대상: $TARGET_LABEL / 판정: $TARGET_VERDICT ($TARGET_DETAIL)
  실행 명령: ${CMD[*]}

  출력이 비어 있지 않다는 것은 '리뷰가 돌았다'를 뜻하지 실제로 **무언가를 검토했다**를
  뜻하지 않는다. 빈 대상에 대한 approve는 게이트 통과 근거가 될 수 없으므로 0으로
  끝내지 않는다. --base/--scope를 다시 잡아 재실행하라.

  이 검사가 오탐일 가능성: 문구 매칭(phrase)은 모델이 쓴 산문에 의존하므로 완전하지
  않다. 위에 그대로 출력된 리뷰 본문을 읽고, 실제로 변경을 검토한 리뷰였다면 대상
  판정 로그(리뷰 대상/대상 판정 줄)를 산출물에 함께 남겨 근거로 삼아라."
fi

exit 0

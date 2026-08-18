#!/bin/bash
# run_gate.sh — 게이트 명령 러너: 파이프가 있어도 종료 코드가 진실이 되게 한다.
#
# 왜 있는가: 게이트 명령을 `pytest | tail` 처럼 파이프로 이으면 종료 코드가
# 마지막 명령(tail)의 것이 되어 실패가 삼켜진다. 실제로 e90cfe4 가 빨간 가드인
# 채 커밋·푸시됐다(게이트 15 X3 / 리뷰 T-D — CLAUDE.md 규칙 4 근거 4번과 같은
# 구조의 두 번째 발생). 이 러너는 **단일 문자열 인자 하나**를 받아
# `bash -o pipefail` 아래에서 실행해 파이프 안 어느 단계가 실패해도 비-0 이
# 전파되게 하고, 실행한 명령과 종료 코드를 stdout 에 기록한 뒤 그 코드를
# 그대로 자기 종료 코드로 낸다. zsh 에서 불러도 안전하다 — 파이프는 항상
# bash+pipefail 아래에서 돌므로 zsh 의 pipestatus(소문자·1-기반)와 bash 의
# PIPESTATUS 차이에 기대지 않는다.
#
# 계약 (tests/test_run_gate.py 가 문장마다 고정한다 — 게이트 16 A · 게이트 17 자기 감사):
#   - 인자는 **정확히 하나**, 셸 명령 문자열이다. 0개·2개 이상이면 exit 2 + 사용법.
#     argv 나열형(`run_gate.sh uv run pytest -k 'a or b'`)은 받지 않는다 — 옛 판이
#     `$*` 로 재조립한 뒤 `bash -c` 로 재파싱해 인용 경계·연속 공백이 소실됐다
#     (게이트 16 D). 그 문자열은 그대로 `bash -c` 의 한 인자로 넘어가므로 안쪽 인용은
#     보존된다.
#   - **빈 호출은 통과가 아니라 실패다** — 다음 셋 전부 그 범주다.
#     ⒜ 인자 내용이 비었거나 공백뿐이면 exit 2 (게이트 16 C, 차단②: "검사 대상 0건인데
#        성공으로 끝나는 스크립트" 그 자체 — 옛 판은 `$#` 만 보고 내용을 안 봤다).
#     ⒝ 인자는 비지 않았는데 자식 bash 가 **해석한 뒤 실행할 명령이 0건**이면 exit 2
#        (게이트 17 X17-1 zero-work — 주석 전용 `'# ...'`·백슬래시-개행만). 원문 공백 검사는
#        자식 셸이 해석하기 전만 보므로 이것을 놓쳤다. 판정은 손 파싱이 아니라 **bash 자신에게
#        묻는다**: 자식에 `-o functrace` 와 DEBUG trap 을 걸어 첫 명령이 실행 단계에 들어갈 때
#        마커 파일에 1회 기록하고, rc 0 인데 마커가 비면 zero-work 다. functrace 가 없으면
#        서브셸 `(cmd)`·명령 치환 안의 명령이 trap 에 안 잡혀 오탐이 난다(실측) — 그래서 켠다.
#        **못 잡는 잔여(정직하게)**: 값이 빈 문자열로 **설정된** 변수만 있는 명령(`'$V'`, V="")은
#        문법상 단순 명령이라 DEBUG trap 이 발화한 뒤 확장 결과가 비어 0건 실행·rc 0 이 된다 —
#        이 기제로는 구조적으로 못 본다(xtrace 는 구분하지만 bash 3.2 엔 BASH_XTRACEFD 가 없어
#        stderr 오염 없이 쓸 수 없다). 미설정은 ⒞ 가 잡는다. 변수로 명령을 넘길 거면 `${V:?}` 를
#        써라 — 비어 있으면 자식이 비-0 을 낸다. tests/test_run_gate.py 의 LIMIT 테스트가 이
#        잔여를 문서화한다.
#     ⒞ **미설정 변수 참조는 오류다** — 자식을 `-o nounset` 으로 돌린다. `'$GATE_CMD'`(미설정)
#        은 옛 판에서 빈 문자열로 확장돼 0건 실행·exit 0 이었고, 이제 자식이 "unbound
#        variable" 로 비-0 을 낸다(값은 bash 판에 따라 다르다 — 127 등. 계약은 "비-0" 이다).
#        선택 변수는 `${VAR:-}` 로 써라 — 이것은 nounset 아래에서도 통과한다(테스트가 고정).
#   - 종료 코드는 명령의 것을 가공 없이 전파한다. 파이프 안 실패는 pipefail 로 비-0.
#   - 실행한 명령 **전문**과 종료 코드가 stdout 에 `[run_gate] cmd:` / `[run_gate] exit:` 로
#     기록된다.
#
# 장치 분류(kotlin-migration SKILL.md "선언한 범위와 실제 도달을 대조한다"
# 규칙 4): 탐지형 — 어긋남(실패 종료 코드·0건 실행)을 드러낸다. 면제 목록·무시 패턴 없음.
#
# 자기 도달(정직하게 — 장치가 하는 일보다 크게 적지 않는다). 파이프 축의 상태는 셋이다:
#   ⑴ 러너를 경유하지 않은 명령 — 못 잡는다. 이 러너는 "게이트 명령은 러너 경유
#      또는 파이프 금지" 규약(SKILL.md 규칙 5) 자체의 강제자가 아니다.
#   ⑵ 명령이 러너 **안**에서 파이프를 구성한 경우(`run_gate.sh 'pytest | tail'`) —
#      pipefail 이 잡는다. 이 러너가 하는 일은 여기까지다.
#   ⑶ 명령은 경유했는데 파이프가 러너 **밖**에서 구성된 경우
#      (`run_gate.sh 'pytest' | tail`) — **못 잡는다.** 호출 측 셸이 러너보다 먼저
#      파이프를 만들어 outer 종료 코드는 tail 의 것이 된다(실측: bash 0 · zsh 0 · sh 0).
#      러너 호출 자체를 파이프에 태우면 이 러너는 무효다(게이트 16 B / codex K16-1).
#      tests/test_run_gate.py 가 이 한계를 "못 잡는다"로 **문서화**한다 — 고친 것이 아니다.
#   실행 경로: `local` (게이트 명령을 이 스크립트에 태워 줄 때만 돈다) ·
#   계약 테스트는 `ci:quality`(tests/test_run_gate.py 경로 명시 스텝). 러너 자신을
#   부르는 CI 배선은 0.
#
# 전제 — 인자에 비밀값을 넣지 마라. 실행 명령 **전문**이 stdout 에 기록된다(게이트 16 T).
# 마스킹하지 않는다 — 은폐 장치를 두는 대신 전제로 적는다. 비밀은 환경변수로 넘겨라.
#
# 사용 (인용형만 — 문자열 하나):
#   .claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest tests/x'
#   .claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest tests/x | tail -20'
#   .claude/skills/kotlin-migration/scripts/run_gate.sh "uv run pytest -k 'a or b'"

set -u

usage() {
  echo "사용법: run_gate.sh '<셸 명령 문자열 하나>'   (인자는 정확히 1개, 인용형만)" >&2
}

if [ "$#" -ne 1 ]; then
  echo "[run_gate] 오류: 인자는 정확히 1개여야 한다 (받은 개수: $#). argv 나열형은 인용 경계를 잃는다" >&2
  usage
  exit 2
fi

cmd="$1"
case "${cmd}" in
  *[![:space:]]*) ;;
  *)
    echo "[run_gate] 오류: 실행할 게이트 명령이 없다 — 인자가 비었거나 공백뿐이다 (빈 호출은 통과가 아니라 실패다)" >&2
    usage
    exit 2
    ;;
esac

# zero-work 마커: 자식의 DEBUG trap 이 첫 명령 실행 직전에 1회 기록하고 스스로 해제한다.
# 서브셸·명령 치환에도 닿도록 자식은 -o functrace 로 돈다.
marker="$(mktemp "${TMPDIR:-/tmp}/run_gate.XXXXXX")" || {
  echo "[run_gate] 오류: 마커 임시 파일을 만들 수 없다" >&2
  exit 2
}
trap 'rm -f "${marker}"' EXIT
preamble="trap 'echo >>\"\$RUN_GATE_MARKER\"; trap - DEBUG' DEBUG"

echo "[run_gate] cmd: ${cmd}"
RUN_GATE_MARKER="${marker}" bash -o pipefail -o nounset -o functrace -c "${preamble}
${cmd}"
rc=$?
if [ "${rc}" -eq 0 ] && [ ! -s "${marker}" ]; then
  echo "[run_gate] 오류: 확장 후 실행된 명령이 0건이다 (주석 전용·백슬래시-개행·빈 확장 등) — 빈 호출은 통과가 아니라 실패다" >&2
  echo "[run_gate] exit: 2"
  exit 2
fi
echo "[run_gate] exit: ${rc}"
exit "${rc}"

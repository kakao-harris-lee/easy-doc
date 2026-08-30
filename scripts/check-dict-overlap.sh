#!/usr/bin/env bash
# 중복 표제어 단일 출처 게이트 — 사전(easy-dictionary)이 이미 맡은 낱말이 이쪽
# 치환 목록(`DIFFICULT_WORD_REPLACEMENTS`)에 되살아나는지 본다.
#
#   ./scripts/check-dict-overlap.sh
#   EASY_DICTIONARY_REPO=/다른/경로 ./scripts/check-dict-overlap.sh
#
# ## 왜 필요한가
#
# 같은 낱말이 양쪽에 있으면 한 프롬프트 안에 "바꿔라"와 "그대로 둬라"가 같이
# 실린다. 한 번 지운 낱말이 나중에 무심코 다시 들어오는 것이 재발 경로라서,
# 사람 검토가 아니라 게이트로 막는다. 판정 기준은
# `easy-dictionary/docs/consumer-overlap-policy.md`, 검출은 같은 저장소의
# `tools/detect_consumer_overlap.py` 가 맡는다 — 이 스크립트는 그 판정을
# 통과/차단으로 옮기기만 한다.
#
# ## 사전 저장소가 없으면 [건너뜀]이다
#
# 교차 저장소 검사라 사전 저장소가 옆에 없는 환경(예: GitHub CI)에서는 돌지
# 않는다. 그때는 "통과"가 아니라 "검사 안 함"이라고 밝히고 0으로 끝낸다 —
# 사전 저장소 `scripts/check.sh` 4단계와 같은 방침이다. 실효는 사전 저장소를
# 옆에 둔 로컬·릴리스 검증에서 난다.
#
# CI 상시 차단(이미 흡수된 낱말의 재도입)은 core 의 `AbsorbedWordGateTest` 가 맡고,
# 이 스크립트는 살아있는 사전 색인과 대조해 신규 중복(사전 성장분)을 로컬·릴리스에서
# 잡는 층이다.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dict_repo="${EASY_DICTIONARY_REPO:-$repo_root/../easy-dictionary}"
detector="$dict_repo/tools/detect_consumer_overlap.py"
index_path="$dict_repo/dist/easy_dict.index.json"
consumer="$repo_root/backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/DifficultWords.kt"

# 처분 보류분(consumer-overlap-policy.md §3.3). 사전 쪽 처분이 끝나는 대로
# 비워서 0을 목표로 한다 — 이 목록이 남아 있는 동안만 봐주는 예외지 정상이 아니다.
export HOLD_WORDS="공지 내역 당월 소요 하자"

echo "================================================================"
echo "사전 중복 표제어 게이트 (easy-dictionary 대조)"
echo "================================================================"

if [ ! -d "$dict_repo" ] || [ ! -f "$detector" ] || [ ! -f "$index_path" ]; then
    echo "[건너뜀] 사전 저장소 없음 — 검사 안 함"
    echo "         찾은 경로: $dict_repo"
    echo "         '통과'가 아니라 '검사 안 함'이다."
    echo "         EASY_DICTIONARY_REPO 로 사전 저장소 위치를 지정할 수 있다."
    exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "[건너뜀] python3 없음 — 검사 안 함"
    echo "         '통과'가 아니라 '검사 안 함'이다."
    exit 0
fi

report="$(mktemp)"
trap 'rm -f "$report"' EXIT

# 검출 도구는 차단 판정이 있으면 1로 끝난다. 그 코드를 그대로 쓰지 않고 JSON 을
# 다시 읽는 이유는 보류 목록 때문이다 — 도구는 보류분을 모르므로 여기서 거른다.
python3 "$detector" --consumer "$consumer" --index "$index_path" --json >"$report"

REPORT_PATH="$report" python3 <<'PY'
import json
import os
import sys

raw = open(os.environ["REPORT_PATH"], encoding="utf-8").read()
try:
    overlaps = json.loads(raw)
except json.JSONDecodeError:
    print("[실패] 검출 도구가 JSON 을 내놓지 않았다 — 통과로 볼 수 없다.")
    print(raw[:2000])
    sys.exit(1)

hold = set(os.environ["HOLD_WORDS"].split())
blocking = [o for o in overlaps if o["verdict"] in ("CONFLICT", "DIVERGENT")]
unexpected = [o for o in blocking if o["word"] not in hold]

if unexpected:
    words = sorted({o["word"] for o in unexpected})
    print(f"[실패] 사전이 맡은 낱말이 치환 목록에 있다: {len(words)}낱말 / {len(unexpected)}엔트리")
    for o in sorted(unexpected, key=lambda x: (x["word"], x["entry_id"])):
        print(
            "  {word} [{verdict}] 이쪽='{ours}' 사전='{theirs}' (전략 {strategy}, 위험 {risk})".format(
                word=o["word"],
                verdict=o["verdict"],
                ours=o["consumer_replacement"],
                theirs=o["dict_easy_term"],
                strategy=o["dict_strategy"],
                risk=o["dict_risk"],
            )
        )
    print("        치환 목록에서 빼거나, 처분이 끝났으면 이 스크립트의 HOLD_WORDS 를 고친다.")
    sys.exit(1)

held = sorted({o["word"] for o in blocking})
print(f"[통과] 허용 목록 밖 중복 없음 — 보류 {len(blocking)}엔트리 / {len(held)}낱말")
if held:
    print("       처분 보류(§3.3): " + " ".join(held))
PY

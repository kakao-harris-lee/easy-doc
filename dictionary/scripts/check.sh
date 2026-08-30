#!/usr/bin/env bash
# 검사 진입점 (docs/inspection-plan.md Phase 1).
#
# 명령 하나로 이번 세션에 손으로 치던 검사를 전부 돌린다. 하나라도 실패하면
# 비영(non-zero) 종료한다. 실행 방법은 README.md의 "빌드 검증"에도 남긴다.
#
#   ./scripts/check.sh
#
# 묶는 것 (1/5 ~ 5/5):
#   1. tests/        (unittest, 189개) — 빌드 파이프라인·정규화·스키마·재현성·
#                     재빌드 결정성·동일표면형 승자결정 등 함수/모듈 단위 계약
#   2. tools/tests/   (unittest, 32개) — tools/ 보조 스크립트 계약
#   3. tools/check_invariants.py — 층위 1 산출물 불변식(dist/를 읽기만 한다,
#      절대 재빌드하지 않는다). substitute+review 공존 금지·readability
#      범위·deprecated 유출·simple.jsonl 계약처럼 이미 알던 것과, 도달
#      가능성·보호 엔트리 승리·엔트리 귀속 불변처럼 이번 세션 결함에서 새로
#      도출한 것을 함께 본다.
#   4. tools/audit_corpus.py — 층위 2 코퍼스 통과 검사(골든 문서에 현재
#      dist/를 통과시켜 경계 위반·원문 파괴·활용형 비문·상충 지침을 센다).
#      골든 문서(기본 ../data/golden — easy-doc 저장소 루트 기준)가 없으면
#      이 단계만 [건너뜀] — "통과"가 아니라 "검사 안 함"이다.
#   5. tools/detect_consumer_overlap.py — 소비자 중복 게이트(단일 출처 정책,
#      docs/consumer-overlap-policy.md §4). easy-doc 내장 목록과 사전의
#      표제어·표면형 교집합이 다시 생기면(CONFLICT/DIVERGENT) 실패한다 —
#      같은 낱말에 정반대 지시가 공존하는 사고의 재발 방지. 소비자 목록
#      (기본 ../backend-kotlin의 DifficultWords.kt)이 없으면 [건너뜀].
#
# 묶지 않는 것(의도적 — docs/inspection-plan.md §4 Phase 4 참고):
#   - 층위 3(의미 검증, tools/detect_homonym_risk.py)은 별도 도구다 —
#     동형어 처분안 작업이 다른 레인에서 진행 중이라 여기서 건드리지 않는다.
#
# "통과"가 "안전"을 뜻하지 않는다 — 3단계가 매번 [미검사] 두 줄을 함께
# 출력하고(층위 2는 4단계로 넘어왔으니 이제 층위 3만 [미검사]), 4단계도
# 골든 문서가 없으면 그 자체가 "검사 안 함"이라고 스스로 [건너뜀]으로 밝힌다.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export PYTHONPATH="$REPO_ROOT/src"

overall_rc=0

echo "================================================================"
echo "1/5  tests/ (unittest)"
echo "================================================================"
python3 -m unittest discover -s tests
rc=$?
if [ $rc -ne 0 ]; then
    echo "[실패] tests/ (rc=$rc)"
    overall_rc=1
fi

echo
echo "================================================================"
echo "2/5  tools/tests/ (unittest)"
echo "================================================================"
PYTHONPATH="$REPO_ROOT/src:$REPO_ROOT/tools" python3 -m unittest discover -s tools/tests
rc=$?
if [ $rc -ne 0 ]; then
    echo "[실패] tools/tests/ (rc=$rc)"
    overall_rc=1
fi

echo
echo "================================================================"
echo "3/5  층위 1 산출물 불변식 (dist/ 읽기 전용)"
echo "================================================================"
if [ ! -f "$REPO_ROOT/dist/easy_dict.sqlite3" ]; then
    echo "[건너뜀] dist/easy_dict.sqlite3 없음 — 빌드 먼저 필요"
else
    PYTHONPATH="$REPO_ROOT/src" python3 tools/check_invariants.py
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "[실패] 층위 1 불변식 (rc=$rc)"
        overall_rc=1
    fi
fi

echo
echo "================================================================"
echo "4/5  층위 2 코퍼스 통과 검사 (골든 문서, dist/ 읽기 전용)"
echo "================================================================"
GOLDEN_DIR="${GOLDEN_DIR:-$REPO_ROOT/../data/golden}"
if [ ! -d "$GOLDEN_DIR" ]; then
    echo "[건너뜀] 골든 문서를 못 찾음: $GOLDEN_DIR"
    echo "         easy-doc 저장소 안의 dictionary/ 하위가 아니면 이 단계는"
    echo "         돌지 않는다 — '통과'가 아니라 '검사 안 함'이다."
else
    PYTHONPATH="$REPO_ROOT/src" python3 tools/audit_corpus.py --golden-dir "$GOLDEN_DIR"
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "[실패] 층위 2 코퍼스 통과 (rc=$rc)"
        overall_rc=1
    fi
fi

echo
echo "================================================================"
echo "5/5  소비자 중복 게이트 (단일 출처 정책, dist/ 읽기 전용)"
echo "================================================================"
CONSUMER_LIST="${CONSUMER_LIST:-$REPO_ROOT/../backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/DifficultWords.kt}"
if [ ! -f "$REPO_ROOT/dist/easy_dict.index.json" ]; then
    echo "[건너뜀] dist/easy_dict.index.json 없음 — 빌드 먼저 필요"
elif [ ! -f "$CONSUMER_LIST" ]; then
    echo "[건너뜀] 소비자 목록을 못 찾음: $CONSUMER_LIST"
    echo "         easy-doc 저장소 안의 dictionary/ 하위가 아니면 이 단계는"
    echo "         돌지 않는다 — '통과'가 아니라 '검사 안 함'이다."
else
    PYTHONPATH="$REPO_ROOT/src" python3 tools/detect_consumer_overlap.py \
        --consumer "$CONSUMER_LIST" --index "$REPO_ROOT/dist/easy_dict.index.json"
    rc=$?
    if [ $rc -ne 0 ]; then
        echo "[실패] 소비자 중복 게이트 (rc=$rc) — 같은 낱말에 모순 지시가 공존한다."
        echo "        처분 규칙: docs/consumer-overlap-policy.md §3"
        overall_rc=1
    fi
fi

echo
echo "================================================================"
if [ $overall_rc -eq 0 ]; then
    echo "check.sh: 전체 통과"
else
    echo "check.sh: 실패 (위 [실패] 항목 참고)"
fi
echo "================================================================"

exit $overall_rc

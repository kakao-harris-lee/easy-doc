#!/bin/bash
# run_gate.sh — 게이트 명령 러너: 파이프가 있어도 종료 코드가 진실이 되게 한다.
#
# 왜 있는가: 게이트 명령을 `pytest | tail` 처럼 파이프로 이으면 종료 코드가
# 마지막 명령(tail)의 것이 되어 실패가 삼켜진다. 실제로 e90cfe4 가 빨간 가드인
# 채 커밋·푸시됐다(게이트 15 X3 / 리뷰 T-D — CLAUDE.md 규칙 4 근거 4번과 같은
# 구조의 두 번째 발생). 이 러너는 인자 전체를 하나의 셸 문자열로 합쳐
# `bash -o pipefail -c` 로 실행해 파이프 안 어느 단계가 실패해도 비-0 이
# 전파되게 하고, 실행한 명령과 종료 코드를 stdout 에 기록한 뒤 그 코드를
# 그대로 자기 종료 코드로 낸다. zsh 에서 불러도 안전하다 — 파이프는 항상
# bash+pipefail 아래에서 돌므로 zsh 의 pipestatus(소문자·1-기반)와 bash 의
# PIPESTATUS 차이에 기대지 않는다.
#
# 장치 분류(kotlin-migration SKILL.md "선언한 범위와 실제 도달을 대조한다"
# 규칙 4): 탐지형 — 어긋남(실패 종료 코드)을 드러낸다. 면제 목록·무시 패턴 없음.
# 빈 호출은 통과가 아니라 실패다(exit 2).
#
# 자기 도달(정직하게): local — 게이트 명령을 이 스크립트에 태워 줄 때만 돈다.
# CI 배선 0. 이 러너는 경유하지 않은 파이프 명령을 탐지하지 못한다 — 즉
# "게이트 명령은 러너 경유 또는 파이프 금지" 규약(SKILL.md 규칙 5) 자체의
# 강제자가 아니라, 경유한 명령의 종료 코드를 정직하게 만드는 장치까지만이다.
#
# 사용:
#   .claude/skills/kotlin-migration/scripts/run_gate.sh uv run pytest tests/x
#   .claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest tests/x | tail -20'

set -u
set -o pipefail

if [ "$#" -eq 0 ]; then
  echo "[run_gate] 오류: 실행할 게이트 명령이 없다 (빈 호출은 통과가 아니라 실패다)" >&2
  exit 2
fi

cmd="$*"
echo "[run_gate] cmd: ${cmd}"
bash -o pipefail -c "${cmd}"
rc=$?
echo "[run_gate] exit: ${rc}"
exit "${rc}"

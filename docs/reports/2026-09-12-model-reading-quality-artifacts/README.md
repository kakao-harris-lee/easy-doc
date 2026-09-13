# 유료 실측 산출물

- [집계와 총비용](usage.json): 응답 토큰 및 설정 단가 기준. 청구서 확정액은 아니다.
- [독해 확인 기준](reading-checks.md): 보류 표본을 변환하기 전에 원문으로 작성한 대조 기준.
- `corpus/`: 개발 문서 3건. `holdout/`: 보류 문서 2건.
- 각 조건 폴더의 `lane.log`, `invocation.json`, `exit.json`, `calls.json`: 원시 결과, 설정·예약 상한·해시, 종료 상태, 호출별 사용량.
- 각 조건의 `outputs/`: 모델의 최종 결과 그대로. 키·요청 헤더는 없다.
- `sources/phase1`, `phase2`, `phase3`: 주요 코드·프롬프트의 고정 사본과 해시. 같은 단계 내의 비교는 같은 소스를 사용한다.
- `run.cjs`와 `arms.json`: 기존 Kotlin 레인을 순차 실행한 임시 실행 도구·설정이다. 독립 채점기나 새 검증 하네스가 아니다. 경로가 측정 환경에 고정돼 있으므로 그대로 자동 재실행하지 않는다. 미사용 후보 설정도 포함되며 실제 호출 여부는 `exit.json`과 `calls.json`으로 확인한다.

## 결과 보기

- [현재 Chrome 문서 — Astra 개선 예](astra-improved/outputs/001-current.txt) (phase2)
- [주택지원 — Astra 개선 예](astra-improved/outputs/097.txt) (phase2)
- [부모상담 — 마지막 개선 결과](astra-final/outputs/087.txt) (phase3)
- [임신·출산 진료비 — 원문 오타 설명 미달](astra-final/outputs/088.txt) (phase3)
- [에너지바우처 — 보류 표본 결과](astra-final/outputs/090.txt) (phase3)

`sonnet-low`는 코퍼스 디렉터리 사전 검사 실패로 호출이 0회다. 실제 low 비교는 `sonnet-low-r1`이다. `sonnet-high`의 현재 문서는 첫 변환이 출력 한도에 걸려 최종 텍스트가 없다. 실패·누락 결과를 빈 성공 파일로 채우지 않았다.

phase3는 일반 설명 지침을 추가한 소스다. phase2의 개발 3문서를 이 최종 소스로 모두 다시 측정한 것은 아니다. 087은 개선 후 재실행했고, 088·090은 처음 변환했다. 실제 초등학생 참여 독해 시험이나 무작위 반복 실험은 아니다.

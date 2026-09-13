# 유료 실측 증거

[분석 보고서](../2026-09-11-reading-level-measurement.md)

원문 3건, 변환 결과 15개를 실제 출력 그대로 보존했다. 출력 뒤의 문장 수정은 없다. 각 레인은 변환 1회와 최대 보정 1회 후 기존 judge를 호출한다. 이번에는 모든 변환에 보정 1회가 실행됐다.

| 문서 | 변경 전 | 변경 후 | 변경 후 사전 제외 |
|---|---|---|---|
| 현재 Chrome 청소년 안내문 | [1회](before/001-current-run1.txt), [2회](before/001-current-run2.txt) | [1회](after/001-current-run1.txt), [2회](after/001-current-run2.txt) | [1회](after-no-dict/001-current.txt) |
| 발달장애인 부모상담 | [1회](before/087-run1.txt), [2회](before/087-run2.txt) | [1회](after/087-run1.txt), [2회](after/087-run2.txt) | [1회](after-no-dict/087.txt) |
| 매입임대주택 | [1회](before/097-run1.txt), [2회](before/097-run2.txt) | [1회](after/097-run1.txt), [2회](after/097-run2.txt) | [1회](after-no-dict/097.txt) |

- 원문: [현재 문서](corpus/001-current.json), [부모상담](corpus/087-발달장애인-부모상담지원사업.json), [주택지원](corpus/097-기존주택등-매입임대주택-지원사업.json)
- 실행 조건·소스 해시: [manifest](manifest.json)
- 기존 레인 원시 보고서: [변경 전](before-report.txt), [변경 후](after-report.txt), [사전 제외](after-no-dict-report.txt)
- 호출별 토큰·비용·지연: [변경 전](before-usage.json), [변경 후](after-usage.json), [사전 제외](after-no-dict-usage.json), [전체 합계](total-usage.json)

총 호출 45회. 예상 비용 US$1.464392, 총 예약액 US$8.935236, 최종 승인 상한 US$10이다. 입력 US$2/출력 US$10(백만 토큰당)을 사용했으며, 청구서 확정액이나 세금 포함 금액은 아니다. 각 조건의 invocation/exit JSON에 시작·종료 시각과 종료 코드를 남겼다. before-invocation의 US$20은 실행 시작 시점의 승인 이력이며 실행 중 사용자의 최신 US$10 지시에 따라 후속 실행의 상한을 낮췄다. 세 레인의 exit code 1은 보고서에 나온 품질 assertion 실패다.

문서마다 2회인 주 비교와 1회인 보조 비교를 구분한다. judge yes/no와 기계 숫자 보존율에는 보고서에 기록한 오판이 있으므로 원시 점수를 실제 독해 합격률로 사용하지 않는다. 중간 초안과 judge 전체 응답은 기존 레인이 보존하지 않으며, 최종 출력과 판정 실패 이유만 남는다.

재현은 기존 Kotlin 레인을 사용한다. 사용자 승인과 비용 상한이 먼저 필요하며, 환경변수 EASYDOC_LANE_MAX_USD를 지정하지 않으면 유료 호출 전에 실패한다. 사전 제외는 EASYDOC_LANE_DICT_PRODUCT와 EASYDOC_LANE_DICT_CONTEXT_DIR를 모두 해제한다. PRODUCT=0도 값이 존재하는 것으로 처리되므로 제외 설정이 아니다. 소스는 manifest의 해시에 맞춰야 한다. 유료 재실행을 자동으로 시작하는 파일은 포함하지 않았다.

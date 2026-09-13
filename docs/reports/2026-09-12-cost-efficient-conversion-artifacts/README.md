# 비용 개선 실측 산출물

`../2026-09-12-cost-efficient-conversion.md`의 원자료다. API 키와 전체 `.env`는 포함하지 않는다. 문서 원문·결과에는 공개 공고의 연락처가 포함될 수 있다.

- `budget.json`: 이전 완료 실험의 usage 정산액과 새 예약 상한. 이번 배치에서는 예약을 반환하지 않았다.
- `usage.json`: 여섯 조건의 문서별 변환+보정 비용, 별도 judge 비용, 실제 usage 기반 예상액과 예약액.
- `{arm}-calls.json`: 구조화 로그의 54개 성공 호출 기록. 인덱스 순서는 해당 로그의 실행 순서이며 purpose는 문서별 `passes`로 구분한다.
- `{arm}.log`: 기존 Kotlin 레인의 출력. 종료 코드 1인 Astra/Sol/Terra는 provider 실패가 아니라 judge 품질 실패다.
- `{arm}-invocation.json`, `{arm}-exit.json`: 호출 전 조건·소스 해시·시작 시각과 종료 코드. 소스 해시의 일부 리팩터링 차이는 아래 조건 설명을 따른다.
- `{arm}-outputs/*.txt`: 최종 결과와 실행 조건. `passes/*.json`은 첫 변환, 보정, 실제 채택 여부와 비용이다. baseline의 초기 관측 형식에는 나중에 추가된 model/latency/repairStyleIssues가 없다. 본문·비용·채택·기존 styleIssues·missingFacts는 있다.
- `corpus`: 개발 원문 001-current, 087, 097. 기존 품질 실험의 같은 JSON이다.
- `validation-final`: 이미 이전 실험에서 사용한 087, 088, 090. 이번 실행에서는 호출하지 않았고 미지의 holdout으로 주장하지 않는다.
- `reading-review.md`: 사전 고정 질문을 바탕으로 Codex가 원문/결과를 직접 대조한 판단. 자동 judge와 별개이며 실제 독자 실험을 대신하지 않는다.
- `sources/baseline`: 기존 phase3 제품 경로. `sources/improved`: 날짜·공식 파일명 검사 개선 후. `sources/clear`: 짧은 설명 지침 실험. `sources/final`: 실제 최종 제품/관측 파일. 디렉터리마다 파일별 SHA-256을 둔다.

baseline 이후 저비용 4조건은 phase3 프롬프트가 같다. clear는 EXPLAIN 지침만 바꾼 별도 조건이며 최종 제품에는 채택하지 않았다. 검사 개선 중 날짜 매치 코드의 함수 추출, 마지막 숫자 필터의 파일 이동은 의미를 바꾸지 않은 코드 구성 수정이다. 최종 검증에서 들여쓴 목록의 파일 이름 처리도 확인한다. 비교 표는 제품 구성 비교이며 모델만의 인과 효과를 분리한 실험이 아니다.

`run.cjs`, `arms.json`은 이전 실험의 기존 레인을 호출한 임시 실행 명령의 보존본이다. 기존 Kotlin `GoldenCorpusLlmEvaluationTest`를 실행하며 별도 평가 엔진이 아니다. 보존본에는 당시 절대경로·이미 사용한 로그 이름이 들어 있다. 그대로 실행하면 중복 호출을 거부한다. high 조건이 arms에 있지만 실행 로그/종료 기록은 없으며 실제 호출하지 않았다. 향후 다시 실행하려면 남은 예산, 독립 실행 경로와 사전 조건을 새로 정해야 한다. 유료 호출을 일반 build에 포함하지 않는다.

재검산: 각 로그의 `llm_call ... estimated_cost_usd`를 합하면 조건의 `totalEstimatedUsd`, 각 문서의 `passes.calls[].estimatedCostUsd`를 합하면 제품 처리비다. 그 차이가 judge 비용이며 문서별 합계의 평균으로 비용을 비교했다. 이전 이월 $5.565252와 이번 $2.986990을 합하면 $8.552242다. 모든 응답을 받았으므로 사용량 미확인 호출을 0으로 가정한 항목은 없다.

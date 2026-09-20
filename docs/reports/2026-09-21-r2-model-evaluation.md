# R2 행동 안내문 모델 평가

작성일: 2026-09-21

기준 커밋: `2313dc6af1079570fab2b3dc8d6c2b6eda134bc9`

상태: **production readiness 실패** — 후보 검증 거절 9건은 기존 사실 검사 규칙의 오탐으로 진단됐다. 실제 제품이 해당 후보를 거절하므로 규칙 보완·회귀 검증 전 출시를 보류한다.

## 실행 조건

이번 실행은 사용자가 승인한 누적 US$50 이하 범위에서 진행했다. 기존 Kotlin quality lane과 production action-guide runner를 사용했으며, 프롬프트와 production parser는 실행 전에 변경하지 않았다. 보류 표본의 기존 변환 본문은 production 입력으로 사용했지만, 보류 정답과 이전 결과를 프롬프트 튜닝에 사용하지 않았다.

| 항목 | 값 |
|---|---|
| provider / model / effort | `openai` / `gpt-6-astra` / `medium` |
| action-guide 출력 상한 | 8,192 tokens |
| provider 읽기 제한 | 90초 |
| 요청 저장·캐시 | `store=false`, explicit cache mode |
| 재시도 | 0회, 호출당 1회 |
| 입력·출력 단가 | US$10 / US$50 per 1M tokens ([공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-6-astra), 실행 전 확인) |
| 표본 | 개발 6건 + 보류 4건 |
| 반복 | 문서당 2회, 총 20회 |
| 기준선 | `docs/reports/2026-09-18-easy-read-r0-baseline.md` |
| 프롬프트 변경 | 없음 |

개발 표본은 `070, 087, 023, 072, 077, 088`이고, 보류 표본은 `089, 097, 074, 101`이다. 실행 순서는 각 구분 안에서 이 목록의 순서를 따랐다.

## 예산과 호출 결과

각 호출의 보수 예약액은 다음 식으로 계산했다.

```text
(US$10 × (UTF-8 system bytes + UTF-8 user bytes + 4096)
 + US$50 × 8192) / 1,000,000
```

20회 사전 예약액은 **US$10.185420**으로 계산됐고, 실행 상한도 이 값으로 고정했다. 모든 호출이 끝난 뒤 예약액은 US$10.185420으로 사전 계산과 일치했다. 승인된 누적 US$50 중 보수 예약 기준 잔여액은 US$39.814580이다.

실제 provider 응답의 토큰으로 계산한 예상 비용은 **US$2.880730**이다. 이는 provider observer가 기록한 예상치이며, 확정 청구액이 아니다. 총 입력은 35,148 tokens, 출력은 50,585 tokens였다. provider 실패와 재시도는 모두 0건이었다.

| 구분 | 문서 | 반복 | 결과 | latency ms | input tokens | output tokens |
|---|---:|---:|---|---:|---:|---:|
| 개발 | 070 | 1 | invalid | 31,925 | 1,307 | 2,328 |
| 개발 | 070 | 2 | invalid | 31,874 | 1,319 | 2,366 |
| 개발 | 087 | 1 | valid | 42,138 | 1,794 | 3,278 |
| 개발 | 087 | 2 | valid | 48,767 | 1,822 | 3,668 |
| 개발 | 023 | 1 | invalid | 44,902 | 4,583 | 3,488 |
| 개발 | 023 | 2 | valid | 55,996 | 4,587 | 4,206 |
| 개발 | 072 | 1 | valid | 30,261 | 1,246 | 2,176 |
| 개발 | 072 | 2 | valid | 27,446 | 1,242 | 2,071 |
| 개발 | 077 | 1 | valid | 17,668 | 962 | 1,150 |
| 개발 | 077 | 2 | valid | 17,520 | 978 | 1,119 |
| 개발 | 088 | 1 | invalid | 33,575 | 1,415 | 2,534 |
| 개발 | 088 | 2 | invalid | 32,949 | 1,407 | 2,321 |
| 보류 | 089 | 1 | invalid | 34,955 | 1,351 | 2,366 |
| 보류 | 089 | 2 | invalid | 38,756 | 1,347 | 2,753 |
| 보류 | 097 | 1 | invalid | 53,974 | 2,584 | 3,706 |
| 보류 | 097 | 2 | invalid | 62,942 | 2,568 | 4,787 |
| 보류 | 074 | 1 | valid | 24,911 | 1,071 | 1,666 |
| 보류 | 074 | 2 | valid | 22,322 | 1,083 | 1,519 |
| 보류 | 101 | 1 | valid | 23,030 | 1,241 | 1,342 |
| 보류 | 101 | 2 | valid | 26,948 | 1,241 | 1,741 |

요약 결과는 다음과 같다.

| 구분 | 호출 | valid | invalid | provider 실패 |
|---|---:|---:|---:|---:|
| 개발 | 12 | 7 | 5 | 0 |
| 보류 | 8 | 4 | 4 | 0 |
| 합계 | 20 | 11 | 9 | 0 |

지연 합계는 702,859ms, 일반적인 짝수 표본 중앙값은 **32,437ms**, 최댓값은 62,942ms다. p95는 작은 표본의 분포를 보존하기 위해 nearest-rank 방식으로 계산한 **55,996ms (n=20, 탐색치)**다.

## 후보 검증 실패와 transcript

9개 invalid 결과는 모두 production runner의 동일한 parser 실패로 분류됐다.

```text
parser=행동 안내문 후보 검증에 실패했습니다.
```

실패 문서와 반복은 `070` 1·2회, `023` 1회, `088` 1·2회, `089` 1·2회, `097` 1·2회다. finish reason 이상, 빈 응답, provider 실패는 관찰되지 않았다. 최초 실행에서는 production parser의 공통 메시지만 관찰했으며, 아래 오프라인 진단으로 세부 원인을 분리했다.

### 저장 응답의 오프라인 진단

추가 호출 없이 기존 컴파일된 Kotlin `ActionGuideCandidateParser.decode`, `ActionGuideCandidateValidator.validateStructure/validate`, `findMissingFacts(userText, evidence)`를 보존 응답에 적용했다. 새 검증 하네스·프롬프트·제품 변경은 하지 않았다. 9건 모두 decode·구조·지정 원문 단위의 인용 검증을 통과했고, `available` 항목의 제한된 사실 검사에서 한 항목씩 거절됐다.

| 후보 | 거절된 사실 | 진단 |
|---|---|---|
| 070 r1/r2, eligibility[2] | `한 명` | 원문 `한명`과 후보 `한 명`은 같은 의미지만 `WORD_NUMBER`의 공백 조건으로 추출 결과가 달라진다. |
| 023 r1, exceptions[1] | `10억원` | 선택 원문에는 `10억 이상`이 있다. 금액 검사에서 `억`과 `억원` 표기가 같은 값으로 대응되지 않는다. |
| 088 r1/r2, documents[1]/[0] | `2026` | 문장 안의 파일명에서는 연도가 숫자로 분리되지만, 원문 인용의 줄 단위 파일명에서는 `DOCUMENT_NAME`이 연도를 함께 소비한다. |
| 089 r1/r2, documents[0] | `2026` | 같은 파일명·연도 추출 차이다. |
| 097 r1/r2, documents[0] | `13` | 문장 안의 `[서식 13]`과 원문 줄 단위 문서명의 추출 범위가 다르다. |

따라서 9건의 거절은 이 사실 검사 규칙의 표기·문서명 처리 오탐으로 분류한다. 후보 전체 의미가 옳다는 보증이나 9건의 사람 검수 통과 판정은 아니다. 다음 보완은 잘못된 수치·다른 단위·다른 서식은 계속 거절하는 회귀 사례와 함께 규칙을 좁혀 수정하고, 저장된 응답을 재생해 확인하는 것이다. 이 진단에는 유료 재생성이나 golden 답안 갱신이 필요하지 않았다.

원문 transcript 20개와 측정 조건 파일은 다음 opt-in 디렉터리에 보존했다.

실행 원본은 `/private/tmp/easy-doc-r2-transcript.gfgc11`에 있으며, 재현 가능한 보존본은 [평가 증거 폴더](2026-09-21-r2-model-evaluation-artifacts/README.md)에 복사했다. [manifest](2026-09-21-r2-model-evaluation-artifacts/manifest.json)에서 파일별 SHA-256과 비용 예약 기록을 확인할 수 있다.

이 디렉터리는 실행 조건 `conditions.txt`와 `{document}-run{1|2}.txt` 파일을 포함한다. transcript의 의미 판정은 이 보고서의 후보 검증 결과와 별도로 수행해야 한다. 특히 valid 11건도 사람의 의미 보존·조건·예외·금액·행동·기한 검수를 통과했다는 뜻은 아니다.

[AI 정성 의미 검수](2026-09-21-r2-semantic-review.md)에서 valid 11개를 별도로 대조했다. 독립 사람 평가나 출시 승인을 대신하지 않는다.

## 판정과 남은 통합

production readiness는 **실패**다. 20회 중 9회가 production parser 검증에 실패했으므로 현재 생성·검증 경로를 production-ready로 판정하지 않는다. 진단된 오탐을 수정하고 회귀를 확인한 뒤 별도 출시 판단이 필요하다. 추가 모델 호출이나 프롬프트 튜닝은 이 평가에 포함하지 않았다.

이번 lane은 production action-guide runner와 parser를 직접 실행하지만, 다음 항목은 평가 범위 밖이다.

- valid 후보의 의미 정확성에 대한 사람 검수
- action-guide DB 저장·job worker·credit ledger의 실제 통합 경로
- API/frontend 표시와 사용자 독해 검증

fake runner의 1회 호출·8,192-token bound·invalid/truncated/provider-failure 테스트, R2 lane 계획 테스트, 보고서의 반복별 결과·p95 테스트, `ktlintCheck`, `detekt`는 통과했다. 유료 `testLlm` task 자체는 invalid 9건을 실패로 보고하도록 되어 있어 종료 코드는 실패였으며, provider·예산·호출 상한 실패는 아니었다.

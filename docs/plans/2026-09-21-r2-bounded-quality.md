# R2 제한 품질 평가·수용 계획

작성일: 2026-09-21. 상태: 회차·상한 고정. [실행 결과와 비용 원장](../reports/2026-09-21-r2-bounded-quality.md)을 별도로 기록한다.

이 문서는 R2 행동 안내문을 최대 세 번의 개선·평가 라운드 안에서 확인하는 실행 계약이다. 실제 호출과 비용 원장은 평가 담당자가 관리한다. R3 유료 평가는 별도 범위 확인 전 이 실행 계획에 포함하지 않는다.

## 출발점과 범위

최초 R2는 기존 Kotlin `testLlm` 레인에서 개발 표본 6건과 보류 표본 4건을 문서당 두 번씩 실행했다. 보수적 예약액은 **US$10.185420**이었고, 최초 결과는 후보 검증 통과 11건·거절 9건이었다. 9건은 `한 명`/`한명`, `10억원`/`10억`, 문장 안 파일명의 연도·서식 번호를 문서명 추출이 삼키는 좁은 사실 검사 오탐으로 진단됐다. 보존된 20개 응답의 오프라인 재생은 현재 수정된 validator에서 20/20 통과했지만, 이 결과는 새 생성 품질이나 사람의 의미 승인을 뜻하지 않는다.

개선 평가의 개발 표본은 R0 고정 표본의 다음 6건으로 고정한다.

| 순서 | 문서 ID | 유형 |
|---:|---:|---|
| 1 | `070` | 조건 중심 |
| 2 | `087` | 조건 중심 |
| 3 | `023` | 절차·금액 중심 |
| 4 | `072` | 절차 중심 |
| 5 | `077` | 금액·조건 중심 |
| 6 | `088` | 조건·절차 중심 |

각 라운드는 위 6건을 같은 조건으로 두 번 생성한다. 따라서 개발 평가의 최대치는 라운드당 **12호출**이다. 문서를 빼거나 성공한 반복만 골라 합격률을 올리지 않는다. 다른 agent가 담당하는 action-guide 프롬프트와 R3 generic prompt/dictionary 변경은 각 라운드의 후보 버전과 해시로만 기록하고, 이 계획에서 직접 수정하지 않는다.

보류 표본 `089, 097, 074, 101`은 최초 R2에 이미 사용됐다. 이후 결과를 프롬프트 조정에 참고했다면 새 독립 보류 표본으로 부르지 않는다. 개발 라운드가 수용된 뒤 같은 4건을 두 번씩 다시 실행할 수 있으나, 그것은 **회귀 확인**일 뿐 독립적인 사후 품질 주장이 아니다.

## 라운드와 예산

| 단계 | 표본·반복 | 호출 상한 | 보수 예약 상한 | 목적 |
|---|---|---:|---:|---|
| 개발 1 | 6건 × 2회 | 12 | US$7.000000 | 현재 후보의 첫 확인 |
| 개발 2 | 6건 × 2회 | 12 | US$7.000000 | 개발 1 실패 원인에 대한 한 가지 국소 개선 확인 |
| 개발 3 | 6건 × 2회 | 12 | US$7.000000 | 마지막 허용 개선 확인 |
| 선택 회귀 | `089,097,074,101` × 2회 | 8 | US$5.000000 | 개발 수용 후보의 회귀만 확인 |

개발 라운드를 모두 사용해도 기존 예약을 포함한 개발 누적 예약은 `10.185420 + 3 × 7 = US$31.185420` 이하이다. 개발 후보가 수용되어 선택 회귀를 실행하는 경우에도 총 보수 예약은 **US$36.185420 이하**다. 선택 회귀는 네 번째 개선 라운드로 세지 않으며, 개발 수용 전에 시작하지 않는다. 실제 사용액이 예약액보다 작아도 다음 라운드의 상한을 임의로 늘리지 않는다.

각 호출의 예약은 현재 레인의 보수식을 그대로 적용한다.

```text
(입력 단가 × (UTF-8 system bytes + UTF-8 user bytes + 4,096)
 + 출력 단가 × 8,192) / 1,000,000
```

R2 기록의 조건은 `openai / gpt-6-astra / medium`, 입력 US$10·출력 US$50 per 1M tokens, action-guide 출력 상한 8,192 tokens, 읽기 제한 90초, 호출당 1회(재시도 0회)다. 후보가 이 조건을 바꾸면 호출 전에 새 model·effort·상한·단가와 예약 합계를 원장에 다시 고정한다. 12건의 합산 예약이 US$7.000000을 넘으면 provider 호출을 시작하지 않는다.

## 사전 게이트

평가 담당자는 각 라운드의 첫 호출 전에 다음을 한 번에 원장에 남긴다.

1. 평가 worktree의 `git rev-parse HEAD`, dirty 파일 목록, 프롬프트·사전·모델 설정 식별자와 SHA-256을 기록한다. 현재 준비된 격리 worktree가 있다면 `/private/tmp/easy-doc-r2-three-round-evaluation`의 실제 HEAD와 변경 목록을 먼저 확인한다.
2. R0 원문·저장 본문의 해시가 [R0 기준선](../reports/2026-09-18-easy-read-r0-baseline.md) 및 현재 lane의 입력과 일치하는지 확인한다. `docs/reports/2026-09-21-r2-model-evaluation-artifacts`의 frozen transcript, `manifest.json`, `conditions.txt`는 읽기 전용으로 취급하고 덮어쓰지 않는다.
3. 6개 문서, 문서당 2회, 계획 호출 12건, `MAX_CALLS=12`, 개발 상한 US$7.000000을 확인한다. `planned_reserved_usd`가 cap 이하라는 레인의 출력이 없으면 실행하지 않는다.
4. 기존 R0 개발 6건의 Q1–Q6을 두 생성 반복에 각각 대조한다. 고정 질문은 36개이고 **6문서 × 6질문 × 2반복 = 72개 판정**이다. 질문·정답·원문 근거를 새 응답에 맞춰 바꾸지 않는다.
5. 다음 오프라인 검사를 paid preflight보다 먼저 실행한다. 이 검사는 provider 객체를 호출하지 않는다.

   ```bash
   cd backend-kotlin
   ./gradlew --no-daemon :infrastructure:test \
     --tests kr.easydoc.infrastructure.quality.ActionGuideR2LaneTest \
     --tests kr.easydoc.infrastructure.quality.ActionGuideR2LaneReportTest \
     --tests kr.easydoc.infrastructure.quality.ActionGuideR2OfflineRegressionTest
   ```

6. `EASYDOC_R2_LANE_PREFLIGHT_ONLY=true`로 동일한 12건 계획을 한 번 더 해석해 호출 수·예약액·provider·model·effort·출력 상한·읽기 제한을 stdout에서 확인한다. 비밀값과 transcript 본문은 출력하지 않는다. preflight를 통과한 설정과 실제 호출 설정이 다르면 해당 라운드를 무효로 하고 비용 원장에 남긴다.

## 실행 명령과 안전한 환경

기존 레인의 `ActionGuideR2EvaluationTest`만 선택한다. 루트 전체 `testLlm`을 실행하면 다른 LLM 골든 테스트가 함께 열려 이 계획의 호출·예산 상한을 보장할 수 없다.

개발 라운드의 환경은 다음 의미를 가져야 한다.

```text
EASYDOC_R2_LANE_ENABLED=true
EASYDOC_R2_LANE_COHORT=development
EASYDOC_R2_LANE_DOCUMENTS=070,087,023,072,077,088
EASYDOC_R2_LANE_RUNS=2
EASYDOC_R2_LANE_MAX_CALLS=12
EASYDOC_R2_LANE_MAX_USD=7.000000
EASYDOC_R2_LANE_PREFLIGHT_ONLY=true  # preflight 때만 true
EASYDOC_R2_LANE_TRANSCRIPT_DIR=<round-specific opt-in directory>
EASYDOC_LLM_PROVIDER=openai
EASYDOC_LLM_MODEL=gpt-6-astra
EASYDOC_LLM_EFFORT=medium
EASYDOC_LLM_INPUT_USD_PER_MILLION_TOKENS=10
EASYDOC_LLM_OUTPUT_USD_PER_MILLION_TOKENS=50
```

실제 실행에서는 preflight flag만 false로 바꾼 뒤 아래처럼 같은 테스트를 한 번 실행한다. `OPENAI_API_KEY`는 선택한 provider에만 안전하게 주입하고, 셸 추적·로그·보고서에 값을 남기지 않는다.

```bash
cd backend-kotlin
./gradlew --no-daemon :infrastructure:testLlm \
  --tests kr.easydoc.infrastructure.quality.ActionGuideR2EvaluationTest
```

다음 실행 안전 규칙을 지킨다.

- 프롬프트를 격리 checkout에 고정해 병렬 제품 수정과 분리한다. Gradle은 소스·입력 지문으로 컴파일 결과를 추적하며, 공유 checkout에서 clean으로 다른 작업의 산출물을 지우지 않는다.
- 기존 `testLlm`의 `outputs.upToDateWhen { false }`가 실제 평가를 생략하지 않도록 한다. 실행 조건 파일과 호출 수를 확인하고, 테스트를 다시 실행하면 새 유료 평가라는 점을 원장에 반영한다.
- 한 번에 한 Gradle paid task만 실행한다. 네트워크 timeout·provider 오류·malformed output은 재시도하지 않고 해당 호출 및 예약액을 원장에 남긴다. 별도 수동 재실행은 새 호출로 세고 cap을 다시 확인한다.
- `EASYDOC_R2_LANE_ENABLED`가 `false`이면 실행이 skip되고, `EASYDOC_LLM_PROVIDER=fake`이면 레인이 품질 측정으로 거절해야 한다. skip·fake 결과를 paid 품질 결과로 집계하지 않는다.
- 지정된 `:infrastructure:testLlm --tests ...ActionGuideR2EvaluationTest`만 실행한다. 전체 `testLlm`, 일반 변환 레인, 병렬 provider 작업을 함께 열지 않는다.
- API key와 사용자 문서를 명령 추적·표준 출력·커밋에 내보내지 않는다. 공개 골든 원문 기반 transcript는 명시한 opt-in 디렉터리에 저장한 뒤 평가 증거 경로에만 보존하고 해시와 보존 경로를 원장에 기록한다.

선택 회귀는 개발 수용 판정 뒤 별도 invocation으로 `COHORT=holdout`, `DOCUMENTS=089,097,074,101`, `RUNS=2`, `MAX_CALLS=8`, `MAX_USD=5.000000`을 사용한다. 같은 환경 안전 규칙과 0회 재시도를 적용하고, 결과 제목에 `regression-only / not independent holdout`을 붙인다.

## 라운드별 수용 기준

각 후보 반복을 다음 순서로 판정한다.

| 게이트 | 통과 조건 | 실패 처리 |
|---|---|---|
| 호출·인프라 | 계획 12건, 실제 호출 ≤12건, provider 실패 0, 재시도 0, cap 초과 0, 각 응답의 종료·지연 조건 기록 | 라운드 실패. 빠진 호출을 성공으로 보충하지 않음 |
| production 구조·사실 검사 | 12개 모두 decode, 구조, source-unit anchor, 기존 production fact validator 통과 | 하나라도 invalid면 개발 수용 불가. 오탐·실제 불일치를 구분해 기록 |
| R0 의미 대조 | 고정된 R0 all72 Q에서 자격·예외·금액·행동·기한의 **중대한 의미 변경 0건** | 하나라도 확인되면 후보 거절. 표현 난이도와 의미 변경을 분리 |
| 핵심 중단 사례 | `088-Q5` 두 반복 모두 `비급여 포함` 사용 범위를 명시적으로 보존 | 둘 중 하나라도 누락이면 즉시 해당 후보 거절. 의미 보완을 validator 통과로 대신하지 않음 |
| 모호성 보존 | 기존 `needs_review`/`indeterminate` 표시는 그대로 유지하고, 원문이 결정하지 못한 내용을 임의로 확정하지 않음 | 임의 확정은 원문 밖 추가 또는 의미 변경으로 기록 |

`비급여 포함`은 088 원문의 `본인부담금(비금여 포함)`이 요구하는 사용 범위다. Q5 답변이 본인부담금·차감만 말하고 이 범위를 생략하면 두 반복 모두 의미상 누락으로 판정한다. 검증기 12/12 통과만으로 Q5를 통과 처리하지 않는다.

현재 정성 검수에서 확인한 ambiguity flag는 그대로 유지한다. 예를 들면 089의 소득 구간 충돌, 097의 유형별 기준·다자녀 순위 모호함, 101의 법령명·25% 기준 연결, 023·070·072·074·077의 원문에 없는 연락처·서류·기한·단계, 088의 원문 오타와 Q5 범위가 있다. 원문이 답을 결정하지 못한 항목은 `indeterminate`/`needs_review`로 남기며 성공으로 채우지도 오류로 소급하지도 않는다. 반대로 원문과 반대되는 자격·제외·금액·행동·기한은 모호성 flag로 숨기지 않는다.

### 조기 종료와 라운드 전환

개발 라운드가 아래 세 조건을 동시에 만족하면 더 이상의 R2 개선 라운드를 실행하지 않는다.

1. 088-Q5의 두 반복이 모두 `비급여 포함`을 보존한다.
2. 개발 12개 응답이 모두 production 구조·anchor·fact validator를 통과한다.
3. R0 all72 Q 대조에서 중대한 의미 변경이 0건이다.

이때 개발 후보를 수용한 뒤 선택 회귀를 실행할 수 있다. 선택 회귀의 결과는 회귀 여부만 말할 수 있고 독립 post-tune holdout 성능으로 보고하지 않는다. 개발 게이트를 통과하지 못하면 회귀를 먼저 실행하지 않는다.

실패한 라운드는 성공으로 표시하지 않고 실패 원인, 문서·반복, 질문 ID, source 근거, 다음 라운드에서 바꿀 **한 가지** 개선 축을 기록한다. 세 번째 개발 라운드 뒤에도 세 조건을 만족하지 못하면 추가 R2 라운드를 만들지 않고 `bounded evaluation failed / unresolved`로 종료한다. R3 paid sharing에 대한 별도 답변·승인이 있기 전에는 R3 유료 호출을 이 계획에서 시작하지 않는다.

## 호출 원장과 증거

평가 담당자는 라운드마다 다음 열을 채운다. 비용은 실제 관측액과 별도로 **예약액 기준**으로 누적한다.

| 열 | 내용 |
|---|---|
| round / cohort / document / run | `dev-1..3`, 또는 `regression`; 문서 ID; 1·2회 |
| source/body SHA-256 | R0 원문과 저장 본문 입력 지문 |
| prompt/dictionary/config SHA-256 | 이번 후보의 변경 축과 실행 설정 |
| provider/model/effort | 실제 레인이 조립한 값 |
| max tokens / timeout / retry | 8,192 / 90초 / 0회 등 |
| planned reserved USD | 호출 시작 전 레인의 보수 예약액 |
| actual tokens / estimated USD | 실행 뒤 observer 값; 확정 청구액과 구분 |
| validator / semantic result | 12개 기술 결과, Q별 판정과 근거 |
| ambiguity / action | `needs_review`, `indeterminate`, 유지·거절·다음 개선 |

실행 조건·stdout 요약·호출 원장·transcript manifest·R0 질문 manifest 해시는 라운드별 새 opt-in 증거 디렉터리에 보관한다. 기존 frozen R2 artifacts를 수정하거나 golden answer를 갱신하지 않는다. 보고서에는 “validator 통과”, “AI 의미 대조”, “사람 파일럿”, “독립 holdout”을 서로 다른 결과로 적는다.

## 완료 판정

이 계획의 문서 완료는 명령을 실행했다는 뜻이 아니다. 평가 담당자가 최대 세 개발 라운드와 선택 회귀의 예약·실제 호출·질문별 근거를 원장에 남기고, 위 수용 기준에 따라 다음 중 하나를 기록해야 한다.

- `accepted`: 088-Q5 두 반복 보존, 개발 12/12 validator 통과, R0 all72 Q 중대한 의미 변경 0건.
- `accepted_with_regression_findings`: 위 개발 수용을 만족했으나 선택 회귀에서 별도 확인점이 남음. 독립 holdout 주장은 하지 않음.
- `bounded_evaluation_failed`: 세 라운드 안에 수용 조건을 만족하지 못했거나 분모·입력 해시·비용 원장이 확인되지 않음.

실제 호출·사람 파일럿·운영 배포 승인은 이 계획 자체로 대신하지 않는다.

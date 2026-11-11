# 쉬운 글 그림으로 설명 개선 실행 계획

작성일: 2026-11-07 · 상태: **P0-2·P1·P2 구현·자동/fake 검증 완료(§10), P0-1 실제 provider 3차 평가까지 불통과(§12, 보고서 2026-11-11)·파일럿 확대 보류** · 코드 기준: `47cc2858`

이 문서는 쉬운 글 변환 뒤 **그림 제안 → 사용자 명시 요청에 따른 비동기 생성 → 검토 → 적용** 흐름의
리뷰 결과를 실행 순서로 정리한다. 현재 제안·생성 기능은 기본 OFF이며 관리자 파일럿으로 제한한다
(`backend-kotlin/api/src/main/resources/application.yml:105`, `:115`,
`frontend/e2e/illustration-suggestions.spec.ts:52`). fake/0크레딧 검증 통과는 실제 provider 품질·출시 완료 증거가 아니다.

## 1. 목표와 비목표

### 목표

- 설정 오류나 기능 토글 때문에 기존 생성 자산이 사라진 것처럼 보이지 않게 하고, 문서 전환·폴링·실패 상태를 안전하게 복구한다.
- 사용자가 생성 결과의 원 제안과 원문 근거를 대조하고, 대체텍스트와 후보를 검토한 뒤에만 적용하게 한다.
- 이미지 로딩·입력·초점·중복 낭독·반복 다운로드 문제를 개선한다.
- 실제 provider의 의미 정확성, 이해 도움, 대체텍스트 품질, 지연, 실제 비용을 측정한 뒤에만 파일럿 확대를 판단한다.

### 비목표

- 이 문서 작성 단계에서 제품 코드·계약·테스트를 변경하거나 실제 유료 provider를 호출하지 않는다.
- 기본 변환 품질, DOCX/HWPX/TXT 그림 삽입, 새 평가 하네스나 Python 구현은 다루지 않는다.
- fake/0크레딧 E2E 통과를 실제 이미지 품질이나 출시 승인으로 대체하지 않는다.
- 읽기 경로의 `FOR NO KEY UPDATE`를 측정 없이 제거하지 않는다. 이는 선택 과제로 분리한다.

## 2. 유지할 현재 강점

- **제안과 생성 분리:** 제안은 텍스트 검토 단계이고, 이미지 생성은 별도의 명시적 요청과 비동기 작업으로 실행한다.
- **재현 가능한 요청 경계:** `request_id`, `expected_content_revision`, 제안 분석 버전과 구성 해시로 멱등성과 입력 일치를 검사한다
  (`backend-kotlin/application/src/main/kotlin/kr/easydoc/application/illustration/generation/IllustrationGenerationJobService.kt:76`).
- **stale·CAS 안전장치:** 본문·제안·자산 revision을 비교하고, 생성 완료가 본문 적용을 뜻하지 않는다
  (`contracts/easy-doc-v1.yaml:5330`).
- **이용량 정산:** 생성 전 예약하고 성공 시 소비하며, 확정 실패·stale·결과 불명확에서는 반환한다
  (`backend-kotlin/application/src/main/kotlin/kr/easydoc/application/illustration/generation/ProcessIllustrationGenerationJob.kt:133`).
- **비공개 PNG:** 인증된 소유자만 이미지와 다운로드를 조회하고 응답을 비공개로 처리한다
  (`backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/IllustrationGenerationController.kt:92`).
- **검토 후 적용:** 결과 카드에서 원문 근거·대체텍스트를 확인하고 적용하며, 적용 제거와 자산 삭제를 혼동하지 않는다
  (`frontend/src/components/IllustrationGenerationPanel.tsx:426`).

아래 개선은 이 안전장치를 약화하지 않는 것을 전제로 한다.

## 3. 우선순위와 과제

P0는 출시 차단 여부, P1은 데이터 손실처럼 보이는 동작과 핵심 사용성, P2는 접근성·효율이다.
우선순위와 실행 순서는 다르다. 특히 P0 실제 provider 평가는 준비를 먼저 하고 호출은 마지막 단계에서 한다.

### P0-1. 실제 provider 품질·비용·지연 출시 게이트

- **문제:** 현재 대표 E2E는 fake worker와 0크레딧 스택을 사용한다
  (`frontend/e2e/illustration-suggestions.spec.ts:52`). 실제 provider의 의미 정확성, 독자 이해 도움,
  대체텍스트 품질, 지연, 계정 권한과 실제 청구 비용은 검증하지 않았다.
- **개선 작업:** §6의 표본과 평가 절차를 확정하고, fake 검증과 분리된 승인된 실제 호출로 측정한다.
  제안 적절성과 생성 이미지 품질을 나누어 기록하고, 실제 사용량·청구 내역과 호출 원장의 추정치를 대조한다.
  평가 전에 표본 수, 평가자, 채점 기준, 허용 오류, 지연·비용 통과 기준과 중단 규칙을 문서화한다.
- **관련 파일:** `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/illustration/generation/OpenAiImageGenerationProvider.kt`,
  `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/illustration/generation/GeneratedPngValidator.kt`,
  `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/illustration/generation/ProcessIllustrationGenerationJob.kt`,
  `frontend/e2e/illustration-suggestions.spec.ts`.
- **완료 기준:** 사전에 확정한 모든 통과 기준을 실제 측정 결과로 판정하고, 실패·무제안 사례와 비용까지 포함한
  평가 기록으로 파일럿 유지·축소·확대를 결정한다. 기준 미달이나 미측정 항목이 있으면 확대하지 않는다.
- **검증:** 승인된 유료 평가의 원시 결과와 집계, 실제 비용·지연 확인, 사람 검토 기록. fake E2E는 별도 회귀 증거로만 쓴다.
- **계약:** 현재 평가만으로는 공개 HTTP 계약 변경이 필요 없다. 평가를 위해 새 외부 필드를 추가한다면 별도 계약 단위로 다시 승인한다.

### P0-2. 개인정보 문안과 이미지 요청의 `store=false` 불일치 해소

- **문제:** 개인정보 처리방침은 모든 요청에 저장 안 함(`store=false`)을 지정한다고 말하지만
  (`frontend/src/content/legal/privacy-policy.md:139`), 실제 이미지 요청 본문에는 `model`, `prompt`, `n`,
  `size`, `quality`, `output_format`만 있다
  (`backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/illustration/generation/OpenAiImageGenerationProvider.kt:92`).
- **개선 작업:** 현재 Images API와 계정 조건에서 지원 여부를 확인한다. 지원하면 provider 요청에 `store=false`를
  추가하고 stub 서버의 요청 본문 테스트로 고정한다. 지원하지 않거나 계정에 적용할 수 없으면 실제 동작에 맞게
  문안과 전송·보존 설명을 수정하고 법무 검토를 거친다. 문구만 고치고 실제 요청을 확인하지 않은 채 넘어가지 않는다.
- **관련 파일:** 위 provider와 `frontend/src/content/legal/privacy-policy.md`,
  `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/illustration/generation/OpenAiImageGenerationProviderTest.kt`.
- **완료 기준:** 처리방침의 문장과 실제 전송 본문·적용 계정 조건이 일치하고, 그 근거를 검토 기록에 남긴다.
  **실제 provider 활성화 전 반드시 완료한다.**
- **검증:** provider stub 요청의 정확한 필드·값 테스트, 문서 검토, 관련 백엔드 build. 실제 호출이 필요한 계정 조건 확인은 별도 승인 하에 수행한다.
- **계약:** 서비스의 공개 HTTP 계약 변경은 없다. 외부 provider 요청과 법정 문안의 정합성 작업이다.

### P1-1. 자산 읽기·미리보기와 신규 제안/생성 capability 분리

- **문제:** 생성 목록 응답의 `enabled`가 false이면 프런트가 기존 자산 조회 자체를 건너뛴다
  (`frontend/src/components/IllustrationGenerationPanel.tsx:75`). 서버의 `enabled`도 새 작업 접수에 필요한
  토글·고객 차감량·provider 가격 설정을 함께 본다
  (`backend-kotlin/application/src/main/kotlin/kr/easydoc/application/illustration/generation/IllustrationGenerationJobService.kt:230`).
  또한 생성 패널은 제안 패널 안에서만 그려지고, 제안 capability가 전체 선택 그림 영역과 미리보기를 좌우한다
  (`frontend/src/components/IllustrationSuggestionsPanel.tsx:587`, `frontend/src/components/ReviewEditor.tsx:1887`).
  따라서 가격 설정 오류, 생성 OFF 또는 제안 OFF가 기존 자산을 숨길 수 있다.
- **개선 작업:** 신규 요청 가능 여부와 기존 작업·자산 읽기 가능 여부를 나눈다. 읽기 경로는 생성 접수 가격 설정과
  무관하게 유지하고, `ReviewEditor`는 생성 읽기 capability와 적용된 자산 미리보기를 제안 패널에서 독립시킨다.
  신규 생성·재생성은 계속 생성 접수 capability로 막는다. 제안 읽기와 신규 제안 접수도 같은 원칙으로 검토한다.
- **관련 파일:** `contracts/easy-doc-v1.yaml`, 위 생성·제안 패널, `ReviewEditor.tsx`,
  `IllustrationGenerationJobService.kt`, `GeneratedIllustrationAssetService.kt`,
  `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/IllustrationGenerationController.kt`,
  `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/IllustrationGenerationContractTest.kt`,
  `frontend/src/api/types.ts`, `frontend/src/api/client.ts`.
- **완료 기준:** 가격 설정이 잘못돼도 기존 자산·적용 미리보기를 읽고 새 생성만 차단된다. 제안 OFF+생성 읽기 ON에서도
  기존 생성 결과를 볼 수 있고, 읽기 OFF에서는 소유자 404 등 기존 접근 정책을 유지한다.
- **검증:** capability 조합별 API 계약 테스트, `IllustrationGenerationPanel.test.tsx`,
  `ReviewEditorIllustrations.test.tsx`, 기존 자산 유지 E2E.
- **계약:** **필요.** `ReviewCapabilities`와 생성/제안 응답 중 어디에 read/create capability를 둘지 결정한 뒤
  YAML + Kotlin DTO/계약 테스트 + `frontend/src/api/`를 한 변경 단위로 맞춘다.

### P1-2. 제안 접수 응답과 문서 전환 경합 제거

- **문제:** 제안 접수 `submitCreate`는 응답 후 현재 문서·요청 범위를 다시 확인하지 않고 상태를 쓴다
  (`frontend/src/components/IllustrationSuggestionsPanel.tsx:376`). `ConversionPage`는 완료된 문서를 같은
  `ReviewEditor` 경로로 렌더링하므로(`frontend/src/pages/ConversionPage.tsx:148`), 늦은 이전 문서 응답이
  새 문서의 작업·이용량·초점 상태를 덮을 수 있다.
- **개선 작업:** 접수마다 conversion ID와 시도 번호를 묶고, 문서 전환·언마운트 시 요청을 abort하며 응답 시에도
  현재 범위와 일치하는지 검사한다. 복구 조회·폴링과 같은 범위 규칙을 쓰고, 이전 문서 응답은 무시하되 서버 작업은
  취소한 것처럼 표시하지 않는다. 새 문서는 자체 목록 조회로 상태를 복구한다.
- **관련 파일:** `IllustrationSuggestionsPanel.tsx`, `IllustrationGenerationPanel.tsx`, `ReviewEditor.tsx`,
  `frontend/src/pages/ConversionPage.tsx`.
- **완료 기준:** 접수 중 A→B 문서 전환 뒤 A의 성공·실패 응답이 와도 B의 제안·생성·이용량·초점에 영향이 없고,
  A 재방문 시 서버의 진행 작업을 복구한다.
- **검증:** 지연 응답을 제어하는 컴포넌트 회귀 테스트와 문서 전환 Playwright. 요청 중복·자동 재접수가 없음도 확인한다.
- **계약:** 필요 없다. 기존 응답과 `request_id` 의미를 유지한다.

### P1-3. 영구 오류와 일시 오류를 구분한 폴링 종료·복구

- **문제:** 제안 폴링은 모든 조회 예외에서 다시 예약하고
  (`frontend/src/components/IllustrationSuggestionsPanel.tsx:287`), 생성 폴링도 401·404·5xx를 구분하지 않는다
  (`frontend/src/components/IllustrationGenerationPanel.tsx:110`). 인증 만료나 없는 작업에서 무한 요청이 이어진다.
- **개선 작업:** 401·404 등 영구 오류는 즉시 폴링을 멈추고 재인증 또는 문서 상태 확인으로 유도한다. 5xx·네트워크
  오류는 겹치지 않는 제한적 backoff와 중단 조건을 두고 수동 새로고침을 제공한다. abort와 문서 전환은 오류로 알리지 않는다.
  폴링을 멈춰도 서버 작업 취소나 크레딧 반환을 단정하지 않는다.
- **관련 파일:** 위 두 패널과 `frontend/src/api/client.ts`, 각 패널의 기존 테스트.
- **완료 기준:** 상태 코드별 폴링 중지·재시도 동작이 일관되고, 화면에 마지막으로 확인한 상태와 다음 행동이 남는다.
- **검증:** 제안·생성 각각 401/404/5xx, 네트워크 오류, abort, 문서 전환 테스트; 추가 요청 횟수와 재개 동작 확인.
- **계약:** 필요 없다. 기존 오류 코드를 소비하는 방식만 바꾼다.

### P1-4. 생성 실패·크레딧 반환·402·10건 한도 안내 완성

- **문제:** 백엔드는 확정 실패에서 예약 이용량을 반환하지만
  (`ProcessIllustrationGenerationJob.kt:133`), 화면은 `generation_failed`와 `result_invalid`를 구분하지 않고
  결과 불명확도 짧게만 안내한다(`frontend/src/components/IllustrationGenerationPanel.tsx:413`). 402는
  “이용량이 부족합니다”에 그치고, 문서당 10건 한도와 실패·결과 불명확 포함 규칙은 409 뒤에야 드러난다
  (`IllustrationGenerationPanel.tsx:258`).
- **개선 작업:** `generation_failed`, `result_invalid`, `outcome_unknown`, `superseded`별로 원인, 이용량 처리,
  다음 행동을 나눈다. 반환 여부는 서버 작업·원장 상태로 확인한 뒤 표시한다. 402에서는 필요한 양·가용량과 기존
  이용량/충전 화면 이동을 제공한다. 생성 전 현재 사용 요청 수·남은 수·최대 10건과 재생성도 새 요청이라는 점을 알린다.
  자동 provider 재호출이나 자동 재접수는 추가하지 않는다.
- **관련 파일:** `IllustrationGenerationPanel.tsx`, `IllustrationGenerationJobService.kt`,
  `ProcessIllustrationGenerationJob.kt`, `contracts/easy-doc-v1.yaml`,
  `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/IllustrationGenerationController.kt`,
  `frontend/src/api/types.ts`, `frontend/src/api/client.ts`.
- **완료 기준:** 각 terminal failure에서 문구·반환 상태·재생성 동작이 서버 상태와 일치한다. 402에서 다음 행동으로
  이동할 수 있고, 한도 전·도달·초과 시 같은 10건 규칙을 설명한다. 실패해도 기존 자산과 본문은 유지된다.
- **검증:** `ProcessIllustrationGenerationJobTest.kt`의 정산 회귀와 생성 패널의 failure/402/한도 테스트,
  API 계약 테스트, terminal failure E2E.
- **계약:** 실패 문구와 이동만으로는 필요 없다. **사용 요청 수·남은 수·한도 등 사용량/한도를 새로 노출하는 부분은
  계약 변경**이므로 YAML + Kotlin DTO/계약 테스트 + `frontend/src/api/`를 함께 변경한다.

### P1-5. 생성 후 검토: 원 제안 연결, 대체텍스트 수정, 후보 폐기

- **문제:** 결과 카드는 대체텍스트와 원문 인용을 보여 주지만 제안 목적·이유·장면·보존 사실과 원 제안 카드로
  돌아가는 연결이 부족하다(`frontend/src/components/IllustrationGenerationPanel.tsx:426`). 생성 후 대체텍스트
  수정이나 원하지 않는 후보 폐기 계약도 없다. 현재 적용 제거는 자산 삭제나 크레딧 반환이 아니다.
- **개선 작업:** 우선 기존 응답의 `suggestion_id`, 목적·이유·장면·보존 사실·본문 범위를 결과 카드에 표시하고
  원 제안 위치로 이동하게 한다. 사용자가 도입을 승인하면 대체텍스트는 `expected_asset_revision` CAS로 수정하고
  재방문 후에도 유지한다. 후보 폐기는 미적용 후보를 목록·미리보기에서 제외하는 동작으로 정의하고, 물리 삭제 여부와
  보존 정책을 명시한다. 폐기·미적용·제거는 이미 소비한 크레딧을 반환하지 않는다. 생성 당시 `configuration_revision`은
  provenance로 보존하고 생성 후 대체텍스트 수정으로 다시 계산한 값처럼 표시하지 않는다.
- **관련 파일:** `IllustrationGenerationPanel.tsx`, `GeneratedIllustrationPreview.tsx`, `ReviewEditor.tsx`,
  `GeneratedIllustrationAssetService.kt`, `IllustrationGenerationJobService.kt`,
  `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/IllustrationGenerationController.kt`,
  `contracts/easy-doc-v1.yaml`, `frontend/src/api/types.ts`, `frontend/src/api/client.ts`.
- **완료 기준:** 사용자가 결과와 원 제안·원문 근거를 함께 대조할 수 있다. 수정·폐기를 도입한 경우 저장 후 재조회,
  CAS 충돌, stale 자산, 폐기 후 미리보기 제외와 크레딧 불반환이 일관된다. 도입하지 않으면 저장되지 않는 편집 버튼이나
  삭제처럼 보이는 제거 버튼을 추가하지 않는다.
- **검증:** 컴포넌트·서비스·계약 테스트와 새로고침/충돌/폐기 E2E, 키보드·스크린리더 검토.
- **계약:** 기존 필드의 추가 표시는 계약 변경이 없다. **대체텍스트 수정·후보 폐기는 도입 시 계약 변경**이며
  YAML + Kotlin DTO/계약 테스트 + `frontend/src/api/`를 한 단위로 구현한다.

### P1-6. 대체텍스트 300자 기준을 Unicode 코드 포인트로 통일

- **문제:** 제안 대체텍스트는 코드 포인트 300을 검사하지만
  (`backend-kotlin/core/src/main/kotlin/kr/easydoc/core/illustration/suggestion/IllustrationSuggestionValidator.kt:133`),
  생성 구성과 프런트는 Kotlin/JavaScript `length`의 UTF-16 코드 단위 300을 사용한다
  (`backend-kotlin/core/src/main/kotlin/kr/easydoc/core/illustration/generation/IllustrationGenerationJob.kt:80`,
  `frontend/src/components/IllustrationGenerationPanel.tsx:180`). emoji가 많으면 제안과 생성이 다르게 거절한다.
- **개선 작업:** 제안·생성·프런트 입력을 모두 300 Unicode 코드 포인트로 맞추고 화면 남은 글자 수도 같은 규칙으로 센다.
  구성 해시의 canonical 직렬화는 계약상 UTF-16 길이를 사용하므로 바꾸지 않는다. 의미 정확성은 길이 검사와 분리한다.
- **관련 파일:** 위 두 Kotlin 파일, `IllustrationGenerationPanel.tsx`, `frontend/src/api/illustrationConfiguration.ts`,
  `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/illustration/generation/IllustrationGenerationTest.kt`,
  `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/illustration/suggestion/IllustrationSuggestionParserTest.kt`.
- **완료 기준:** emoji 151~300개와 한국어+emoji 조합이 제안·생성에서 같은 결과를 받고, 301 코드 포인트는 거절된다.
  기존 canonical SHA-256 벡터는 유지된다.
- **검증:** 경계값 단위 테스트와 프런트 입력/서버 수락 회귀.
- **계약:** endpoint나 schema 형태 변경은 없다. 기존 `maxLength: 300`의 코드 포인트 의미에 구현을 맞추는 버그 수정이며,
  설명을 명확히 할 때도 해시 직렬화 규칙과 혼동하지 않는다.

### P2-1. 이미지 로딩·입력 오류·초점·중복 낭독 접근성

- **문제:** 이미지 컴포넌트는 로딩 문구와 실패 재시도만 제공하고
  (`frontend/src/components/GeneratedIllustrationImage.tsx:16`), 패널의 일부 입력·오류·동적 상태는 초점과 오류 연결이
  불충분하다. 적용 미리보기는 같은 대체텍스트를 `img alt`와 `figcaption`에 둘 다 넣어 중복 낭독할 수 있다
  (`frontend/src/components/GeneratedIllustrationPreview.tsx:48`).
- **개선 작업:** 로딩은 적절한 live status로, 실패는 오류 설명과 연결된 재시도로 제공한다. 생성·수정 입력에 label,
  설명, 오류 ID를 연결하고 접수·실패·문서 전환 뒤 초점 위치를 검토한다. `figcaption`이 이미지와 같은 설명이면
  `alt=""`로 중복을 피하고, 서로 다른 정보를 담는 경우에만 각각의 이름을 유지한다.
- **관련 파일:** `GeneratedIllustrationImage.tsx`, `GeneratedIllustrationPreview.tsx`,
  `IllustrationGenerationPanel.tsx`, `IllustrationSuggestionsPanel.tsx`, `ReviewEditor.tsx`.
- **완료 기준:** 키보드만으로 로딩 실패 재시도·검토·수정·적용이 가능하고, 초점이 사라지지 않으며, 스크린리더가 같은
  설명을 두 번 읽지 않는다. 오류와 상태가 적절히 한 번 announce된다.
- **검증:** 기존 접근성 테스트와 Playwright의 키보드·초점·accessible name 회귀, 수동 스크린리더 확인.
- **계약:** 필요 없다.

### P2-2. 같은 자산의 중복 Blob GET 제거

- **문제:** 결과 카드와 적용 문서 미리보기가 각각 `GeneratedIllustrationImage`를 마운트하고, 컴포넌트마다 같은
  자산의 이미지를 GET해 Blob URL을 만든다(`GeneratedIllustrationImage.tsx:16`,
  `GeneratedIllustrationPreview.tsx:48`).
- **개선 작업:** 문서·자산 ID 단위로 진행 중 요청과 Blob/Object URL을 공유하되 소유자·문서 사이에 재사용하지 않는다.
  참조 해제와 문서 전환 시 revoke하고, 실패 재시도는 공유 캐시를 무효화한다. 인증 이미지를 공개 캐시로 바꾸지 않는다.
- **관련 파일:** `GeneratedIllustrationImage.tsx`, `IllustrationGenerationPanel.tsx`,
  `GeneratedIllustrationPreview.tsx`, `ReviewEditor.tsx`.
- **완료 기준:** 같은 자산을 두 위치에 표시해도 이미지 GET이 한 번만 발생하고, 문서 전환·재시도·언마운트에서
  잘못된 이미지나 해제된 URL을 쓰지 않는다.
- **검증:** 네트워크 호출 횟수·Object URL 해제·실패 재시도·문서 전환 테스트.
- **계약:** 필요 없다.

### P2-3. 읽기 경로 `FOR NO KEY UPDATE`는 측정 후 선택 검토

- **문제:** 생성 작업 목록·단건 조회와 자산 목록·이미지 조회가 모두 `lockOwnedContext`를 거치고, 해당 SQL은
  conversion에 `FOR NO KEY UPDATE`를 사용한다
  (`backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/illustration/generation/JdbcIllustrationGenerationJobRepository.kt:334`).
  읽기 요청이 본문 저장과 불필요하게 경합할 가능성이 있지만 현재 측정 근거는 없다.
- **개선 작업:** 우선 대표 읽기/저장 동시성에서 대기 시간과 잠금 대기를 측정한다. 문제가 확인된 경우에만 생성·삭제
  안전성을 유지하는 읽기 전용 context 조회를 분리하고, 문서 삭제·revision·만료·소유권 보장을 다시 증명한다.
  측정 결과가 문제를 뒷받침하지 않으면 변경하지 않는다.
- **관련 파일:** 위 repository, `IllustrationGenerationJobService.kt`, `GeneratedIllustrationAssetService.kt`,
  `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/illustration/generation/JdbcIllustrationGenerationJobFlowTest.kt`.
- **완료 기준:** 측정 결과와 변경/유지 결정이 기록된다. 변경한다면 동시성·삭제·stale 회귀가 통과하고 읽기 대기가 개선된다.
- **검증:** 기존 DB 흐름 테스트와 실제 PostgreSQL 기반 측정. 추정만으로 최적화 완료를 선언하지 않는다.
- **계약:** 필요 없다.

## 4. 계약 변경 단위 구분

다음 세 작업은 클라이언트 우회 없이 공개 HTTP 계약을 먼저 확정한다.

| 작업 | 한 변경 단위에 포함할 산출물 |
|---|---|
| 자산 read/creation capability 분리 | `contracts/easy-doc-v1.yaml`, Kotlin DTO·`IllustrationGenerationContractTest`/해당 제안 계약 테스트, `frontend/src/api/types.ts`·`client.ts`, 서버 capability 계산과 React 소비자 |
| 대체텍스트 수정·후보 폐기(도입 승인 시) | YAML path/schema·CAS·stale·폐기 의미, Kotlin DTO/계약 테스트와 서비스, `frontend/src/api/`, 결과 카드·미리보기 |
| 사용량·요청 한도 노출 | YAML의 사용량/한도 필드와 0·null·한도 도달 의미, Kotlin DTO/계약 테스트, `frontend/src/api/`, 402·생성 전 안내 |

P0-2 provider 본문/문안, P1-2 경합, P1-3 폴링, 실패 문구 자체, 기존 필드의 추가 표시,
P1-6 스키마 준수 버그 수정, P2 접근성·Blob·잠금 검토는 공개 API 형태 변경이 필요 없다.
이들을 이유로 다른 프로젝트의 계약을 임의로 넓히지 않는다.

## 5. 단계별 실행 순서와 의존성

1. **0단계 — 사실 확인과 출시 게이트 준비**
   - P0-2의 실제 요청·문안 불일치를 해소한다. 완료 전 실제 provider를 활성화하지 않는다.
   - 현재 기본 OFF·관리자 허용 목록·fake/0크레딧 경계와 기존 테스트를 재확인한다.
   - P0-1의 표본·채점·통과 기준·호출 수·총 달러 상한·사용량 관찰/중단 방법을 사전 확정한다.
   - P1-1 capability 분리 방식, P1-5 수정/폐기 도입 여부를 결정한다.
2. **1단계 — 자산 숨김·경합·폴링·길이 정합성**
   - P1-1을 먼저 완료해 이후 화면 작업이 올바른 읽기 capability 위에 서게 한다.
   - 이어서 P1-2, P1-3, P1-6을 독립 변경으로 구현하되 최종 통합 diff에서 함께 회귀 검증한다.
3. **2단계 — 실패와 검토 UX**
   - P1-4의 terminal 문구·정산 안내를 먼저 하고, 사용량/한도 필드가 필요하면 같은 계약 단위로 완성한다.
   - P1-5는 원 제안 정보 표시를 먼저 하고, 승인된 수정·폐기 계약을 뒤따라 구현한다. P1-1의 읽기 분리에 의존한다.
4. **3단계 — 접근성과 효율**
   - P2-1, P2-2를 최종 화면 구조에 맞춰 구현한다. P2-3은 측정 후에만 선택한다.
5. **4단계 — 실제 provider 평가와 파일럿 판단**
   - 0~3단계의 출시 차단 결함이 닫히고 표준 검증·fake E2E·리뷰가 최종 diff에서 통과한 뒤에만 P0-1 유료 평가를 시작한다.
   - 사전 통과 기준으로 판정하고, 통과해도 관리자 파일럿 범위·예산·관찰 가능성을 확인한 후 단계적으로 확대한다.

## 6. 테스트와 실제 품질 평가 계획

### 6.1 기존 테스트에 추가할 회귀 항목

| # | 회귀 항목 | 주요 기존 테스트 위치와 확인 내용 |
|---|---|---|
| 1 | **가격 설정 오류 시 기존 자산 유지** | `IllustrationGenerationPanel.test.tsx`, `IllustrationGenerationContractTest.kt`, `ReviewEditorIllustrations.test.tsx`: 생성 접수 불가와 자산 읽기 불가를 분리하고 기존 카드·적용 미리보기가 남는지 확인 |
| 2 | **제안 OFF+생성 읽기 ON** | capability API 계약 테스트와 `ReviewEditorIllustrations.test.tsx`: 제안 UI는 숨겨도 기존 생성 자산·미리보기를 읽고 새 제안/생성은 허용하지 않음 |
| 3 | **접수 중 문서 전환** | `IllustrationSuggestionsPanel.test.tsx`, `frontend/e2e/illustration-suggestions.spec.ts`: 지연된 A 응답이 B를 오염하지 않고 A 재방문 시 복구됨 |
| 4 | **401/404/5xx 폴링** | 제안·생성 패널 테스트: 영구 오류 중지, 일시 오류의 제한적 backoff·수동 재개, abort 무시와 추가 요청 수 |
| 5 | **terminal failure별 문구·반환·재생성** | `IllustrationGenerationPanel.test.tsx`, `ProcessIllustrationGenerationJobTest.kt`: `generation_failed`/`result_invalid`/`outcome_unknown`/`superseded`, 402, 10건 한도, 자동 재호출 없음 |
| 6 | **emoji 151~300개** | `IllustrationGenerationTest.kt`, `IllustrationSuggestionParserTest.kt`, 생성 패널·`illustrationConfiguration.test.ts`: 코드 포인트 경계와 UTF-16 canonical hash의 독립성 |
| 7 | **접근성/후보 정보·수정·폐기** | `ReviewEditorIllustrations.test.tsx`, 생성 패널 테스트, 관련 Playwright: 초점·입력 오류·중복 낭독·Blob 1회, 원 제안 연결, 승인된 수정/폐기의 저장·CAS·재방문·크레딧 불반환 |

각 항목은 새 하네스 대신 해당 기존 단위·계약·Playwright 테스트에 추가한다. 실패를 재현하는 테스트를 먼저 쓰고,
수정 뒤 같은 최종 diff에서 다시 실행한다.

### 6.2 실제 provider 평가 설계

유료 호출 전에 아래 표본, 절차와 통과 기준을 확정한다. 이 문서는 임의의 표본 수·정확도·지연 목표나
미측정 비용을 합격 기준처럼 제시하지 않는다.

- **표본:** 절차, 비교, 관계 문서; 그림이 도움이 되지 않는 무제안 문서; 조건·예외가 있는 문서; 숫자-only 문서를
  모두 포함한다. 숫자-only와 무제안 표본에서는 억지 제안/생성이 없는지 평가한다.
- **분리 평가:** ① 제안 위치·필요성과 원문 근거, ② 생성 이미지의 사실 보존, ③ 독자 이해 도움,
  ④ 대체텍스트의 정확성·간결성·중복 여부를 분리한다. 생성 성공이 제안 적절성을 증명하지 않는다.
- **사실 보존:** 행동 순서, 비교 대상, 관계, 숫자·날짜·금액, AND/OR, 조건·예외의 누락·추가·변형을 원문과 대조한다.
  그림 안 글자만으로 필수 정보를 전달한 경우도 기록한다.
- **이해 도움:** 사전에 정한 평가자와 채점 문항으로 그림 유무의 이해를 비교하고, 장식적이거나 오해를 유발하는 결과를
  실패 사례로 보존한다. 평가자 수와 합의 방법은 호출 전에 정한다.
- **운영 지표:** 접수부터 검토 가능까지의 지연, provider 응답 지연, 성공·확정 실패·결과 불명확 비율, 실제 사용량과
  청구 비용을 표본별로 기록한다. 구성된 추정 단가를 실제 비용으로 보고하지 않는다.
- **사전 통과 기준:** 표본 수, 허용되는 사실 오류, 제안/무제안 적절성, 이해 도움과 대체텍스트 채점 합격선,
  지연 판정 방식, 실제 비용 상한과 집계 방법을 호출 전에 사용자 승인으로 확정한다. 하나라도 미정이면 평가를 시작하지 않는다.

### 6.3 표준 검증 명령과 실행 경계

구현 변경은 각 영역과 최종 통합 diff에서 다음을 실행한다.

```bash
cd backend-kotlin && ./gradlew build
cd frontend && npm run check && npm run test -- --run && npm run build
docker compose config
frontend/e2e/run-local.sh illustration-suggestions.spec.ts
```

E2E는 운영 `.env`·DB·포트·실제 API 키를 쓰지 않는 **격리된 fake/0크레딧 스택**에서 실행한다. fake 통과는
실제 provider 품질·지연·비용 검증이 아니다. 실제 유료 호출은 별도 승인과 명시적인 총 달러 상한, 사용량 관찰·중단
방법 없이는 실행하지 않으며, 기존 텍스트 평가 예산을 전용하지 않는다. 새 검증 하네스는 만들지 않는다.

## 7. 롤아웃과 중단 조건

- 기본 OFF와 관리자 허용 목록을 유지하고, 내부 소수 문서 → 관리자 파일럿 → 확대 여부 판단 순서로 진행한다.
- 신규 생성 중단과 기존 자산 읽기 중단을 분리한다. 문제 발생 시 우선 새 제안/생성 접수만 끄고 기존 자산·본문·다운로드는
  소유자가 확인할 수 있게 유지한다.
- 개인정보 문안/요청 불일치, 사전 기준을 넘는 사실 오류, 실제 비용 상한 도달, 비용 관찰 불가, stale 적용,
  중복 과금·반환 누락, 기존 자산 손실처럼 보이는 동작, 심각한 접근성 차단이 나오면 즉시 확대를 멈춘다.
- 평가 중 통과 기준을 결과에 맞춰 완화하지 않는다. 기준 변경이 필요하면 평가를 중단하고 새 기준·예산을 다시 승인받는다.
- 실제 호출 전후의 설정, 모델, 표본, 호출 수, 실제 비용과 중단 사유를 기록하되 원문·프롬프트·개인정보·비밀키는 로그나
  계획 산출물에 남기지 않는다.

## 8. 리스크와 대응

- **읽기/생성 분리가 권한을 넓히는 위험:** read capability도 문서 소유권·만료·관리자 허용 목록을 유지하고 404 우선순위를 계약 테스트로 고정한다.
- **수정·폐기가 provenance와 정산을 모호하게 하는 위험:** 생성 구성 revision과 자산 revision을 구분하고, 폐기·적용 제거·물리 삭제·크레딧 불반환 의미를 계약에 명시한다.
- **폴링 완화가 상태 인식을 늦추는 위험:** 마지막 확인 시각·오류·수동 새로고침을 제공하고 서버 작업 상태를 추정하지 않는다.
- **Blob 공유가 사생활이나 수명 관리를 해치는 위험:** 문서·자산 범위로 제한하고 전환·언마운트 시 revoke하며 비공개 인증 GET을 유지한다.
- **잠금 최적화가 삭제/stale 안전을 깨는 위험:** 성능 문제가 측정된 경우에만 별도 변경으로 진행하고 동시성 회귀를 통과시킨다.
- **fake 성공을 실제 품질로 과대해석하는 위험:** 출시 게이트와 보고에서 fake 회귀와 유료 실측을 분리한다.

## 9. 완료 정의(DoD)

- P0-2 문안/요청 정합성이 실제 provider 활성화 전에 해소됐다.
- P1의 자산 유지, capability 조합, 문서 전환, 폴링, terminal 실패·정산·한도, 후보 검토와 코드 포인트 회귀가 닫혔다.
  수정·폐기를 도입하지 않기로 한 결정은 명시적으로 기록하고 화면에 미구현 조작을 노출하지 않는다.
- P2 접근성·Blob 중복이 해소됐고, 읽기 잠금은 측정과 변경/유지 결정이 기록됐다.
- 모든 계약 변경이 YAML + Kotlin DTO/계약 테스트 + `frontend/src/api/` + 소비자로 완결됐다.
- 최종 통합 diff에서 §6.3 표준 검증과 관련 기존 테스트·fake E2E·독립 리뷰가 통과했다. 코드가 바뀌면 이전 통과를 재사용하지 않는다.
- 사전 승인된 기준으로 실제 provider 평가를 판정하고 실제 비용·지연을 기록했으며, 파일럿 유지·중단·확대 결정이 남았다.
- 기본 OFF·관리자 제한·유료 호출 승인 경계가 유지되고, 남은 미검증 항목을 완료로 보고하지 않았다.

## 10. 구현 기록 (2026-11-10, 통합 브랜치 `codex/illustration-explanation-improvement-20261108`)

| 과제 | 상태 | 결정·근거 |
|---|---|---|
| P0-2 `store=false` | 완료(문안 정정) | Images API는 `store` 파라미터를 지원하지 않으므로 요청 본문은 바꾸지 않고, 처리방침을 `privacy-2026-11-10`(즉시 시행)으로 개정해 그림 제안·생성 전송 내용·생성 그림 보관을 공개하고 `store=false` 문장을 바로잡았다. 결제 동의 증빙 스냅샷은 `LegalSnapshot.PRIVACY_VERSION`으로 분리해 유료 시행 시각(`VERSION`)은 움직이지 않았다. |
| P1-1 capability 분리 | 완료 | `ReviewCapabilities`에 `illustration_{suggestions,generation}_{read,create}`를 추가했다. 기존 필드는 `*_create`와 같은 값의 호환 필드다. read는 `read-enabled`에 연결되고 가격·접수 토글과 독립이며, 관리자 허용 목록 밖 사용자는 7개 필드가 모두 false로 마스킹된다(`ConversionIllustrationCapabilityContractTest`). |
| P1-2 문서 전환 경합 | 완료 | 접수 응답을 conversion·시도 범위로 검사하고 전환 시 abort한다. |
| P1-3 폴링 | 완료 | 401/404 즉시 중지, 5xx 3회 제한 후 수동 새로고침. terminal 상태 이후의 목록·자산 재조회 실패가 terminal 안내를 덮거나 폴링을 재개하지 않도록 수정했다(회귀 테스트 3건). |
| P1-4 실패·402·한도 | 완료 | 실패 코드별 안내, 402 필요량·가용량·충전 이동, 응답에 `request_count`·`request_limit`(10)·`remaining_request_count` 노출(계약 변경). |
| P1-5 후보 검토 | 완료(도입) | 원 제안 정보 표시 + `PATCH` 대체텍스트 수정·soft 폐기를 `expected_asset_revision` CAS로 도입했다. 폐기는 암호화 메타데이터의 soft flag(물리 삭제 아님, 복구 없음, 적용 자산은 먼저 적용 해제), 크레딧 불반환, `configuration_revision`은 생성 당시 provenance 유지. 새 migration 없음(기존 V51 `asset_revision` 사용). |
| P1-6 코드 포인트 300 | 완료 | 제안·생성·프런트를 코드 포인트로 통일, canonical 해시 벡터 유지. |
| P2-1 접근성 | 완료 | 로딩 status·오류 연결 재시도·입력 오류 ID·중복 낭독 제거(`alt=""`+`figcaption`). 수동 스크린리더 확인은 미실시. |
| P2-2 Blob 공유 | 완료 | `generatedIllustrationImageCache.ts`로 문서·자산 단위 GET 1회·참조 해제 시 revoke. |
| P2-3 읽기 잠금 | 변경 | 실제 PostgreSQL 흐름 테스트에서 읽기 context 조회 트랜잭션이 열린 동안 본문 저장이 대기 없이 끝남을 확인하고(기존 `FOR NO KEY UPDATE`는 저장을 막음), **생성** 작업·자산 읽기 경로만 잠금 없는 `readOwnedContext`로 분리했다(제안 읽기·폴링은 여전히 `FOR NO KEY UPDATE`). 문서 삭제 직렬화·소유권·만료는 회귀 테스트로 유지. 대기 시간 수치 측정은 하지 않았다. |
| P0-1 실제 provider 평가 | **1차 실행 불통과(2026-11-10)** | §12 기준으로 13건·이미지 17장 실행, 레인 누적 US$4.78. ②사실 보존 치명 4건(010-2 분변 검사를 내시경으로 그림·015-1 예시 금액 추가·089-1 절차 단계 추가·097-2 임대를 상자 전달로 그림), ③오해 유발 1건(010-2)으로 미달, ⑦ 실제 청구는 운영자 확인 대기. 제안 `reason` 일본어 1건(①은 통과). [보고서](../reports/2026-11-10-illustration-p0-1-eval.md). 파일럿 확대 불가. **2차 실행 불통과(2026-11-10, §12.4)**: 후속 1·3 수정 후 17건·이미지 24장, 레인 누적 US$6.10(승인 총액 누적 US$10.88/50). 1차 치명 4건·오해 유발 1건은 같은 문서에서 모두 해소됐고 ①①′⑤⑥ 통과. 그러나 ② 치명 3건 — 016-1(범례에 원문에 없는 「건강보험」 부담 주체)·019-1(원문에 없는 「전기요금 감면 신청서」)·002-2(「20분 이상」 시계를 약 10분으로 그림) — 과 깨진 글자·장식 경미 5/24 = 21%, ③ 오해 유발 1건·④ 모순 1건(모두 002-2★)으로 미달. 041 제안 탈락은 검증기 오탐(「‘10.」을 2010년으로 해석)으로 확인. ⑦ 실제 청구 운영자 확인 대기. [2차 보고서](../reports/2026-11-10-illustration-p0-1-eval-2.md). 파일럿 확대 불가, 3차 여부·기준 변경은 새 승인 사안. **3차 실행 불통과(2026-11-11, §12.5)**: #203·#204·#208 수정 후 같은 17건·이미지 23장, 레인 누적 US$6.05(승인 총액 누적 US$16.93/50). 2차 치명 3건·깨진 글자 4건 모두 재발 없음, 경미 1/23 = 4.3%, ①①′⑤⑥ 통과. 그러나 081-1★(지급 연결선이 ✗ 미선정 패널에서 출발)이 ② 치명·③ 0점·④ 모순으로 미달. 019-1(서류 두 장 묶음)은 ★정확. 052 제안 1건은 `reason` 일본어로 검증기가 정당하게 제거. ⑦ 실제 청구 운영자 확인 대기. [3차 보고서](../reports/2026-11-11-illustration-p0-1-eval-3.md). 파일럿 확대 불가, 4차 또는 사람 검토 전제 기준 변경은 새 승인 사안. |

PR #199 리뷰에서 의도된 동작으로 유지한 것: 생성 접수 OFF(`enabled=false`)에서도 `read-enabled`가 켜져 있으면 허용 사용자는
적용·해제·대체텍스트 수정·soft 폐기를 할 수 있다(§7의 "접수만 끄고 기존 자산은 소유자가 다룬다"). stale 자산은 CAS 규칙상
수정·폐기할 수 없다(계약에 명시).

후속 과제(차단 아님):
- 잠금 없는 읽기는 `content_revision`과 자산 목록을 한 스냅샷으로 읽지 않아 저장 직후 `stale`이 잠깐 false일 수 있다. 쓰기는 잠금 CAS로 409가 되어 잘못된 적용은 없다.
- 대체텍스트 수정이 conversion 잠금을 잡은 채 PNG 전체를 복호화·재암호화한다(공유 envelope 열 규칙 때문). 필드별 envelope 분리 검토.
- 제안 읽기·폴링 경로의 잠금 분리.
- 같은 자산을 두 곳에 표시할 때 한쪽 재시도 성공이 다른 쪽 오류 표시를 갱신하지 않는다.
- 새 PATCH·PUT application 경로를 소유권·보존 reach 가드 테스트 목록에 추가.
- stale 미적용 후보를 정리할 방법(현재는 목록에 남는다).

## 11. 사용자 추가 결정이 필요한 사항

1·2는 §10대로 결정·구현됐다. 3만 남아 있다.


1. **생성 후 대체텍스트 수정·후보 폐기 도입 여부:** 도입할지, 폐기를 숨김/물리 삭제 중 무엇으로 할지, stale·보존·재생성 규칙을 확정해야 한다.
2. **capability 계약 분리 방식:** `ReviewCapabilities`에 read/create를 추가할지, 각 작업/자산 응답에 둘지, 제안 읽기까지 같은 단위로 분리할지 선택해야 한다.
3. **품질 평가 통과 기준과 예산:** 2026-11-10 결정 — 총 예산 US$50 이내(사용자 승인), 기준은 §12(세션 추천안으로 확정·진행 지시).

## 12. P0-1 실제 provider 평가 기준 (2026-11-10 확정, 호출 전 고정)

사용자가 2026-11-10 「평가 기준 알려주고, 예산은 $50 이내에서 진행해」로 총액을 승인했고, 아래 기준은 세션 추천안으로
확정했다. **결과를 본 뒤 기준을 바꾸지 않는다**(§7). 바꿔야 하면 평가를 중단하고 다시 승인받는다.

### 12.1 실행 조건

- **경로:** 운영 파일럿 DB·화면을 쓰지 않는다. `:infrastructure:testLlm` 레인(기존 `EASYDOC_LANE_*` 관례)에서
  제품과 같은 변환 프롬프트·`LlmPrompt.forIllustrationSuggestions`·`IllustrationSuggestionParser`·숫자-only 가드·
  `buildIllustrationGenerationPrompt`·`OpenAiImageGenerationProvider`·`GeneratedPngValidator`를 그대로 호출한다.
- **모델:** 변환·제안 `openai`/`gpt-6-astra`(제품 설정), 이미지 `gpt-image-2.5-sunburst`, `quality=medium`, 1024×1024, `n=1`.
  재시도 0회, 각 단계 1회 실행(반복 실행 분산은 측정하지 않는다).
- **표본 13건:** 절차 `001·010·015·089`, 비교·관계 `041·090·097`, 조건·예외 `005·016·081`,
  무제안 예상 `052·063`, 숫자-only 합성 1건. 골든 코퍼스(`data/golden/documents`)에서 길이 4,000자 이하만 골랐다.
  이 중 `001·005·010·015·016`은 코퍼스의 합성(`synthetic: true`) 문서이고 나머지 7건이 실제 공개 원문이다 — 결과를 보고할 때 구분한다.
- **생성 범위:** 문서마다 유효 제안 중 앞의 최대 2건을, 제안이 준 장면·보존 사실·대체텍스트 전부로 1장씩 생성한다
  (상한 26장, 무제안·숫자-only 표본이 제안을 내지 않으면 20장).
- **예산:** 레인 상한 `EASYDOC_LANE_MAX_USD=20`. 다음 호출의 최악 비용(텍스트는 출력 상한 기준, 이미지는 US$0.10/장으로
  보수 계상)을 더해 상한을 넘으면 그 호출을 시작하지 않는다. 사전 추정 총액은 약 US$5(변환이 대부분, 이미지는 외부 추정
  US$0.013~0.03/장). 이미지 공식 단가표를 찾지 못해 US$0.10/장은 추정이며, 실제 단가가 더 높으면 사전 점검이 과소
  계상할 수 있다 — 노출은 26장으로 묶이고 ⑦의 실제 청구 확인이 이를 덮는다. 레인 `ledger.csv`의 `cumulative_usd`(실패 호출은
  예약액 전부를 유지)가 예산 판정의 기준이고, `est_usd`는 측정된 호출만 채운다(미측정은 빈 칸).
  승인 총액 US$50 안에서 재실행이 필요하면 누적액을 이 문서에 적고 다시 시작한다.
- **산출물:** `docs/reports/2026-11-10-illustration-p0-1-eval-artifacts/`에 문서별 제안 JSON, PNG, 대체텍스트, 단계별 지연·토큰·
  추정 비용, 실패 분류를 남긴다. 표본은 공개 행정 문서이고 개인정보가 없어 원문 인용을 허용하되, API 키·요청 헤더는 남기지 않는다.

### 12.2 평가자와 채점

평가자는 세션 모델(Claude)이 원문·쉬운 글·제안·이미지를 직접 보고 채점하고, 운영자가 실패·경계 사례를 확인한다.
**사람 독자 이해도 실험이 아니다** — ③은 대리 지표로만 보고하고 독자 효과의 근거로 쓰지 않는다.

| # | 항목 | 단위 | 통과 기준 |
|---|---|---|---|
| ① | 제안 적절성 — 위치(`body_range`)·필요성·목적이 원문과 맞고 근거 인용이 원문을 지지 | 제안 | 적절 ≥ 80% |
| ①′ | 무제안·숫자-only 표본에서 억지 제안 | 표본 3건 | 부적절 제안 0건(제안 0건이거나 전부 적절) |
| ② | 사실 보존 — 순서·비교 대상·관계·숫자·날짜·금액·AND/OR·조건·예외의 누락·추가·변형, 필수 정보를 그림 속 글자로만 전달 | 이미지 | **치명 오류 0건**, 경미(필수 아닌 깨진 글자·장식 요소) ≤ 20% |
| ③ | 이해 도움(대리) — 0 오해 유발 / 1 장식 / 2 이해에 도움 | 이미지 | 0점 0건, 2점 ≥ 70% |
| ④ | 대체텍스트 — 이미지와 모순 없음·핵심 전달·간결(300 코드 포인트 이하는 제품이 강제) | 이미지 | 정확 ≥ 90%, 모순 0건 |
| ⑤ | 운영 — 생성 성공률(검증 통과 PNG), 결과 불명확(`outcome_unknown`·timeout) | 생성 호출 | 성공 ≥ 90%, 결과 불명확 0건 |
| ⑥ | 지연 — provider 응답 시간 | 호출 | 제안 p95 ≤ 60초, 이미지 p95 ≤ 90초·최대 < 120초(작업 lease 120초) |
| ⑦ | 비용 — 레인 추정 합계와 OpenAI 사용량 화면의 실제 청구 대조 | 실행 전체 | 합계 ≤ US$20, 실제 청구 확인 완료(운영자) |

### 12.3 판정과 중단

- ①~⑦이 모두 통과해야 「관리자 파일럿 유지·소규모 확대 검토」다. 하나라도 미달이거나 ⑦ 실제 청구를 확인하지 못하면
  확대하지 않고 실패 사례를 기록한다.
- 인증·권한·정책 거부(4xx) 또는 결과 불명확이 2건 연속이면 레인을 멈춘다. 예산 상한 도달도 즉시 중단이다.
- 결과 보고서는 `docs/reports/2026-11-10-illustration-p0-1-eval.md`에 쓰고 §10의 P0-1 행을 갱신한다.

### 12.4 2차 평가 전 개정 (2026-11-10, 2차 실행 전 고정)

1차 결과([보고서](../reports/2026-11-10-illustration-p0-1-eval.md))를 보고 후속 1·3을 고친 뒤 재평가한다(사용자 지시
「후속 1·3 고치고 재평가 진행해」). 아래는 **2차 실행 전에** 정한 개정이며, 2차 결과를 본 뒤에는 바꾸지 않는다.

- **고치는 것:** ① 이미지 생성 프롬프트에 원문이 정한 대상(검사·서류·혜택·제공물)의 종류 보존, 원문에 없는 예시 수치 금지,
  절차 단계 추가·중복 금지를 넣는다. 제안 프롬프트는 `scenes`가 대상을 원문 용어로 지정하게 한다.
  ② 제안 프롬프트의 출력 언어를 한국어로 고정하고, 사용자에게 보이는 필드(`reason`·`scenes`·`preserved_facts`·`alt_text_draft`)가
  한국어가 아니면 검증기가 그 제안을 버린다. 프롬프트가 바뀌므로 `ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION`을 올린다.
- **② 경미 정의는 문언 그대로 유지한다** — 「필수 아닌 깨진 글자·장식 요소」만 경미이고, 숫자·단계·대상의 추가·변형은 치명이다.
- **① 에 언어 항목을 더한다** — 사용자에게 보이는 필드가 한국어가 아니면 부적절이다. 검증기가 버린 제안은 따로 센다.
- **표본:** 1차 13건을 그대로 쓰고(같은 문서에서 고쳐졌는지), 1차에 쓰지 않은 보류 표본 4건 `002·019·080·098`을 더한다
  (`002·019` 합성, `080·098` 실제 원문, 모두 900자 이하). 1차 표본에 맞춰 고친 효과와 일반화를 나눠 보고하되,
  통과 판정은 17건 전체로 한다. 이미지 상한은 34장(문서당 2장).
- **예산:** 이번 실행 레인 상한 US$20. 승인 총액 US$50 중 1차 누적 US$4.78을 뺀 범위 안이다.
- 판정·중단 규칙(§12.3)과 ②~⑦ 기준값은 그대로다. 결과는 `docs/reports/2026-11-10-illustration-p0-1-eval-2.md`에 쓴다.

### 12.5 3차 평가 (2026-11-11, 3차 실행 전 고정)

2차 결과([보고서](../reports/2026-11-10-illustration-p0-1-eval-2.md), 불통과)의 후속 #203·#204·#208을 고친 뒤 재평가한다
(사용자 승인 2026-11-11 「승인」). 아래는 **3차 실행 전에** 정한 조건이며, 3차 결과를 본 뒤에는 바꾸지 않는다.

- **2차 대비 코드 변경:** #204 생성 프롬프트 숫자 규칙 단일화(그림에 숫자·날짜·기간·수량을 글자로도 그림 속 양으로도 넣지 않음)와
  보존 사실을 「그리지 말고 모순하지 말 것」으로 바꾼 문구, 제안 프롬프트의 평가 표본 낱말 예시 제거(`analysis_version` `-4`,
  생성 프롬프트 `r7-illustration-generation-2`). #203 축약 연도 중의 표지(‘ ')의 두 읽기 비교(041 오탐). #208 「프로」 외래어 제외.
- **기준:** §12.1~§12.3과 §12.4의 ①~⑦·② 경미 정의를 그대로 쓴다. 기준은 바꾸지 않는다.
- **표본:** 2차와 같은 17건. 같은 문서에서 고쳐졌는지를 보는 실행이므로, 일반화 근거로는 약하다는 한계를 보고서에 적는다.
  010의 표본 낱말 예시는 제거됐으므로 010-2가 다시 맞으면 일반 규칙 효과로 본다.
- **실행 조건:** 2차와 같은 모델·토큰 상한·문서당 이미지 2장·재시도 0. 산출물은
  `docs/reports/2026-11-11-illustration-p0-1-eval-3-artifacts/`, 보고서는 `docs/reports/2026-11-11-illustration-p0-1-eval-3.md`.
- **예산:** 이번 실행 레인 상한 US$20. 승인 총액 US$50 중 1·2차 누적 US$10.88을 뺀 잔여 US$39.12 안이다.

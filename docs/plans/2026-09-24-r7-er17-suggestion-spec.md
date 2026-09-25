# R7 ER-17 문맥 기반 그림 제안 구현 명세

작성일: 2026-09-24 · 상태: 서버·화면 통합 및 무료 테스트 흐름 추가(2026-09-25). 상위 문서: [R7 수정 계획](2026-09-24-contextual-illustration-correction.md) §2~4·§6 결정표. 이 명세는 ER-17의 경계와 판단을 고정한다. 파일 경로·SQL 이름 같은 세부는 구현 PR에서 기존 관례를 따라 정하되, 아래 규칙을 바꾸려면 이 문서를 먼저 고친다.

통합 시 이미 적용된 읽기 수준 V35를 보존하고 그림 제안 스키마는 V36으로 옮겼다.
사용자 선택에 따른 무료 모드에서는 제안과 브라우저 내 그림 적용 미리보기를 시험한다.
이미지 생성 provider·생성 작업 저장은 여전히 ER-18 범위이며, 아래 생성 버튼 비활성 조건은
실제 provider 모드에 적용한다. 무료 테스트 도식은 별도 안내와 명시적 확인 뒤 임시 적용한다.

## 1. 범위

- 사용자가 저장된 변환 결과에서 ‘그림 제안 확인’을 요청하면 LLM을 **한 번** 호출해 그림으로 설명하면 도움이 되는 문맥을 구조화 제안으로 받는다(별도 요청 분석, §6 결정).
- 기본 변환 프롬프트·출력·호출 수는 바꾸지 않는다. 제안 분석은 자동 실행하지 않는다.
- 이미지 생성(ER-18)·검토/적용 UX(ER-19)·실제 품질 평가(ER-20)는 범위 밖이다. 기존 ER-15/16 카탈로그·수동 배치는 그대로 두고 새 흐름이 의존하지 않는다.

## 2. 작업 구조 — R2 행동 안내 작업 패턴 재사용

제안 분석은 수십 초 걸리는 유료 호출이라 R2와 같은 비동기 작업으로 둔다. R2의 검증된 불변식을 그대로 가져온다.

| 항목 | 규칙 |
|---|---|
| 작업 테이블 | 새 `illustration_suggestion_jobs`. R2 `action_guide_jobs`와 같은 형태(요청 키 UNIQUE, 문서당·계정당 활성 1건 부분 UNIQUE, lease/fence=`attempts`, worker_slot 1..2, provider_started 일관성, status↔settlement CHECK). 문서 FK 없이 감사 행으로 남고 owner/workspace는 SET NULL |
| 결과 테이블 | 새 `illustration_suggestion_results`. 작업당 1행, `(job_id, conversion_id)` 복합 FK·conversions CASCADE, 암호화 payload(봉투 3열)와 `based_on_content_revision` |
| 상태 | queued/running/succeeded/failed/superseded. 실패 코드는 R2와 같은 세 가지(generation_failed/result_invalid/outcome_unknown) |
| 입력 확정 | 시작 트랜잭션 안에서 conversions 행 잠금 아래 원문·저장 본문을 읽는다(PR #150 이후 R2와 같은 prepare 구조). 입력 준비 실패는 provider 미시작 실패로 종료 |
| 재획득 상한 | PR #154와 같은 dead-letter. 별도 구성값 `max-lease-attempts`(기본 5, 1..100) |
| 문서당 호출 상한 | provider 시작 기준 누적 상한은 구성값 `max-provider-attempts-per-conversion`(기본 3). D04를 자동 승계한 것이 아니라 같은 기본값을 둔 운영 구성이다 |
| 문서 삭제·만료·탈퇴 | R2 트리거와 같은 BEFORE DELETE 정산 함수를 새 작업에도 둔다(예약 반환, 진행 중 호출 outcome_unknown, 작업 superseded). 탈퇴 경로는 PR #152의 ‘문서 먼저 삭제’ 순서를 그대로 탄다 |
| stale | 결과는 `based_on_content_revision`을 갖고 **읽을 때** 현재 `content_revision`과 비교해 `stale`로 표시한다(R2 후보와 같은 패턴). 트리거로 결과를 고치지 않는다 |

공통화는 이번 범위의 필수 조건이 아니다. R2 코드를 제네릭으로 바꾸는 리팩터링은 하지 않고, 새 기능 전용 클래스로 같은 패턴을 구현한다. 단, 원문 앵커 검증(§4)은 공용 core 함수로 추출해 두 기능이 같은 규칙을 쓴다.

## 3. 이용량(D10 미정 대응)

- 예약 → 소비 → 반환 흐름을 R2와 같게 구현한다. 차감량은 `@ConfigurationProperties` 구성값 `credits-per-100-chars`(BigDecimal, 0.1 단위, 원문+저장 본문이 아니라 **원문 글자 수** 기준으로 R2 `Credits.requiredFor`와 같은 올림 방식)로 받는다.
- 실제 provider 모드에서 이 값이 없으면 기능이 켜져 있어도 작업 접수를 **503**으로 거부하고 capability를 false로 보고한다. fake 모드에서만 0을 허용하며, 0이면 크레딧 거래 행을 만들지 않는다(`reserved_credits = 0`, settlement `not_charged`).
- 소비 시점: 분석 결과(제안 1건 이상 또는 ‘제안 없음’)가 저장될 때 소비한다. provider 오류·결과 형식 오류·모든 제안이 검증에서 탈락한 경우·stale·만료는 반환한다.
- 요청 전 차감량 표시를 위해 조회 응답에 `required_credits`를 포함한다.

## 4. 제안 스키마와 검증(core, 순수 함수)

LLM 출력은 JSON 하나이며 추가 키를 거부한다. 서버가 검증 뒤 저장하는 형태:

| 필드 | 규칙 |
|---|---|
| `schema_version` | 1 |
| `analysis_version` | 프롬프트 버전 문자열(코드 상수, 계약 고정값) |
| `suggestions` | 0~5건. 0건은 정상 결과(‘제안 없음’) |
| `suggestion_id` | 서버가 부여하는 UUID. LLM 출력의 식별자를 쓰지 않는다 |
| `purpose` | `procedure`(순서·절차) / `comparison`(대상·경로 비교) / `relationship`(구성·사용 관계) |
| `reason` | 그림이 도움이 되는 이유, 1~300자 |
| `body_range` | 저장 본문 줄 범위 `{start, end}`(0-based, 포함), 본문 줄 수 안. 여러 문단 범위 허용 |
| `source_anchors` | 1~10개, R2와 같은 규칙(원문 줄 인덱스 + 해당 줄에 실제로 있는 인용) |
| `scenes` | 그릴 내용 1~6장면, 각 1~200자 |
| `preserved_facts` | 그림이 바꾸면 안 되는 사실·조건 0~10개, 각 1~200자 |
| `alt_text_draft` | 대체텍스트 초안 1~300자 |

- 글자 수는 코드포인트 기준. 구조 규칙 위반은 결과 전체를 `result_invalid`로 본다.
- 의미 검사는 **제안 단위**로 한다. 앵커가 원문과 맞지 않거나, `scenes`·`preserved_facts`·`alt_text_draft`에 앵커 근거에 없는 사실(숫자·날짜·금액 등 R2 `findMissingFacts`가 잡는 항목)이 있으면 그 제안만 버린다. LLM이 1건 이상 냈는데 모두 버려지면 결과는 `result_invalid`(반환), LLM이 0건을 냈으면 ‘제안 없음’(소비).
- 버린 제안 수는 결과에 `dropped_count`로 남긴다(내용은 저장하지 않음).
- 모든 모델 타입의 `toString`은 본문을 출력하지 않는다.

## 5. 프롬프트

`LlmPrompt`에 제안 분석 전용 팩토리를 추가한다. R2처럼 난수 구분자로 원문(줄 번호 `[i]` 부착)과 저장 본문(줄 번호 부착)을 감싼다. 시스템 프롬프트 요지:

- 문서 목적·주변 문장·조건·예외를 함께 읽고, 그림이 **행동 순서·비교·관계** 이해를 실제로 돕는 문맥만 고른다. 낱말마다 아이콘을 붙이는 제안, 장식용 그림, 연락처·날짜 나열 문맥은 제안하지 않는다.
- 날짜·금액·자격·AND/OR·예외 근거가 불명확하거나 시각화가 오해를 키우면 제안하지 않는다. 적절한 문맥이 없으면 빈 배열을 낸다.
- 원문에 없는 행동·장소·기간·조건을 넣지 않는다. 성인 독자를 어린이처럼 묘사하지 않는다. 중요한 조건을 그림 속 글자에 맡기지 않는다.
- 모든 제안은 원문 줄 번호와 그 줄의 정확한 인용을 근거로 단다.

`LlmCallPurpose`에 `illustration_suggestion`을 추가하고 `llm_calls`의 purpose CHECK를 새 마이그레이션에서 확장한다. 호출은 1회, 자동 재시도 없음, 출력 상한은 구성값(기본 8,192토큰), 타임아웃은 R2 worker와 같은 방식.

## 6. API·계약(초안 — 구현 PR에서 계약과 함께 확정)

| 메서드·경로 | 동작 |
|---|---|
| `POST /conversions/{conversion_id}/illustration-suggestion-jobs` | `{request_id, expected_content_revision}` → 202 + Location, `X-Credit-Balance`. 402 잔액 부족, 409 버전 충돌·활성 작업, 429 호출 상한, 503 이용량 미설정 |
| `GET /conversions/{conversion_id}/illustration-suggestion-jobs` · `/{job_id}` | 작업 목록·단건 |
| `GET /conversions/{conversion_id}/illustration-suggestions` | 최신 결과: `status`(`not_analyzed`/`ready`/`no_suggestions`/`stale`), `content_revision`, `based_on_content_revision`, `required_credits`, `suggestions[]`, `dropped_count` |

- 토글 `easydoc.illustration-suggestions.enabled`(api·worker)와 `worker-enabled`, 둘 다 기본 false. 꺼져 있으면 404. 기존 `easydoc.illustrations.enabled`와 독립이다.
- `review_capabilities`에 `illustration_suggestions`를 추가한다(토글 ON이고 이용량이 유효할 때만 true).
- 새 경로는 모두 인증·소유권 필수, 타인 자원은 404. 계약·Kotlin 계약 테스트·`frontend/src/api` 타입과 호출부를 같은 PR에서 맞춘다.
- fake 모드: 프로필 `illustration-suggestion-fake`에서 결정적인 제안(절차 1건, 원문 첫 줄 근거)을 돌려주는 fake runner. e2e compose 설정에 포함한다.

## 7. 저장·보안 체크리스트

새 암호화 컬럼은 `EncryptedField`에 추가하고, 다음 가드에 등록한다: `EncryptionSchemeSchemaTest`, `EnvelopeColumnWriteGuardTest`, `OwnershipPredicateGuardTest`, `SensitiveToStringReachTest`(선언 수), `AuthenticationCoverageContractTest`, 키 회전 family(`KeyRotationConfiguration`·`KeyRotationRunner`), testFixtures `DerivedRows` CENSUS와 이를 쓰는 만료 파기·탈퇴 테스트, `ConversionFeatureRouteReachTest`(교차 사용자 404). 로그에는 작업 ID·상태·예외 타입만 남긴다.

## 8. 인수 기준(ER-17 부분)

- AC-R7-c(fake): 절차 문맥 fixture → 제안 1건, 근거·이유·장면·보존 사실 연결. 원문에 없는 숫자를 넣은 제안은 버려진다.
- AC-R7-d(fake): 연락처만 있는 fixture → ‘제안 없음’으로 소비, 분석 실패와 구분.
- 기본 변환 경로의 LLM 호출 수·프롬프트가 바뀌지 않았음을 기존 테스트로 확인한다.
- 중복 요청은 같은 작업, 본문 수정 후 결과는 stale, 문서 삭제·만료·탈퇴 시 예약 반환과 결과 파기.
- 실제 문맥 이해 품질은 ER-20 유료 평가 전까지 주장하지 않는다.

## 9. PR 분할

1. **ER-17-1 core:** 제안 모델·파서·검증기, 원문 앵커 검증 공용 함수 추출(R2 동작·테스트 불변), 프롬프트 팩토리. DB·Spring 없음.
2. **ER-17-2 서버:** 마이그레이션·application·infrastructure·worker·api·계약, `frontend/src/api` 타입·호출부, 가드 등록, fake runner.
3. **ER-17-3 화면:** 리뷰 화면 제안 패널(요청 전 차감량 표시, 상태별 문구, 폴링·재방문 복구, 이미지 생성 버튼은 ER-18까지 비활성) + e2e.

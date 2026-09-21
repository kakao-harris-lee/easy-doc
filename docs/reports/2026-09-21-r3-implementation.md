# R3 implementation status

2026-09-21 기준 R3 구현은 기본 경로를 보존한 opt-in 기반으로 반영했다.

## 구현된 동작

- 사전 색인의 선택적 `v=reviewed` provenance를 `DefinitionReviewStatus`로 읽는다. `v`가 없거나 알 수 없는 값이면 각각 `UNVERIFIED`로 읽거나 기동 시 거절한다.
- R3 사전 컨텍스트는 `KEEP` 공식 이름만 남긴다. 정의는 명시적으로 `reviewed`인 경우에만 전달하고, `caution`·`review_note` 성격의 내부 메모와 예문은 전달하지 않는다. 검수된 정의 줄에는 `설명(검수된 정의)` 표식을 붙인다.
- R3 사전 자료와 문단 재변환의 저장 본문 prefix를 각각 난수 구분자 안에 넣고, 자료가 지시문이 아니라 참고 데이터임을 system prompt에서 고정한다.
- 문단 재변환은 저장된 `editedText`를 우선하고 없으면 `easyText`를 사용한다. 기존 `SegmentMapDerivation`의 현재 본문 mapping에서 대상과 앞선 단위가 명확한 `HIGH` 대응일 때만 대상 앞 prefix를 전달한다. 요청 index가 stale하거나 mapping이 모호하면 prefix를 생략하고 `R3_UNIT` 보수 경로를 사용한다. 이 계산은 provider를 추가 호출하지 않는다.
- HTTP 계약은 바뀌지 않았다. 프롬프트 문맥 인자는 trailing optional parameter로 두어 기본 `BASELINE`의 기존 사전 문자열·프롬프트·재변환 복호화 경로를 유지한다. 재변환 서비스의 `SegmentMapDerivation`은 현재 본문 mapping을 사용하기 위한 필수 내부 DI 의존성으로 배선했다.

## 확인 결과와 남은 AC

- 통과: `./gradlew build --no-daemon` 전체 빌드(81 tasks, 3분 54초). JUnit XML 집계 **3,158 tests, failures 0, errors 0, skipped 0**.
- 통과: R3 사전 선별·프롬프트 구분자·prompt 전파·재변환 mapping/no-extra-call에 대한 core/application focused tests.
- 배포 색인에는 현재 `v=reviewed` provenance가 없으며 확인된 production reviewed-definition count는 **0**이다. 구현은 future reviewed data를 받는 기반이지 기존 데이터를 자동 검수 처리하지 않는다.
- 기존 Kotlin quality lane에 `EASYDOC_LANE_VARIANT=baseline|r3` 선택을 추가했다. 기본은 baseline이고 알 수 없는 값은 거절한다. R3의 제품 사전 조립은 같은 검수 정의 선별 정책을 쓰며, 출처를 검증할 수 없는 파일 주입 컨텍스트는 거절한다. 변형 선택·사전 선별 오프라인 테스트가 통과했다. 실제 유료 R3 모델 호출·의미 판정은 실행하지 않았다.
- 따라서 ER-08/ER-09의 의미 품질 측정 및 AC-R3 release 판정은 완료로 주장하지 않는다. 현재 상태는 코드·단위 테스트 기반만 완료한 부분 구현이다.

## 리뷰 리스크

- 실제 reviewed 정의를 공급하려면 exporter/index source-of-truth가 `v=reviewed`를 명시적으로 생성해야 한다. 기존 `active`·review 상태나 사람 검수 메모를 자동 승격 근거로 사용하지 않는다.
- 예문은 별도 reviewed provenance가 없어서 R3 생성 자료에서 제외했다.
- R3 모델 평가에서는 기존 일반 변환 레인을 사용하되 승인된 달러·호출 상한과 표본을 먼저 고정해야 한다. 오프라인 선택 테스트를 전체 호출 예산 preflight나 실제 품질 측정으로 표현하지 않는다. R2의 US$50을 R3에 공유할지는 사용자 답변을 기다리는 중이다.

## 독립 리뷰

[리뷰 기록](2026-09-21-r3-review.md)의 P2 첫 등장 설명 경로 문제를 수정했다. 현재 본문의 앞부분이 불명확하면 `R3_UNIT`, 신뢰 가능한 첫 문단이면 빈 문맥을 가진 `R3`, 신뢰 가능한 앞부분이 있으면 해당 문맥을 가진 `R3`로 구분한다. 변환·보정의 지침을 같게 유지하며 추가 호출은 없다.

기준선 `055196d8`에서 새 4단어 입력 제한 때문에 기존 reach 테스트가 의도한 검증 단계에 도달하지 못하는 실패를 깨끗한 별도 checkout에서 재현했다. 제품 제한과 기존 상태·보안·크레딧 단언은 유지하고 테스트 입력만 보정했다. 별도로 기존 CreditsReachTest 주석 들여쓰기와 R3 테스트 생성 지점 allowlist를 현재 코드에 맞췄다.

브라우저 검증: `EASYDOC_PROMPT_CONTEXT_EXPLANATION_VERSION=R3`와 사전 조회를 켠 로컬 fake 스택에서 전체 E2E **27 passed, 1 skipped**(Toss test billing)를 확인했다. 최초 실행의 사전 조회 실패는 로컬 플래그 누락이었고 재실행에서 통과했다. 개인정보 경고 2개 fixture는 기존 4단어 입력 정책에 맞게 고쳤으며 경고 헤더·확인 후 재전송·등록 성공 단언은 유지했다.

프런트 최종 검증: `npm run check`, `npm run test -- --run`(**55 files, 729 tests**), `npm run build` 모두 통과했다. 기준선에서 이미 바뀐 관리자 크레딧 입력 label·버튼 이름을 뒤따르지 못한 테스트 2건도 실패를 재현한 뒤 조회 문구만 맞췄고, 0.1 단위 처리와 API 호출 단언은 유지했다.

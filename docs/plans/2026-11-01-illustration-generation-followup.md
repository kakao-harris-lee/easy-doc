# 요청형 이미지 생성 후속 구현·검증 계획

작성일: 2026-11-01 · 상태: **구현 재개, 최종 검증·리뷰 전**.

이전 장비의 Git 저장소 밖에 있던 Kimi 원본 계획은 확보하지 못했다. 이 문서는
[PR #197](https://github.com/kakao-harris-lee/easy-doc/pull/197)의 `dee97546`·`6569cbe3`,
[R7 수정 계획](2026-09-24-contextual-illustration-correction.md),
[현행 HTTP 계약](../../contracts/easy-doc-v1.yaml)을 근거로 복원한 실행 계획이다.
원본을 대체하는 확정 요구사항으로 취급하지 않으며, 원본 입수 시 구성·대체텍스트 편집과
출력 범위를 먼저 대조한다.

## 1. 시작 상태와 범위

원격 main `8f213aa3`에는 grounding 통합 [#194](https://github.com/kakao-harris-lee/easy-doc/pull/194),
숫자-only 제안 차단 [#195](https://github.com/kakao-harris-lee/easy-doc/pull/195),
이미지 provider 경계 [#196](https://github.com/kakao-harris-lee/easy-doc/pull/196)가 포함돼 있다.
PR #197의 `6569cbe3`는 V51 작업·암호화 자산·정산·인증 API·프런트 흐름을 추가한 미병합 기준점이다.
이전 빌드·fake E2E 기록과 #196의 CI는 후속 수정 최종 diff의 검증으로 전용하지 않는다.

이번 범위는 사용자 명시 생성 → 검토 → 저장 본문 문맥 옆 적용/제거 → 재방문 복구와
결과 불명확 처리의 완성이다. 기본 변환·텍스트 출력의 동작을 유지한다. 생성 이미지가
DOCX/HWPX/TXT에 포함되지 않는다는 안내를 유지하고 이미지 개별 다운로드는 기존 계약을 따른다.
실제 유료 호출, 운영 활성화·배포, 새로운 평가 하네스, 생성 후 편집 API 확장은 포함하지 않는다.

## 2. 실행 조각과 완료 기준

| 조각 | 변경 경계 | 완료 기준 |
|---|---|---|
| 결과 불명확 정산 | `backend-kotlin/`의 생성 provider·runner·작업 처리 및 직접 연결된 기존 테스트 | provider 호출 뒤 timeout/I/O로 결과를 알 수 없으면 작업 실패 코드와 호출 원장을 `outcome_unknown`으로 일치시킨다. 고객 예약은 한 번 반환하고 provider 자동 재호출·추가 소비는 없다. 확정 provider 오류 및 잘못된 이미지 결과와 구분한다 |
| 본문 문맥 적용 | `frontend/`의 생성 패널·검수 화면·API helper 및 직접 연결된 테스트·기존 Playwright | 생성 전 대체텍스트를 편집하고 계약의 canonical SHA-256으로 구성 revision을 보낸다. 생성 완료만으로 적용하지 않는다. 사용자가 확인 후 적용하면 `body_range`의 저장 본문 문맥 옆에서 이미지와 대체텍스트를 확인한다. 제거하면 본문 미리보기에서 사라지고 자산·소비 기록은 유지된다. 새로고침·재방문 시 서버 적용 상태를 복구한다 |
| stale·충돌 복구 | 위 두 영역의 기존 계약 경계 | 수정 중인 본문에는 이전 자산을 적용하지 않고, 본문 저장으로 revision이 바뀌면 이전 자산을 현재 미리보기에서 제외한다. 서버는 stale 적용을 거부하고 최신 revision을 아는 제거는 허용한다. 409나 응답 유실 후에는 최신 상태를 다시 읽으며 생성 POST를 자동 재전송하지 않는다 |
| 문서·통합 | `docs/plans/`와 별도 통합 worktree | 구현·미검증·원계획 확인 필요 항목을 구분한다. 독립 변경을 검토 후 통합하고 같은 최종 diff에서 표준 검증·fake E2E·독립 리뷰를 완료한다 |

작업은 `6569cbe3`에서 분기한 전용 worktree에서 나누며 실행 에이전트는 재위임하지 않는다.
문서 검증·수정 회차는 최대 2회다. 제품 코드 변경 후 해당 영역의 이전 검증·리뷰는 최종 통과
근거로 사용하지 않고 다시 확인한다. 범위나 외부 승인이 필요해지면 해당 항목과 근거를 보고한다.

## 3. 대체텍스트와 현 계약의 한계

생성 POST는 `configuration.scenes`, `preserved_facts`, `alt_text` 및 그 SHA-256
`configuration_revision`을 받는다. 이번 범위에 생성 전 대체텍스트 1~300자 편집을 포함한다.
API helper가 계약의 UTF-16 길이 기반 canonical 직렬화와 SHA-256을 구현하며 한국어·emoji
입력의 digest를 검증한다. 서버는 장면·보존 사실을 최신 제안의 항목으로 제한한다.
대체텍스트의 길이·공백 검사가 자유 편집의 의미적 정확성 검증은 아니므로 사용자가 원문과
생성 결과를 확인해야 한다. 원본 계획을 확보하면 생성 전·후 편집 요구의 일치 여부를 대조한다.

생성 후 적용 PUT은 `expected_content_revision`, `expected_asset_revision`, `applied`만 받는다.
생성 후 대체텍스트를 수정·저장하는 API는 없다. 이번 작업은 생성 후 대체텍스트 표시·검토를 보존하며,
원본 계획에 생성 후 편집이 필수라면 별도 계약 변경 단위(Kotlin DTO/계약 테스트와
`frontend/src/api/` 포함)를 정한 뒤 진행한다. 화면만 바꿔 저장되지 않는 편집을 제공하지 않는다.

## 4. 검증과 종료 조건

1. 관련 기존 테스트로 timeout/I/O와 정산, 본문 문맥 옆 표시·적용·제거·재방문·stale 갈래를 확인한다.
2. 백엔드 `cd backend-kotlin && ./gradlew build`, 프런트
   `cd frontend && npm run check && npm run test -- --run && npm run build`를 최종 통합 diff에서 통과시킨다.
3. `docker compose config`와 기존 `frontend/e2e/run-local.sh illustration-suggestions.spec.ts`를
   격리한 fake 스택에서 실행한다. 운영 `.env`·DB·포트·실제 API 키를 재사용하지 않는다.
4. 독립 리뷰에서 남은 결함을 확인하고 수정 후 관련 검증·리뷰를 반복한다. 마지막 변경과 같은
   diff의 결과, 실행 명령, 실패·건너뜀·미실행 항목을 PR/완료 보고에 기록한다.

무거운 빌드·Testcontainers·Docker 기반 테스트 전에는
`pgrep -af 'GradleWrapperMain|GradleWorkerMain' | grep -v pgrep`와 `free -g`를 확인한다.
다른 프로젝트·worktree의 무거운 빌드가 있으면 종료까지 기다리며 호스트에서 하나만 실행한다.
가용 메모리가 6GB 미만이면 시작하지 않고 보고한다. 예상치 못한 빌드 종료 시
`journalctl -u earlyoom`을 먼저 확인한다. 새 검증 스크립트나 하네스는 만들지 않는다.

현재 문서는 완료 증거가 아니다. 최종 코드·동일 diff의 필수 검증·독립 리뷰가 통과해야
이번 fake 기반 후속 구현을 완료로 보고하며, 실제 이미지 품질·R7 출시 완료와 구분한다.
검증·리뷰 완료 후 PR #197 브랜치에 최종 변경을 push하고 설명을 최종 범위와 증거에 맞춘다.
PR 병합과 운영 배포는 이번 실행 범위에 포함하지 않는다.

## 5. 유료 평가와 운영 전환 전 남은 결정

승인된 유료 호출 총 달러 예산은 없고 실제 이미지 생성·품질·지연·청구액은 미검증이다.
2026-11-01 [공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst)와
[Images API 문서](https://developers.openai.com/api/reference/resources/images/methods/generate)에서
`gpt-image-2.5-sunburst`와 `medium`, PNG·base64 요청/응답 지원을 확인했다. 대상 계정의
접근 권한과 실제 요청 성공은 확인하지 않았다.

공식 모델 문서의 Standard 요율은 100만 토큰당 텍스트 입력 US$5, 이미지 입력 US$8,
이미지 출력 US$30이다. 같은 문서는 GPT Image 2 계산기로 2.5의 토큰 소비량을 추정하지
말라고 안내하므로 1024x1024·medium 한 장의 실제 비용으로 환산하지 않는다. 실제 단가와
출력 토큰 수에 따른 비용·상한 및 provider 보존 조건은 유료 실행 전에 확인해야 한다.

유료 평가를 시작하려면 대상 표본·호출 수·명시적인 총 달러 상한·사용량 관찰/중단 방법을
정하고 사용자 승인을 받아야 한다. `provider-cost-usd-per-image`는 성공 호출의 추정 비용
구성값이며 누적 예산을 차단하는 장치가 아니다. `credits-per-image`는 고객 이용량으로 실제
provider 비용과 구분한다. 기존 텍스트 평가의 US$50 승인도 이번 이미지 평가에 전용하지 않는다.
기능 토글의 기본 OFF와 유료 실행 조건을 유지하며, fake 통과를 실측 이미지 품질이나
독자 이해 효과의 근거로 보고하지 않는다.

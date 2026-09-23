# ER-16 — R7 그림 배치·미리보기·출력 범위 구현 보고

작성일: 2026-09-23 · 브랜치 `feat/er-16-r7-illustration-placement` · 리비전 `12c04633`

## 범위

[실행 계획](../plans/2026-09-18-easy-read-delivery-plan.md)의 ER-16 「그림 배치·출력 범위 결정·미리보기·실사용 확인」과 [인수 기준](../plans/2026-09-18-easy-read-validation-release.md) AC-R7-b 「그림을 적용 → 웹/파일 결과 확인 → 의미 불일치 없음, 이미지 미포함 출력은 사용자에게 명시」를 닫는다. [구현 명세](../plans/2026-09-18-easy-read-implementation-spec.md) §9의 기본안대로 v1은 웹 미리보기와 기존 텍스트 출력 유지이며, DOCX/HWPX 이미지 삽입은 별도 범위다.

## 설계 결정

- **배치 좌표는 쉬운 글 줄 index다.** 서버 `splitUnits`와 프런트 `draft.split('\n')`이 같은 줄 배열을 쓰므로 `easy_unit_index`(0-based)로 맞췄다. 세그먼트 지도는 저장하지 않는 파생물이라 좌표로 쓰지 않았다.
- **변환당 배치 집합 하나, 이력 없음.** `PUT`은 현재 집합을 통째로 교체하고 빈 목록은 행을 지운다. 저장 시점의 `content_revision`에 묶고, 본문이 바뀌면 `stale=true`로 돌려주되 서버는 지우지 않는다 — 검수자가 위치를 확인하고 다시 저장한다. R6의 「본문 수정 후 이전 위치 즉시 비활성화」와 같은 규칙을 화면이 따른다(stale이면 미리보기에서 그림을 내린다).
- **CAS.** `expected_content_revision`은 필수다(`ReviewSupportItemUpdateRequest` 관례). 현재와 다르면 409(`CONTENT_REVISION_CONFLICT_MESSAGE`).
- **검증은 도메인에서 422로.** 개수 > 10, index 중복, 본문 줄 수 초과, 미검수·미존재 asset(같은 메시지 「선택할 수 없는 그림입니다」). `maxItems`를 bean validation으로 두면 도메인 메시지가 제네릭 스키마 detail에 가려져 도메인 예외로만 잡았다.
- **좌표는 본문 취급이라 봉인한다.** (줄 index, asset_id)만으로도 「3번째 줄이 비용 안내」처럼 본문을 유추할 수 있어 명세 §10에 따라 AES-GCM payload로 저장한다. AAD는 `EncryptedField.ILLUSTRATION_PLACEMENTS` + 행 id, 코덱은 탭 구분 평문 4KiB 상한(DB CHECK 4,160B). `SealedStores`·`EnvelopeRotation`·`KeyRotationBatch`에 등록해 키 회전 대상에 넣었다. `EnvelopeRotationTest`의 전수 대조가 등록 누락을 컴파일 단계에서 막는다.
- **소유·만료 술어는 SQL 안에.** `findOwned`·`replaceOwned`·`deleteOwned`가 `d.user_id`·`retention_expires_at`를 SQL로 건다(`review_assessments` 선례). 회전 경로 3문장은 선례와 같이 술어 없이 인구조사에 등록했다.
- **토글은 ER-15 것을 재사용.** 새 capability 플래그를 만들지 않았다.
- **파일 출력 불포함을 명시.** 패널 안내문(항상)과 다운로드 버튼 옆 안내(배치가 있고 stale이 아닐 때)로 「배치한 그림은 파일에 들어가지 않습니다」를 보여 준다. 내보내기 서비스는 건드리지 않았다.

## 변경 파일

- 계약 2.45.0: `GET`·`PUT /conversions/{conversion_id}/illustration-placements`, 스키마 `IllustrationPlacementsRequest`·`IllustrationPlacementsResponse`·`IllustrationPlacement`, `x-input-limits.max_illustration_placements: 10`.
- core `illustration/IllustrationPlacement.kt`(+테스트) — `IllustrationPlacement`·`IllustrationPlacements`(정규화·불변식·`validateAgainst`)·메시지 상수. `crypto/StoredContent.kt`에 `EncryptedField.ILLUSTRATION_PLACEMENTS`.
- application `illustration/IllustrationPlacementCodec.kt`·`IllustrationPlacementRepository.kt`·`IllustrationPlacementService.kt`(+테스트). `document/DocumentPorts.kt`(`SealedStores`)·`EnvelopeRotation.kt`·`KeyRotationBatch.kt` 회전 등록.
- infrastructure `db/migration/V34__illustration_placements.sql`, `illustration/JdbcIllustrationPlacementRepository.kt`, `IllustrationsConfiguration.kt`·`DocumentConfiguration.kt` 조립, `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`·`DocumentStorageContextTest` 인구조사 갱신.
- api `illustration/IllustrationPlacementController.kt`(+DTO, `IllustrationPlacementContractTest` 14건), `AuthenticatedEndpoints`, `AuthSliceBeans`, `SensitiveToStringReachTest`(336→342), `ValueSlotInvariantReachTest` 표본.
- frontend `api/types.ts`·`client.ts`, `components/IllustrationPlacementPanel.tsx`(+테스트 7건), `ReviewEditor.tsx` 배치 패널·다운로드 옆 안내(+테스트 5건), `e2e/illustration-placement.spec.ts`, `e2e/contract.ts`.

## 검증

- 백엔드 `./gradlew build` — BUILD SUCCESSFUL. core 778 / application 620 / infrastructure 1,101(Testcontainers 실 DB 포함) / api 754 / worker 63 = 3,316개 테스트, 실패 0. ktlint·detekt 포함.
- 프런트 `npm run check` exit 0, `npm run test -- --run` 60개 파일/779개 테스트 통과, `npm run build` 성공.
- Compose 기본·CI·E2E 구성 검증 통과.
- e2e `frontend/e2e/run-local.sh --grep "그림 배치"`(일회용 스택, fake LLM): **1 통과 / 0 실패**. 흐름: 업로드 변환 → 1번째 줄에 「기관 방문」 선택 → 저장(PUT 200) → 미리보기에 대체텍스트 그림 → 다운로드 옆 미포함 안내 → 본문 두 줄로 고쳐 저장 → 「현재 본문과 다름」 배지와 미리보기 그림 없음.
- 유료 provider 호출 0회. 실제 LLM 호출·배포·외부 전송 없음.

## 리뷰 (리비전 `12c04633`, 저자와 다른 패스, 모두 sonnet)

| 레인 | 판정 |
|---|---|
| code-reviewer | BLOCKER 0 · HIGH 0 · MEDIUM 1 · LOW 2 — 머지 가능 |
| privacy-gate | BLOCKER 0 · HIGH 0 · MEDIUM 1 · LOW 0 — 통과(조치 권장) |
| contract-keeper | BLOCKER 0 · HIGH 1 · MEDIUM 1 · LOW 1 — HIGH 조치 후 머지 가능 |
| migration-reviewer | BLOCKER 0 · HIGH 0 · MEDIUM 0 · LOW 1 — 머지 가능 |

리뷰어가 코드로 확인한 것 중 중요한 것: 배치 저장이 본문 검수 저장과 같은 행 잠금(`lockOwnedForReview`)으로 직렬화되고 CAS·검증·봉인·쓰기가 한 트랜잭션 안에 있음, upsert의 `WHERE EXISTS(소유+보존)`가 거짓이면 `ON CONFLICT`가 붙지 않아 타인 변환은 0행, `EnvelopeRotationTest`의 exhaustive `when`이 회전 등록을 컴파일로 강제, V1~V33 바이트 단위 무변경·V34는 신규 테이블뿐, 계약 삭제 줄은 version 한 줄뿐.

지적과 조치(`71d5bec5`):
- **HIGH**(contract-keeper) nullable `placements_content_revision`에 `@JsonInclude(ALWAYS)`가 없어 키 보존이 실측되지 않았다 → 애너테이션 추가, 원문 JSON에 `null` 키가 남는 계약 테스트 추가.
- **MEDIUM**(code-reviewer) 봉인 열 쓰기 가드가 `UPDATE <table>`만 잡아 `ON CONFLICT DO UPDATE SET` upsert를 못 봤다 → 스캐너 확장. 기존 upsert 4문장이 새로 잡혔고 전부 준수였다(가드의 사각지대였을 뿐).
- **MEDIUM**(privacy-gate) 단건 `IllustrationPlacement`의 기본 toString이 좌표·asset을 찍었다 → 마스킹 재정의와 단언. 자동 게이트 표본은 `INERT_VALUES` 때문에 생성되지 않아 toString 재정의가 유일한 방어선임을 KDoc에 적었다.
- **MEDIUM**(contract-keeper) 11개·중복 index PUT의 HTTP 422 테스트 없음 → 2건 추가(컨트롤러가 도메인 객체를 직접 생성하는 실제 경로).
- **LOW**(code-reviewer) 코덱 decode 손상 입력 테스트 3건 추가. **LOW**(contract-keeper) 프런트가 10개 상한을 사전 반영(초과 시 저장 차단·안내). **LOW**(code-reviewer) 메시지 상수가 core에 있는 것은 core 도메인 검증이 쓰는 상수라 그대로 둔다. **LOW**(migration-reviewer) 토글 롤백은 마이그레이션 범위 밖 표기이며 토글은 ER-15 것을 재사용한다.

조치 후 `./gradlew build` 통과(core 779 / application 623 / infrastructure 1,103 / api 757 / worker 63), 프런트 780 테스트 통과.

## 남은 한계

- DOCX/HWPX/TXT 파일에 그림을 넣지 않는다. 명세의 「서식 검증 후 별도 범위」 그대로다.
- 리포지토리 전용 실 DB 테스트(upsert·타인 은닉·cascade)는 신설하지 않았다. `review_assessments`도 같은 전례이며, 빈 배선은 `DocumentStorageContextTest`(실 Postgres)로 검증된다. 소유 술어는 `OwnershipPredicateGuardTest` 인구조사가 SQL 문장 단위로 강제한다.
- 두 검수자가 같은 revision에서 동시에 PUT하면 마지막 저장이 남는다(본문 저장과 같은 정책). 배치 자체에 별도 revision을 두지 않았다.
- 이 세션의 Claude Code 환경은 MDM 관리형 프록시 때문에 GitHub에 닿지 못해 푸시·PR 생성·PR 코멘트는 사용자 터미널에서 실행한다.

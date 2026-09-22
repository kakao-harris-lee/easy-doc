# ER-14 / AC-R6 근거 있는 추가 설명 구현·검증

사용자 요청: 백로그의 마지막 미구현 블록 중 첫 단계인 ER-14(R6 근거 있는 추가 설명)를 구현하고, PR #140에 「다음 기회 반영」으로 남긴 미조치 LOW 2건을 함께 처리한 뒤 PR을 만든다.
기준 커밋은 `dfe7266c4c298a4818ee3f7b4f8bb71e5a8fcc1c`이다.

## R6: 검수 화면의 「용어 설명」 패널

쉬운 글에는 낯선 행정 용어가 남는다. 검수자는 그 용어가 무슨 뜻인지 확인할 근거가 화면에 없어 사전을 따로 찾거나 넘겨짚는다. R6는 저장된 현재 본문에서 **검수를 마친** 사전 정의가 있는 용어만 골라, 용어마다 한 건의 설명을 접기/펼치기 카드로 보여준다.

계약은 `contracts/easy-doc-v1.yaml` **2.43.0**에 신설 `GET /conversions/{conversion_id}/explanations`(200/401/404/409/422/500/503)와 `ExplanationsResponse`·`Explanation`·`ReviewSourceAnchor` 스키마를 더했다. **additive**이며 기존 오퍼레이션·스키마를 바꾸지 않는다. 기능 토글 `easydoc.explanations.enabled`의 기본값은 **OFF**이고, 꺼져 있으면 엔드포인트가 404이며 화면은 섹션 자체를 그리지 않는다.

동작 규칙은 다음과 같이 정했다.

- 같은 용어가 여러 번 나오면 처음 나온 자리의 정의 하나로 합치고, 활용형이 여러 개면 근거 색인을 합친다.
- 본문에서 위치를 찾지 못하면 근거 없이 남기고 「원문 위치를 찾지 못했습니다.」로 표시한다 — 없는 위치를 추정하지 않는다.
- 본문이 저장된 버전과 다르면(dirty) 「현재 본문과 다름」 배지와 함께 저장 후 다시 확인하도록 안내한다.
- 사전이 꺼져 있는데 R6를 켜면 **빈 목록이 아니라 구성 오류로 즉시 실패**한다(배포 실수가 정상 응답으로 위장하지 않게).

구현은 계층 경계를 그대로 따랐다. `core`에 도메인(`Explanations.kt`, `deriveExplanations`), `application`에 포트 `ReviewedDefinitionSource`와 유스케이스 `ExplanationsService`, `infrastructure`에 어댑터 `DictionaryReviewedDefinitionSource`·조립 지점 `ExplanationsConfiguration`·설정 `ExplanationsProperties`, `api`에 `ExplanationsController`를 두었다. 저장·보존 변경 **없음**, 새 마이그레이션 **없음**, 새 외부 호출 **없음**.

`AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS`에는 하위 경로를 **명시적으로** 추가했다 — 인터셉터 패턴은 하위 경로를 덮지 않는다.

## 함께 처리한 미조치 LOW 2건

PR #140에 「다음 기회 반영」으로 기록했던 두 건을 이 변경 단위에서 닫았다.

- `DocxTableMetadata.kt`의 `spanValue`가 숫자가 아닌 값을 만났을 때의 처리. 반환 타입을 `Int?`로 좁히고 호출부 두 곳에서 보수적으로 `?: true`로 떨어뜨리며 이유를 KDoc에 남겼다(2차 방어선이 이미 있던 자리다).
- ~~`V33__review_history.sql` 주석 한 줄의 클래스명 표기 `ReviewHistoryCodec` → `ReviewHistorySnapshotCodec`~~ — **되돌렸다.** 첫 커밋(`0dd2dfc4`)에 넣었으나 migration-reviewer가 차단했다. Flyway는 SQL 마이그레이션 checksum을 파일 전체 줄 단위 CRC32로 계산하며 주석을 제외하지 않는다(`flyway-core-12.4.0` `ChecksumCalculator.java:63-87` 직접 확인). V33은 PR #140으로 이미 `origin/main`에 병합돼 적용된 파일이므로, 주석만 고쳐도 그 환경은 다음 부팅에서 `FlywayValidateException`으로 기동이 거부된다. 커밋 메시지의 「checksum 불변(양쪽 CRC32 동일)」 주장은 `--` 줄을 건너뛰는 가정을 넣은 잘못된 재현이었고 철회한다. 표기 정정은 이 파일이 아니라 다음 신규 마이그레이션이나 해당 Kotlin 클래스 KDoc에서 다룬다.

## 검증·리뷰

- **백엔드** `./gradlew build` — BUILD SUCCESSFUL. 5개 모듈 3,241개 테스트, 실패 0 (core 756 / application 596 / infrastructure 1,096 / worker 63 / api 730). detekt·ktlint 포함.
- **프런트** `npm run check` exit 0, `npm run test -- --run` 58개 파일/756개 테스트 통과 exit 0, `npm run build` exit 0.
- **Compose** `compose.yml`, `compose.yml + compose.ci.yml --profile ci`, `compose.yml + compose.e2e.yml` 구성 검증 통과. (`compose.e2e.yml` 단독은 오버라이드 파일이라 image/build 컨텍스트가 없어 exit 1이 나는데, 이는 이번 변경 이전부터의 성질이며 stash로 확인했다.)
- **E2E** `frontend/e2e/run-local.sh --grep "R6 용어 설명"`(일회용 스택 + fake LLM) — **1 통과 / 0 실패**. 이 실행에서 아래 스펙 결함 한 건을 찾아 고쳤다.
- 신규 게이트: `ExplanationsContractTest`(계약 필드·헤더·404/409/401), `ExplanationsServiceTest`, `ExplanationsTest`, `DictionaryReviewedDefinitionSourceTest`, `ExplanationsPanel.test.tsx`, `ReviewCapabilityFlagsTest`에 기능 독립성 검사 추가.
- **유료 provider 호출 0회.** 실제 LLM 호출·배포·외부 전송 없음.

### e2e에서 실제로 드러난 것

첫 실행은 스펙이 422로 떨어졌다. 업로드 원문 `'평범한 안내문입니다.'`가 공백을 포함하지 않아 서버 하한 `MIN_CONVERTIBLE_WORDS = 4`(`core/document/DocumentLimits.kt`)에 걸렸고, `DocumentService`가 `too_short`로 거절한 것이었다. 원문을 4어절 이상으로 고쳐 하한을 넘겼다.

이 과정에서 오케스트레이터가 **잘못된 진단**을 한 구간이 있었다. 커밋된 사전 색인(`easy_dict.index.json`)을 조사해 검수 표시(`v`)를 가진 항목이 0개임을 확인하고, 그것을 근거로 「e2e 스택에도 검수된 정의가 없어 목록이 비어 있다」고 단정해 스펙의 접기/펼치기 단언을 빈 목록 단언으로 바꿔 버렸다. 실제로는 `ExplanationsConfiguration`이 `e2e` 프로필에 고정 응답 정의원(`fakeReviewedDefinitionSource`, 표제어 「서류」 한 건)을 주입하고 있었고, 스택은 `profile=api,local,e2e`로 뜬다. 두 번째 실행의 실패 스냅샷이 카드가 실제로 렌더됨을 보여 주어 오진이 드러났다. 스펙을 원래의 접기/펼치기 단언으로 되돌리고(패널 `region`으로 범위를 좁혀 원문·결과 패널과의 strict mode 충돌을 피하게 했다) 세 번째 실행에서 통과했다.

**색인 조사 자체는 여전히 유효하다** — 다만 그것이 말하는 대상은 **운영 경로**다. `dictionaryReviewedDefinitionSource`(비 e2e)는 커밋된 색인을 읽는데 2,177개 항목 중 `v`를 가진 것이 없어 전부 `UNVERIFIED`로 읽히므로 **운영에서는 지금 빈 목록이 난다**. e2e가 재는 것은 그 데이터가 실렸을 때의 화면 동작이다.

## 남은 한계

- **커밋된 사전 색인 2,177개 중 검수 표시(`v` 키)를 가진 항목이 0개다.** 정의문이 채워진 항목은 423개 있으나 전부 `UNVERIFIED`로 읽히므로 `REVIEWED` 필터를 통과하는 항목이 없어 **운영 경로는 지금 빈 목록을 낸다.** 코드가 아니라 데이터 파이프라인 선행 조건이며, 사전 export가 `v: "reviewed"`를 내보내기 시작하면 채워진다. 이 PR은 그 배선과 게이트를 세우는 단계다.
- e2e가 접기/펼치기를 잴 수 있는 것은 `e2e` 프로필의 fake 정의원 덕분이다. 운영 데이터가 실리기 전까지 이 화면 동작은 실제 색인으로는 재현되지 않는다.
- `V33__review_history.sql` 주석의 클래스명 표기(`ReviewHistoryCodec`)는 여전히 낡은 채다. 이미 적용된 마이그레이션은 손대지 않는다는 판정에 따라 이 PR에서는 고치지 않았다.
- 계약 205행 근처가 `docs/migration/_workspace/...`를 가리키는 것은 이번 변경 이전부터 있던 낡은 포인터다(그 디렉터리는 저장소가 의도적으로 미추적).
- ER-15/ER-16(R7 그림 대체텍스트·배치)은 미착수다.

## 최종 검증 결과 (2026-09-22)

E2E를 포함한 전체 검증을 실행했다. 이 단계에서 유료 외부 호출은 없었다(E2E는 fake LLM 프로필).

- **백엔드 `./gradlew build` 통과** — 5개 모듈 3,241개 테스트, 실패 0.
- **프런트 `npm run check`·`npm run test -- --run`·`npm run build` 통과.**
- **Compose 구성 검증 통과** — 기본 / CI 오버레이 / E2E 오버레이.
- **E2E `frontend/e2e/run-local.sh --grep "R6 용어 설명"`: 1 통과 / 0 실패.** 첫 두 실행은 실패했고(422 업로드 하한, 이어서 오진에 따른 잘못된 단언) 세 번째에서 통과했다. 실패 이력과 원인은 위 「e2e에서 실제로 드러난 것」에 적었다.
- 이 문서는 운영 활성화나 사용자 파일럿 완료를 뜻하지 않는다. 토글 기본값은 OFF다.

## 리뷰 회차 조치 (2026-09-22, 리비전 `0dd2dfc4` 심사 → 후속 커밋)

PR #141에 4개 레인(code-reviewer·privacy-gate·contract-keeper·migration-reviewer, 모두 sonnet)의 판정을 남겼고 BLOCKER 2건·MEDIUM 2건이 나왔다. 조치는 별도 커밋으로 얹었다.

- **privacy-gate BLOCKER — `ExplanationResponse.toString()`이 `term`을 그대로 찍음.** `@UserContent`를 달고 `term`·`definitionSource`·`explanation`을 길이만 남기도록 바꿨다. 실행 에이전트가 처음에 `definitionSource` 값을 그대로 두자 `SensitiveToStringReachTest`가 여전히 실패했다 — 이 DTO의 `definitionSource`는 core의 enum이 아니라 `String`이라 `@UserContent`로 넓힌 순간 표식 심기 대상이 된다. JSON 직렬화는 그대로다. `KNOWN_SENSITIVE_TYPES` 바닥 목록에 `ExplanationResponse`를 넣어 이 타입이 게이트 밖으로 빠지는 것을 막았고, `ExplanationsContractTest`에 `toString()` 단언을 추가했다.
- **migration-reviewer BLOCKER — 이미 적용된 `V33__review_history.sql` 주석 수정.** hunk를 되돌렸다(위 「함께 처리한 미조치 LOW 2건」 참고).
- **code-reviewer MEDIUM — 422 직접 단언 없음.** `GET /conversions/not-a-uuid/explanations` → 422, 본문 `detail` 키 단언을 추가했다.
- **code-reviewer MEDIUM — PR 본문과 e2e 로그 불일치.** 본문은 이미 실제 실행 결과(1 통과 / 0 실패)로 갱신돼 있었다. 추가 조치 없음.
- code-reviewer LOW(`DocxTableMetadataTest` row-offset 커버리지)는 `gridBefore 값이 숫자가 아니면 행 오프셋으로 판정한다` 테스트가 이미 있어 조치하지 않았다.

검증: `./gradlew :api:test --tests ExplanationsContractTest --tests SensitiveToStringReachTest` 통과, `./gradlew build`(5모듈, ktlint·detekt 포함) BUILD SUCCESSFUL. 프런트·계약·마이그레이션은 이 회차에서 바뀐 파일이 없다.

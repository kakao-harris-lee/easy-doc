# `04_documents-c6r2` — Claude 독립 리뷰 (1회차)

> **회차 성격**: 3단계 게이트의 **1단계**. codex 레인이 같은 범위를 병렬·독립으로 보고 있고,
> 이 문서는 그 결론을 **참조하지 않았다**(격리가 설계다). 교차 종합은 리더의 2단계 재호출에서
> `04_documents-c6r2_cross.md` 로 낸다. **이 문서만으로 Phase 종료 조건 충족을 보고하지 않는다.**

- **작성**: `migration-reviewer` (Opus)
- **어간**: `04_documents-c6r2` (리더 지정값 그대로. `04_documents-c6_*.md` 세 파일은 1회차 산출물이며 건드리지 않았다)
- **범위**: `git diff 318bd36..b4646ee` — 커밋 7개(비docs 4개)
- **범위 밖 (판정하지 않음)**: 작업 트리의 미커밋 수정 5건(`.claude/agents/codex-reviewer.md`, Kotlin 테스트 4)

## 1. 리뷰 범위와 참조한 기준 문서

### 1.1 대상 커밋

| 커밋 | 내용 | 이 리뷰에서 붙은 지적 |
|---|---|---|
| `b4c2fda` | G-γ 차단 6건 조치 — K-1 하한선 인구조사 신설 · G-1 · G-2 · S-1 · S-2 · S-5 · 층 ⓑ · DC-26·DC-27 | **C-1**, **C-2**, **C-3**, M-1, M-2, M-4, R-2 |
| `6a9ca8b` | 리더 판정 — 바닥 편입 · 라쳇 둘 · 단언 하한 표 | M-2, M-3 |
| `6c970b6` | 응답 계약 위반 둘 — 완료 전 노출 강제자 신설 · 500 저장 문구 | R-1 |
| `4ac13ec` | 위 ⑴ 의 대상을 결과 필드 셋 → 아홉으로 넓힘 + 분모 결함 수정 | (지적 없음 — §3.1 참조) |
| `595ed3a`·`bf9aeff`·`b4646ee` | 원장 L-㉙·L-㉚·L-㉛ | M-5, M-6 |

### 1.2 참조한 기준 문서 절

- 계획 `docs/plans/2026-08-11-kotlin-react-migration.md` §2.2(14개 엔드포인트 계약) · §2.3(보안 불변식) · §3.1·§3.2(모듈 경계·기술 고정) · §4.5·§4.6(parity 위험 지점) · §5(Phase 4 종료 조건 · 즉시 중단 기준 7) · §6(Contract 게이트 · 보장 재배치 추적)
- `contracts/easy-doc-v1.yaml` — `:931-947`(`x-private-response-headers.applies_to`·`rationale`·`x-failure-mode-shift`) · `:1020-1025`(하한선 10곳의 출처) · `:1132-1134`(개인정보 응답 목록) · `:1306-1340`(`POST /documents` 검사 순서·복합 결함 조항) · `:2107-2123`(`InternalError` 예시 셋) · `:2425-2483`(`ConversionResponse` `required` 열셋) · `:2486-2500`(`DocumentListItem`)
- `.claude/skills/kotlin-migration/SKILL.md` — 「선언한 범위와 실제 도달을 대조한다」(규칙 4 은폐형 거부권 · 규칙 7 게이트 깊이 · 규칙 8 라쳇 상환) · `{scope}` 정본 표(`:208-236`) · 리뷰 게이트 절
- `CLAUDE.md` — 아키텍처 규칙 2(마스킹 선행) · Kotlin 주석 규약 · 리뷰 회차 7축 · 범위 대조
- `docs/migration/_workspace/00_progress.md` — 리뷰 커버리지 표(`:1584-1588`) · 리뷰 이연 장부(`:1609-1618`) · L-㉙·L-㉚·L-㉛ (**읽기만 했다** — 리더 소관)

### 1.3 이 회차에 실제로 돌린 실행

| 실행 | 결과 |
|---|---|
| `uv run pytest tests/test_kotlin_gate_reach.py -k "캐시 or 실수_상한 or 이름_튜플 or 수치_상수가_전부_분류"` | **11 passed · 287 deselected · exit 0** — 신설 라쳇 셋이 살아 있고 `skipped` 0(즉 이력 부재로 조용히 넘어간 케이스 없음) |
| `git rev-list` 로 이연 장부 11개 SHA × 커버리지 3개 범위 교차 | **교집합 0** — 「리뷰됐는데 `닫힘` 이 `-` 인 행」은 오늘 없다(M-6의 전제 확인) |
| `grep -c '^\s*@Test'` · `grep -oE '\bassert[A-Za-z]*\('` (변경된 5개 클래스) | M-3의 창 수치 산출 |
| `grep -rn "MAX_TIMING_RATIO"` 전수 | 사본 **정확히 4개** — `RATCHET_CEILING_DECIMAL_PINS` 열거는 오늘 완전(M-4의 전제) |
| `grep -rn "NON_NULL\|JsonInclude\|default-property-inclusion"` | **0건** — Q2의 전제 확인 |
| `grep -rn "fetch-depth"` `.github/workflows/ci.yml` | `quality`(`:66`)·`kotlin`(`:284`)·`llm-lane`(`:1013`) 에 `fetch-depth: 0`. 두 잡이 `tests/test_kotlin_gate_reach.py` 를 경로 명시로 돌린다(`:198`, `:374`) → 이력 라쳇의 CI 도달 **확인됨** |

**미실행**: Kotlin `:test`(Testcontainers·Docker 필요) · 골든셋 · `scan_privacy_invariants.py` · 이 회차 지적의 음성 대조(§6 참조).

---

## 2. 심각도 척도

`codex-review` 스킬 §5 를 따른다. **차단**은 두 갈래를 같은 무게로 센다 — ① 계획 §5 즉시 중단
기준에 해당하는 일이 실제로 나는 경로, ② **그 사건을 탐지·차단하는 게이트가 무력화된 상태**.
차단 항목에는 **마감**(그 게이트가 처음 실제로 쓰이는 Phase)을 붙이고, 「착수를 차단하는가」의
판정은 리더에게 넘긴다.

---

## 3. 축별 지적

### 3.1 축 A — 계약 준수

#### ✅ 검토함 — 지적 없음 (명시 질문 1·2에 대한 답)

**Q1. 완료 전 노출 강제자의 네 자리 — 우회 경로가 있는가, 잘못된 값을 넣는 경로를 무엇이 잡는가.**

네 자리를 각각 밟았다.

| 자리 | 위치 | 판정 |
|---|---|---|
| 규칙 | `core/document/ConversionStatus.kt:10-24` | **항목마다 `exposesResult` 값을 강제한다** — 생성자 파라미터라 새 상태를 더하면 컴파일이 판정을 요구한다. 기본값 없음 ✓ |
| 아홉의 단일 정의 | `core/document/ConversionView.kt:23-31` `carriesResult` | 7개 nullable `any { it != null }` + 배열 둘 `isNotEmpty()` = 아홉 ✓ (뷰 전체 13 − 넷) |
| 집행 | `application/document/ConversionQueryService.kt:27-46` `beforeDone` | `exposesResult` 분기로 `completed`/`beforeDone` 을 가른다. **`beforeDone` 은 `open()` 을 아예 부르지 않아** 복호화가 돌지 않는다 ✓ |
| 되짚기 | `api/document/ConversionDtos.kt:60-86` `of` 의 `require` | 바이트를 만드는 자리에서 재판정 ✓ |

**우회 경로 — 오늘 없다.** `ConversionResponse` 를 조립하는 main 코드는
`ConversionController.kt:33` 의 `ConversionResponse.of(view)` **한 곳뿐**(`grep` 전수 확인).
`ConversionQueryService.read` 도 `ConversionController` 만 호출한다. `PUT`·`export` 미구현.

**잘못된 값을 넣는 경로를 무엇이 잡는가 — 세 갈래 전부 실제 단언이 있다.**

1. **상태 상수를 잘못 준 경우**(`ARCHIVED(..., exposesResult = true)`):
   `ConversionReadReachTest.kt:104-106`(HTTP 팔) 와 `ConversionQueryServiceTest.kt:95-97`(단위 팔)이
   `filter { it.exposesResult }.containsExactly(DONE)` 로 **상수 자체를 잰다.** `DONE` 을 `false` 로
   내려도 같은 단언이 빨개진다. → L-㉚ 이 남긴 「악용 비용 한 줄 × 탐지 없음」 칸이 닫혔다 ✓
2. **`carriesResult` 가 필드를 빠뜨린 경우**: `ConversionReadContractTest.kt:155-190` 의 매퍼 팔이
   **계약에서 계산한 아홉**(`schemaRequired(CONVERSION_SCHEMA) - BEFORE_DONE_FIELDS`)마다
   `base.copy(그 필드 = 값)` 을 만들어 `of()` 가 **전건 던지는지** 본다. 빠진 필드가 있으면
   그 키가 `assembled` 에 남아 빨개진다. **분모가 구현이 아니라 계약이다** ✓
3. **`beforeDone` 이 아홉 중 하나를 채운 경우**: 위 ⑵ 와, `ConversionReadReachTest.kt:96-142`(CR-3b)의
   완료 전 세 상태 전수 HTTP 대조가 잡는다. CR-3b 의 seed 가 결과 열 **아홉 전부**를 채우고
   (`MARK_DONE_SQL` + `reviewed_at = now()`) 응답에서 전부 비어 있어야 한다 ✓

**seed 가 공허해지는 것도 막았다** — `ConversionQueryServiceTest.kt:240-247` 이 `seedResults` 에서
`reviewedAt = Instant.EPOCH` · `missingPlaceholders = maskedLabels` · `failureCode = "ProviderUnavailable"`
로 아홉을 다 채운다. 종전 판(`reviewedAt = null`·`missingPlaceholders = emptyList()`)이면 그 필드의
케이스가 공허하게 통과했다. 이 자기 정정이 옳다.

**Q2. 계약 `required` 열셋 유지가 직렬화 경로 전부에서 보장되는가 — 그렇다.**

- 직렬화 경로가 **하나**다(위 표). `ConversionResponse` 의 13개 필드는 전부 `@get:JsonProperty` 명시.
- **`NON_NULL`·`JsonInclude`·`default-property-inclusion` 이 저장소 전체에 0건**(실행 확인). 즉 오늘 키 생략이 구조적으로 일어나지 않는다.
- 게다가 행위 단언이 둘 있다 — `ConversionReadContractTest` CR-1(완료 경로 최상위 키 정확 일치)과
  `ConversionReadReachTest.kt:126-128`(CR-3b, **완료 전 세 상태 전부**에서
  `body.keys == schemaRequired(CONVERSION_SCHEMA)` 정확 일치). 나중에 누가 `NON_NULL` 을 켜면
  스캔이 아니라 **행위**로 빨개진다 — 스캔형보다 좋은 처방이다 ✓
- `failed` 상태도 CR-3b 의 분모에 든다(`schemaEnum(STATUS_SCHEMA) - done` = pending·processing·failed).
  `FAILED.exposesResult = false` 로 실패 변환의 `masked_items` 가 빈 배열로 강제되는데, 이것은
  계약 `:2456`(*"`done`이 아니면 빈 배열"*)과 일치하며 계약 `:1132`(`masked_items[].original` 에 실제
  개인정보)를 볼 때 **개인정보 노출 면적이 줄어든 변경**이다 ✓

#### M-1 [수정 필요] `POST /documents` 의 **JSON 팔**은 새로 선언된 검사 순서를 구조적으로 지킬 수 없다

- **근거**
  - 계약 `:1306-1310` — `검사 순서: 파일 크기(413) → 추출(422) → 본문 길이(422) → **작업 공간: 형식(422) 다음 소유권(404)** → …`, 그리고 `:1311-1315` — *"**이 순서는 복합 결함에도 적용된다**"*. 조항의 주체는 **오퍼레이션**(`POST /documents`)이고, 같은 오퍼레이션이 두 입력을 받는다(`:1291-1296`).
  - 조치된 것은 **multipart 팔만**이다. `DocumentController.kt:59-68` 이 `rawWorkspaceId` 를 문자열로 넘기고 `DocumentService.kt:110-113` 이 본문 길이 뒤에 지연 파싱한다 ✓
  - **JSON 팔은 그대로다.** `DocumentDtos.kt:24-26` 이 `workspace_id` 를 `UUID?` 로 받으므로 파싱이 **Jackson 역직렬화 시점**, 즉 컨트롤러 메서드 본문보다 **앞**이다(`DocumentController.kt:39-48`). 따라서 `{"text": "<상한 초과>", "workspace_id": "not-a-uuid"}` 는 **본문 길이(422)가 아니라 작업 공간 형식(422)** 으로 응답한다 — 계약 순서의 역이다.
  - `detail` **모양까지 갈린다**: multipart 팔은 `InvalidInputException(INVALID_WORKSPACE_ID_MESSAGE)` → **문자열** `detail`. JSON 팔은 `HttpMessageNotReadableException` → `GlobalExceptionHandler.kt:119-124`·`:234-249` 를 지나 **배열** `detail`(`[{loc:["body","workspace_id"], msg:"Input should be a valid UUID", type:"uuid_type"}]`). 같은 오퍼레이션·같은 결함인데 응답 본문의 **타입**이 다르다.
- **테스트 공백(실측)**: `grep -rn '"workspace_id"' backend-kotlin/api/src/test` — JSON 팔로 **형식이 잘못된** `workspace_id` 를 보내는 테스트가 **0건**이다. JSON 팔의 `workspace_id` 케이스는 전부 유효 UUID(`DocumentEndpointReachTest.kt:299`, `:313-314`). DC-26·DC-27 은 둘 다 multipart 다.
- **왜 이 회차의 지적인가**: 「복합 결함에도 적용된다」 조항이 이 범위 **직전**에 신설됐고(계약 `:1311`, 2026-08-20), DC-26·DC-27 이 그 조항의 강제자로 이 범위에서 태어났다. 강제자가 선언 범위의 **절반**만 덮는다.
- **관련 종료 조건**: §6 Contract 게이트(status/**body**/error 일치) · Phase 4 `documents`
- **처방**
  1. `DocumentTextRequest.workspaceId` 를 `String?` 으로 바꾸고 `DocumentService.createFromText` 도 `store(...) { parseWorkspaceId(raw) }` 형태로 만든다 — **multipart 팔에 이미 있는 그 함수를 재사용한다**(`DocumentService.kt:154-158`). 두 팔의 파싱 자리와 오류 모양이 하나로 합쳐진다.
  2. DC-27 에 JSON 팔 케이스를 더한다 — 본문 상한 초과 + 형식 오류 `workspace_id` → `earlierStageStatus(BODY_LENGTH_STAGE, WORKSPACE_STAGE)` 와 `pathExampleDetail(..., TOO_LONG_BODY_EXAMPLE)`. `earlierStageStatus` 는 그대로 쓸 수 있다.
- **판정 필요(부수)**: 계약이 같은 결함의 `detail` **타입**(문자열 vs 배열)을 오퍼레이션 단위로 규정하는가? `ValidationError` 는 두 모양을 다 예시로 든다(`:2100-2106`). `contract-keeper` 판정 대상으로 올린다.

#### R-1 [권고] `DecryptionFailedException` 의 선언 범위가 문면보다 넓다

- `DomainExceptions.kt:42-49` — KDoc 이 *"**원인도 자원 종류도 구분하지 않는** 단 하나의 예외"* 로
  넓혔는데, 같은 커밋이 문구를 `"저장된 문서를 읽을 수 없습니다"` → **`"저장된 변환 결과를 읽을 수 없습니다"`**
  로 **자원을 특정하는 쪽으로** 바꿨다. 계약(`:2123` `InternalError.examples.storage`)과는 일치한다 ✓
- 실질 위험은 오늘 0이다 — `AesGcmContentCipher.kt:94` 가 유일한 발생 지점이고, 요청 경로에서
  문서 원문 열을 여는 코드가 아직 없다(`export` 미구현). 그러나 `export`·문서 상세가 오면 문서 열
  복호화 실패에 *"변환 결과"* 라는 틀린 명사가 나간다.
- **처방**: 두 갈래 중 하나를 고르고 **적어라** — ⑴ 계약 `storage` 예시를 자원 중립 문구로 바꾼다
  (예: *"저장된 내용을 읽을 수 없습니다"*), 또는 ⑵ KDoc 의 "자원 종류도 구분하지 않는" 을 지우고
  「이 문구는 변환 결과 경로 전용이며 문서 경로가 생기면 계약 예시를 늘린다」로 좁힌다.
  **지금 선언과 문면이 반대 방향을 가리키는 상태를 남기지 않는 것**이 요점이다.
- **관련 종료 조건**: Phase 4 `export` 착수 시 재판정

---

### 3.2 축 B — parity 위험

#### ✅ 검토함 — 지적 없음

이 범위는 정규식·유니코드·한글 처리·프롬프트 문자열·보정 채택에 **닿지 않는다.** 변경된 것은
응답 조립·게이트 상수·테스트 하네스다. `UploadFixtures.docxWithBodyChars`(`:43-52`)가 새로 생긴
유일한 텍스트 생성 지점인데, 채움 글자가 `"가"`(XML 이스케이프 불필요, `:166`)이고 용도가
「본문 길이 단계에 닿게 하는 것」뿐이라 추출 의미론에 영향이 없다.

`ConversionReadReachTest.kt:243-263`(변조 팔)이 **AAD 결속**을 `sealAs = UUID.randomUUID()` 로 깨서
round-trip 거부를 재는데, 이것은 parity 축이 아니라 보안 불변식 축(I-7)이며 §3.3에서 다룬다.

한 가지만 확인해 둔다 — `DocumentService.store` 가 `charCountOf(text)` 를 **작업 공간 파싱보다 앞**에
두는 순서 변경은 글자 수 계산 자체를 바꾸지 않는다(`charCountOf` 호출 위치만 이동, 인자 동일).

---

### 3.3 축 C — 보안 불변식

> `privacy-gate` 의 감사 목록이 정본이다. 여기서는 **이 범위의 새 코드가 그 목록의 어느 항목에
> 닿는지**만 지목하고, 판정이 갈리면 `privacy-gate` 를 따른다.

#### ✅ 검토함 — 개선 2건 확인, 신규 지적 없음(단 C-1 이 이 축의 하한선을 건드린다)

| 감사 항목 | 이 범위의 접점 | 판정 |
|---|---|---|
| I-7 AEAD round-trip·변조 거부 | `ConversionReadReachTest.kt:243-263` 변조 팔 신설 — 결속을 깬 암호문 두 행이 **같은 `detail`** 을 내고 평문이 안 실리는지 잰다. 「두 행의 detail 이 같다」 단언(`:260-262`)이 좋다 — 문구가 입력에 좌우되지 않음을 잰다 | **개선** |
| 마스킹 대응표 노출 | `FAILED.exposesResult = false` → 실패 변환의 `masked_items`(계약 `:1132` 가 실제 개인정보라 적은 필드)가 빈 배열로 강제 | **개선** |
| 소유권 은닉 404 | `OwnershipConcealment.MAX_VARIABLE_HEADERS = 1` + `DocumentListReachTest.kt:236-246` 의 상한 단언 — 은폐형 면제 목록에 여유 0 상한 ✓. `OwnershipPredicateGuardTest.kt:37-48` `MAX_UNGUARDED_STATEMENTS = 7`, `EXPECTED_UNGUARDED` 실제 7 → 여유 0 ✓ | **개선** |
| 평문 로그 금지 | `ConversionView.toString`(`:33-36`) 허용목록 유지. `ConversionResponse.of` 의 `require` 메시지가 `masked=${view.maskedItems.size}` **개수만** 담는다(`:64`) — 값이 아니다 ✓. `ConversionReadContractTest.kt:184-185` 가 그 메시지에 원문·본문이 없음을 실제로 단언한다 ✓ | **유지** |
| 사적 응답 헤더 하한선(`no-store`·`nosniff`) | K-1 인구조사 신설 — **1/10 → 8/10 으로 대폭 개선.** 다만 남은 2/10 의 유보 해제 장치가 도달 0 → **C-1** | **개선 + 차단 1건** |

**하한선 이양을 확인했다**: 이 회차가 `AuthContractTest.kt:320` · `AuthEndpointReachTest.kt:488` ·
`WorkspaceContractTest.kt:287` · `ConversionReadContractTest.kt:278` · `DocumentListContractTest.kt:39`
의 KDoc/`@DisplayName` 에서 **X-D1 주장을 지웠고**(`값·부착 개수만 잰다 … 하한선(X-D1)은
PrivateHeaderFloorCensusTest 가 진다`), 그 이양은 옳다 — 그 테스트들은 전역 필터가 있는
컨텍스트에서 돌아 개별 부착을 재지 못했다(가드 주석 `tests/test_kotlin_gate_reach.py:444-449` 의
리더 실측: *"컨트롤러 개별 부착 2줄을 지워도 초록"*). **그러나 이양의 귀결로, 유보된 두 자리의
X-D1 을 재는 것이 이제 저장소 전체에서 0이다** — C-1 의 위험이 이 이양 때문에 커졌다.

**K-1 의 음성 대조가 실재한다** — `PrivateHeaderFloorCensusTest.kt:33-43` 이 `/health` 응답에
전역 헤더가 **없음**을 먼저 단언한다. 이 단언이 공허하지 않은 근거를 확인했다: 전역 부착은
`PrivateResponseHeadersConfig` 의 `FilterRegistrationBean` + Tomcat `Valve` 이고(`:24-34`),
`@WebMvcTest` 는 `@Configuration` 클래스를 타입 필터로 제외하므로 그 `@Bean` 이 아예 돌지 않는다.
컨트롤러 개별 부착은 4곳에 실재한다(`AuthController.kt:51-52`, `WorkspaceController.kt:65-66`,
`DocumentController.kt:89-90`, `ConversionController.kt:31-32`). **즉 이 인구조사는 자기가 재겠다고
선언한 것을 실제로 잰다** — 이 회차의 가장 값있는 산출물이다.

#### C-1 [차단 · ② 장치] K-1 인구조사의 **유보를 끊는 장치가 도달 0** — 구현돼도 빨개지지 않는다

- **선언**: `PrivateHeaderFloorCensusTest.kt:85` 의 `@DisplayName` —
  *"유보한 자리는 **실제로 핸들러가 없다** — 구현되면 이 케이스가 먼저 빨개져 유보를 끊는다"*.
  그리고 `:293` — *"아직 미구현인 자리. **면제가 아니라 유보다** — 구현하면 빨개진다."*
- **실제 도달 0. 기제**:
  - 프로브는 **유효한 토큰 + 무작위 UUID** 로 요청하고(`:166-186`) 응답 상태가
    `NO_HANDLER_STATUSES = setOf(404, 405)`(`:302`) 에 드는지만 본다(`:102`).
  - 유보 두 자리는 `PUT /conversions/{conversion_id}` 와 `GET /conversions/{conversion_id}/export`
    (`:294-300`) — **둘 다 `{id}` 를 받는 자원 경로**다.
  - 계약이 이 경로들에 **소유권 은닉 404** 를 요구한다(계약 `:1132-1134` 가 둘을 개인정보 응답으로
    열거하고, `X-B1`·`X-B2` 가 「없는 것과 남의 것이 구분되지 않는다」를 강제한다).
    따라서 **구현된 뒤에도 무작위 UUID 에 대한 응답은 404 다.** 프로브의 술어가
    「핸들러 부재」와 「자원 부재」를 **원리적으로 구분하지 못한다.**
  - `PUT` 쪽은 더 확실하다 — 미구현인 지금은 같은 경로에 `GET` 이 있어 **405**, 구현되면 **404**.
    둘 다 `NO_HANDLER_STATUSES` 안이다.
- **저장소가 이미 이 대리 지표의 오류를 증명해 두었다**:
  `ConversionReadReachTest.kt:334-342` 가 **파기된 자원의 404** 와 **매핑 부재의 404** 를 나란히
  받아 `assertThat(unmapped.body()).isNotEqualTo(afterDelete.body())` 로 단언한다. 실패 문구가
  스스로 적는다 — *"파기 404 와 **매핑 부재** 404 의 본문이 같다 — 이 케이스는 「핸들러가 없어서
  404」를 「파기됐으니 404」로 읽고 있다."* **두 404 는 본문에서만 갈린다. K-1 은 상태만 본다.**
  즉 음성 대조를 새로 만들 필요가 없다 — 이미 있고, 그것이 C-1 을 확정한다.
- **귀결 — 선언 「전건」 대 실도달 8/10, 그리고 빠진 둘이 최악이다**
  - 클래스 KDoc `:23` 은 *"하한선 열거 **전건**의 X-D1"* 이라 선언한다. `implementedTargets()`
    (`:108-109`)이 `NOT_YET_IMPLEMENTED` 를 빼므로 실제 분모는 **8/10** 이다.
  - 빠진 둘 중 `GET /conversions/{conversion_id}/export` 는 계약 `:1134` 가
    *"자리표시자가 **원문으로 복원된 최종본**"* 이라 적은 응답이다 — 하한선 10곳 중
    `no-store` 가 가장 절실한 자리다.
  - **그리고 이 회차가 위험을 키웠다**: 같은 커밋이 `AuthContractTest.kt:320` ·
    `AuthEndpointReachTest.kt:488` · `WorkspaceContractTest.kt:287` ·
    `ConversionReadContractTest.kt:278` · `DocumentListContractTest.kt:39` 에서 **X-D1 주장을
    지웠다**(*"하한선(X-D1)은 `PrivateHeaderFloorCensusTest` 가 진다"*). 이양 자체는 옳다(그
    테스트들은 전역 필터 컨텍스트라 개별 부착을 못 쟀다). 그러나 그 결과 **유보된 두 자리의
    하한선을 재는 것이 저장소 전체에서 0** 이고, 유보를 끊는 장치도 0이다.
- **장치 분류**: **탐지형**(유보 해제 탐지)인데 술어가 대리 지표라 도달 0. 규칙 4 ⑵ 의 은폐형
  전환에 해당한다 — 「유보」라는 이름을 달았지만 기계적으로는 **영구 면제**다.
- **심각도**: **차단(② 장치).** 계획 §5 즉시 중단 기준 중 「타 사용자 노출·404 위반」과 사적 헤더
  하한선을 탐지하는 장치가, 가장 위험한 두 자리에서 구조적으로 침묵한다. **Kotlin 코드가 아직
  0줄인 자리여도 ② 는 차단으로 올린다**(스킬 규약).
- **마감**: **`export` scope 착수 또는 C7 `PUT` 착수 — 그 전에.** 그 시점에 유보가 자동으로
  끊기지 않으므로, 착수 후에 닫으면 「전건」이라 선언된 채 8/10 인 상태로 새 엔드포인트가
  하한선 검사를 **한 번도 받지 않고** 태어난다.
- **처방 — 전부 기구현 재사용, 새 층 없음**
  1. 술어를 **엔진에 직접 묻는 것**으로 바꾼다:
     `ServedOperations.methodsOn(handlerMapping, environment, path)`
     (`api/src/test/.../support/ServedOperations.kt:39-44`)가 `RequestMappingHandlerMapping.handlerMethods`
     에서 **실제 매핑된 (경로, 메서드)** 를 뽑는다. 유보 케이스는
     `assertThat(methodsOn(..., target.path)).doesNotContain(target.method)` 하나로 끝난다.
     상태 코드를 보지 않으므로 404 의 다의성이 사라진다.
  2. `@WebMvcTest` 에서 `RequestMappingHandlerMapping` 을 주입받는 선례가 둘 있다 —
     `AuthenticationCoverageContractTest.kt:22`, `ValueSlotInvariantReachTest.kt:42`. 그대로 쓴다.
  3. **선행 확인 하나**: `ServedOperations.isProductionClass` 가 시스템 속성
     `easydoc.kotlin.source.root` 와 `api/build/classes/kotlin/main` 을 요구한다(`:46-54`).
     `@WebMvcTest` 슬라이스에서 그 배선이 서는지 확인해야 한다 — 서지 않으면 이 클래스에서만
     `handlerMapping.handlerMethods` 를 직접 훑는다(생산 클래스 필터 없이도 유보 판정에는 충분하다).
  4. KDoc `:23` 의 「전건」을 실도달에 맞춰 고친다 — 「하한선 열거 중 **구현된 자리 전건**」.
     ⑴~⑶ 으로 유보가 기계적으로 끊기게 되면 이 문면이 다시 참이 된다.

#### C-2 [수정 필요 → C-1 미조치 시 차단] 이 회차가 만든 **세 번째 은폐형 목록에만 상한이 없다**

- **비대칭이 지적의 요지다.** 같은 커밋(`b4c2fda`)이 은폐형 목록 **둘**에는 여유 0 상한 + 이력
  라쳇을 붙이고, 새로 **만든** 하나에는 아무것도 붙이지 않았다.

  | 은폐형 목록 | 이 회차의 처분 | 상한 | 이력 라쳇 |
  |---|---|---|---|
  | `OwnershipConcealment.VARIABLE_HEADERS` (S-2) | 상한 신설 | `MAX_VARIABLE_HEADERS = 1`, 여유 **0** | ✓ `RATCHET_CEILING_PINS` |
  | `OwnershipPredicateGuardTest.EXPECTED_UNGUARDED` (S-5) | 상한 신설 | `MAX_UNGUARDED_STATEMENTS = 7`, 여유 **0** | ✓ `RATCHET_CEILING_PINS` |
  | `PrivateHeaderFloorCensusTest.NOT_YET_IMPLEMENTED` (K-1) | **신설했으나 상한 없음** | **없음** | **없음** |

- **분모의 유일한 하한이 `isNotEmpty()` 다** — `:55-57` 이 *"분모가 0 이면 초록이 아무 뜻이 없다"*
  로 0만 막는다. 10곳 중 9곳을 유보로 옮겨도 초록이다.
- **C-1 과 곱해진다**: 유보를 끊는 장치가 없고(C-1) 유보 개수 상한도 없으므로(C-2), 인구조사의
  분모는 **아래로 열려 있다.** 두 장치 중 하나만 있어도 막히는데 둘 다 없다.
- **심각도**: 단독으로는 **수정 필요**. **C-1 이 처방대로 닫히면 권고로 내려간다**(유보를 늘리면
  그 자리가 실제로 구현됐는지 엔진이 판정하므로 허위 유보가 막힌다). **C-1 이 미조치로 남으면
  차단(② 장치)** 으로 올려야 한다 — 그때는 분모를 임의로 깎는 경로가 실재한다.
  **이 조건부 판정을 리더에게 그대로 올린다.**
- **마감**: C-1 과 같다.
- **처방 — S-2·S-5 와 **같은 형태**를 쓴다(새 기제 아님)**
  1. `PrivateHeaderFloorCensusTest` companion 에 두 상수:
     `MAX_DEFERRED_FLOOR_TARGETS = 2`(오늘 유보 2, 여유 **0**) ·
     `MIN_FLOOR_CENSUS_TARGETS = 8`(오늘 구현 8, 여유 **0**).
  2. `:50-57` 의 `isNotEmpty()` 두 줄을 각각
     `hasSizeGreaterThanOrEqualTo(MIN_FLOOR_CENSUS_TARGETS)` ·
     `assertThat(NOT_YET_IMPLEMENTED).hasSizeLessThanOrEqualTo(MAX_DEFERRED_FLOOR_TARGETS)` 로 바꾼다.
  3. 라쳇에 핀한다 — `MAX_DEFERRED_FLOOR_TARGETS` → `RATCHET_CEILING_PINS`(상한),
     `MIN_FLOOR_CENSUS_TARGETS` → `RATCHET_SCALAR_PINS`(하한). **단 두 상수 다 Kotlin 이므로
     M-2 의 인구조사 공백에 들어간다** — C-2 처방은 M-2 처방과 **함께** 넣어야 값이 있다.
  4. `MIN_ASSERTIONS_BY_CLASS["…PrivateHeaderFloorCensusTest"]` 를 7 → 9 로(단언 2개 증가) 올린다.

---

### 3.4 축 D — Kotlin/Spring 관용성

#### M-2 [수정 필요] Kotlin 상수를 겨누는 라쳇 표에 **인구조사가 없다** — 열거가 다시 자란다

- **근거**
  - `tests/test_kotlin_gate_reach.py:844-847` 이 스스로 적는다: *"**Kotlin 쪽 항목의 방향은
    `_bound_direction` 이 판정하지 않는다** — 그 판정기는 이 파일의 AST 만 본다. Kotlin 상수는
    사람이 방향을 읽어 여기 넣고…"*. 따라서 `test_이_파일의_수치_상수가_전부_분류돼_있다` 의
    **정확 삼분할은 Kotlin 상수에 도달하지 않는다.**
  - 이 회차가 Kotlin 상수를 라쳇 표에 **처음 넣었다** — `RATCHET_CEILING_PINS` 에
    `MAX_UNGUARDED_STATEMENTS`·`MAX_VARIABLE_HEADERS`, 신설 `RATCHET_CEILING_DECIMAL_PINS` 에
    `MAX_TIMING_RATIO` 사본 넷. 즉 **표는 Kotlin 으로 넓어졌는데 인구조사는 넓어지지 않았다.**
  - **오늘의 열거는 완전하다**(실측): `grep -rn "MAX_TIMING_RATIO" backend-kotlin` → 선언 정확히 4개
    (`AuthEndpointReachTest:565`, `DocumentDeleteReachTest:434`, `WorkspaceEndpointReachTest:538`,
    `AesGcmContentCipherTest:625`), 전부 핀됨. 문제는 **다섯째가 생길 때**다.
  - **이 회차가 정확히 그 형태를 차단으로 처리했다** — S-5(`OwnershipPredicateGuardTest`)의 사유가
    *"목록은 정확 열거 핀이라 「새 owner-less SELECT + 핀 한 줄」이 전건 초록이었다"* 다
    (`tests/test_kotlin_gate_reach.py:849-852`). 새 `*ReachTest.kt` 에 `MAX_TIMING_RATIO = 1.5` 를
    선언하면 어느 표에도 안 들고 저장소 전체에서 빨개지는 것이 **없다** — 같은 구조다.
- **장치 분류**: **범위 선언형**(어느 상수가 라쳇 대상인가를 선언한다). 규칙 4 ⑶ — 범위 선언형은
  빈 선언·부족한 선언에서 통과하면 안 된다. 오늘 부족한 선언에서 통과한다.
- **관련 종료 조건**: §6 게이트·탐지기 자신 · Phase 4 `documents` 종료
- **처방** (기존 기제 재사용, 새 층 없음 — 규칙 7 3층 상한 안)
  ```
  KOTLIN_RATCHETED_CONSTANT_NAMES = ("MAX_TIMING_RATIO", "MAX_UNGUARDED_STATEMENTS", "MAX_VARIABLE_HEADERS")
  # 이 이름을 선언하는 Kotlin 파일 전수 ↔ (CEILING_PINS ∪ CEILING_DECIMAL_PINS) 의 Kotlin 항목
  # 을 **양방향** 대조. 이름 튜플 자신은 RATCHET_NAME_TUPLE_PINS 가 지킨다(이미 있는 축).
  ```
  판정기는 이미 있다 — `_kotlin_test_sources()` + `_source_pair()`(주석·문자열 비움). `_named_enforcer_census`
  가 「제품 주석이 지목한 클래스」에서 쓴 **fail-closed 인구조사 + 핀 대조** 형태를 그대로 옮기면 된다.
- **왜 은폐형이 아닌가**: 면제 목록을 늘리는 것이 아니라 분모를 **소스 전수**로 옮기는 것이므로
  규칙 4 ⑵ 의 거부권에 걸리지 않는다.

#### R-2 [권고] `ConversionResponse` 의 공개 생성자·`copy()` 가 `of` 의 `require` 를 우회한다

- `ConversionDtos.kt:36`(`data class ConversionResponse`) — 주 생성자가 `public` 이고 `data class` 라
  `copy()` 도 `public` 이다. `require` 는 `of` 안에만 있으므로, 생성자 직접 호출이나
  `completedResponse.copy(status = "pending")` 은 강제자를 지나지 않는다.
- **오늘 위험 0**: main 에서 생성자를 직접 부르는 곳은 `of` 안뿐이고 `copy` 사용 0건(실측).
  그래서 차단이 아니다. 그러나 **`of` 가 유일한 자리라는 것은 규율이고 타입이 아니다.**
- **처방**: 주 생성자를 `private` 로 내리고 `@ConsistentCopyVisibility` 를 붙여 `copy()` 도 함께
  좁힌다(Kotlin 2.x). `DocumentCreatedResponse`·`DocumentListItemResponse` 도 같은 형태이므로
  같이 정리하면 「DTO 는 `of` 로만 만든다」가 컴파일러 강제가 된다. §3.2 의 모듈 경계와 같은 결이다.
- **관련 종료 조건**: 없음(규율 강화)

---

### 3.5 축 E — 테스트 적정성

#### C-3 [차단 · ② 장치] 이 회차가 선언한 **새 와이어 불변식의 강제자(DC-26·DC-27)가 어느 하한 표에도 없다**

- **근거**
  - DC-26·DC-27 은 `DocumentEndpointReachTest.kt:139-224` 에 있다.
  - `grep -n "DocumentEndpointReachTest" tests/test_kotlin_gate_reach.py` → **`TEST_CLASSES:254` 한 줄뿐.**
    `FLOOR_TEST_CLASSES` **밖**, `MIN_TESTS_IN_FLOOR_CLASS` **밖**, `MIN_TESTS_BY_NAMED_ENFORCER` **밖**,
    `MIN_ASSERTIONS_BY_CLASS` **밖**.
  - 즉 이 클래스는 **개수 하한이 하나도 없다.** 두 메서드를 지우면 클래스는 남으므로
    `TEST_CLASS_COUNT`(112)·`TEST_CLASSES` 정확 일치도 그대로다. 악용 비용 = 메서드 2개 삭제,
    자동 탐지 = **0**.
  - **저장소 자신이 이 형태를 차단칸으로 판정한 선례가 있다** —
    `tests/test_kotlin_gate_reach.py:697-701`: *"`DocumentListContractTest`(Claude β-03) ·
    `DocumentContractNodeTest`·`HealthContractTest`(codex β-24) 가 두 표 밖이었다. … 자동 신호가
    **0** 이었다(악용 비용 한 줄 × 자동 탐지 0 = **차단 칸**)"*. 같은 구조다.
  - 그리고 **fail-closed 기제가 이미 있는데 쓰이지 않았다**: `_named_enforcer_census` 는
    `backend-kotlin/**/src/main/**` 의 주석·KDoc 이 이름으로 지목한 `…Test` 를 전수로 뽑아
    핀이 없으면 빨개진다(`:698-703`). 이 회차가 `DocumentController.kt:59-60` 에 그 불변식을
    **산문으로만** 적고(*"인자 자리에서 파싱하면 Kotlin 의 인자 평가 순서가 계약 검사 순서를 앞질러…"*)
    강제자 이름을 적지 않았다. 그래서 인구조사가 발동하지 않았다.
- **왜 차단인가(② 장치)**: DC-26 은 **와이어 동작을 지키는 유일한 실행**이다. 계약 `:1316-1319` 이
  스스로 적는다 — *"실제 구현에서는 **언어의 인자 평가 순서**가 관측되는 상태 코드를 정하고
  있었다 — 즉 호출 지점의 줄 순서를 바꾸는 리팩터링이 와이어 동작을 조용히 바꾼다."*
  DC-26 이 사라지면 그 리팩터링이 다시 조용해진다. 이 회차가 고친 결함의 **재발 탐지기**가 무보호다.
- **마감**: **Phase 4 `documents` 종료 판정** — DC-26·DC-27 이 그 판정의 근거로 인용되는 시점.
- **처방 (한 줄, 기구현 기제 발동)**: `DocumentController.kt` 의 그 주석에 강제자 이름을 넣는다 —
  예: *"…앞지른다. 이 순서를 재는 것은 [DocumentEndpointReachTest] 의 DC-26·DC-27 이다."*
  → `_named_enforcer_census` 가 자동으로 분모에 넣고, `MIN_TESTS_BY_NAMED_ENFORCER` ·
  `MIN_ASSERTIONS_BY_CLASS` 핀이 **없으면 빨개진다**(fail-closed). 새 표도 새 층도 만들지 않는다.
  실측 기준값(오늘): `@Test` **20** · `assert…(` 토큰 **56**.

#### M-3 [수정 필요] 이 회차가 만든 강제자 대부분이 **자기를 지켜야 할 표의 삭제 창 안**에 있다

- **근거** (선언 하한 → 오늘 실측. 실측은 `grep -c '^\s*@Test'` 와 `grep -oE '\bassert[A-Za-z]*\('`
  로 냈고, 게이트의 판정기는 주석·문자열을 비우므로 게이트 값은 이보다 작거나 같을 수 있다)

  | 클래스 | 이 회차에 태어난 강제자 | `@Test` 하한 → 실측 | `assert` 하한 → 실측 | 창 |
  |---|---|---|---|---|
  | `DocumentEndpointReachTest` | DC-26 · DC-27 | **표 없음** → 20 | **표 없음** → 56 | **무한** (= C-3) |
  | `DocumentListReachTest` | S-2 상한 단언 · `observe` 어댑터 프로브 | 11 → 14 | 29 → 31 | 3 / 2 — **새 두 테스트가 정확히 이 창 안에 들어간다** |
  | `ConversionReadReachTest` | CR-3b · 변조 팔 | 10 → 13 | 34 → 45 | 3 / 11 |
  | `ConversionReadContractTest` | 매퍼 팔 아홉 대조 | 5 → 6 | 21 → 26 | 1 / 5 |
  | `PrivateHeaderFloorCensusTest` | 인구조사 3케이스 | 3 → 3 | 7 → 7 | **0 / 0** ✓ |
  | `OwnershipPredicateGuardTest` | S-5 상한 단언 | 13 → 14 | 15 → 16 | **1 / 1 — 창이 정확히 새 S-5 테스트 하나다** |

  **`OwnershipPredicateGuardTest` 가 가장 선명한 사례다.** 하한 13/15 는 이 회차에서 **변하지
  않았고**(diff 확인) 그것이 회차 **직전**의 실측이다. S-5 테스트 하나가 `@Test` 를 1, `assert…(`
  를 1 늘렸으므로 현재 14/16 이고, **그 테스트만 지우면 정확히 하한으로 되돌아가 전건 초록이다.**
  즉 「새 owner-less SQL + 핀 한 줄」이 통과하던 자리를 막으러 태어난 단언이, 태어난 그 회차에
  **자기 자신이 같은 형태로 지워질 수 있는** 상태다.

- **무엇이 문제인가**: 규칙 8(라쳇 상환 = Phase 경계)은 **창의 존재 자체**를 정책으로 인정한다.
  그러나 규칙 8 의 전제는 「창은 Phase 경계에서 닫힌다」이고, 여기서 그 Phase 경계는 **Phase 4
  `documents` 종료 판정** — 바로 이 강제자들이 근거로 쓰일 시점이다. 즉 창이 **강제자가 중요한
  기간 내내 열려 있다.** `CLAUDE.md` 2026-08-21 항목이 실측으로 적은 것이 정확히 이 기제다
  (*"라쳇은 「함께 줄이기」를 막는 게 아니라 하한 아래만 막는다: 선언 111 · 하한 105 → **6개 창**"*).
- **리더가 한쪽은 상환했다** — `MIN_TEST_CLASSES` 105 → **111**(`TEST_CLASS_COUNT` 112, 창 1) ·
  `MIN_FLOOR_CLASSES` 29 → 30 · `PrivateHeaderFloorCensusTest` 여유 0. **클래스 축은 조였고
  메서드·단언 축은 조이지 않았다.** 그 비대칭이 지적 대상이다.
- **관련 종료 조건**: §6 「각 테스트가 보장하던 행동이 어느 계층으로 갔는지 추적」 · Phase 4 종료
- **처방**: 위 표 여섯 행의 하한을 오늘 실측으로 올린다(여유 0). 값은 이 표에 있다.
  C-3 을 처방대로 닫으면 `DocumentEndpointReachTest` 는 인구조사가 자동으로 요구한다.
  **여섯 행 모두 리더 핀이므로 판정을 올린다.**
- **구조 처방(권고, 별건)**: 회차마다 여섯 행을 손으로 올리는 것 자체가 규칙 8 이 줄이려던 비용이다.
  개별 상향의 대안은 **「이 회차가 만든 클래스의 하한은 여유 0 이어야 한다」를 기계로 재는 것**이
  아니라 — 그것은 「이 회차」를 정의해야 하므로 비싸다 — **변이 테스트(백로그 B-19)** 다.
  `MIN_ASSERTIONS_BY_CLASS` 주석 자신이 그 잔여를 그렇게 적는다(`tests/test_kotlin_gate_reach.py:756-758`).
  이 리뷰의 처방은 B-19 를 끌어오지 않고 **오늘의 여섯 행 상향까지**다.

#### M-4 [수정 필요] `test_review_coverage_reach.py` 가 **codex 레인의 부재를 보지 않는다** (명시 질문 5)

- **무엇을 막는가 — 확인된 것**
  - 커버리지 행을 **구조적으로** 판정한다: 열 3개 · `범위` 칸이 백틱 `a..b` 이고 양 끝이 실재
    커밋(`git rev-parse --verify`) · `회차` 칸이 백틱이고 `reviews/<회차>_*.md` 가 **최소 2건 실재**
    (`tests/test_review_coverage_reach.py:137-186`). `| 전부 | 0d632f9..HEAD | 없음 |` 한 줄 우회는
    닫혔다 ✓ (F-1)
  - 산출물 실재를 **디스크**에서 본다 → 미추적 파일도 셈. 로컬에서는 위조 가능하지만 **CI 는 신선
    체크아웃**이라 미추적 산출물이 없어 행이 무효화되고 커밋이 장부로 넘어간다 → 미추적 위조는
    CI 가 막는다 ✓ (L-㉛ 이 산출물 3건을 추적에 넣은 조치와 정합)
- **막지 못하는 것 — 이 회차의 지적**
  1. **역할 구성을 보지 않는다.** 조건은 `len(artifacts) >= 2` 뿐이다(`:181`). 따라서
     `{stem}_migration-reviewer.md` + `{stem}_cross.md` **둘만** 있어도 그 범위가 「리뷰됨」이 된다 —
     **codex 독립 리뷰가 아예 없는 회차**가 통과한다. `CLAUDE.md` 는 *"codex 독립 리뷰는 **필수**"*
     라고 규정하고, `xx_harness` 회차가 실제로 그 반례다(산출물 3건 · codex 원문 0줄).
     **판정자의 docstring 이 스스로 적은 「못 재는 것」 목록에 이 항목이 없다**(`:159-166` 은
     「내용의 참·거짓」만 적는다) — 즉 알려진 잔여가 아니라 **미인식 빈자리**다.
  2. **커밋 시점을 보지 않는다.** `touch` 두 개 + 표 한 줄이면 임의 범위가 리뷰됨이 되고, 그 두
     파일을 커밋해 버리면 CI 도 통과한다. 악용 비용 3줄 × 자동 탐지 0.
  3. **`위 두 조건이 이 회차에 더 중요해졌다`** — 커버리지 표가 이 범위에서 늘었고(`00_progress.md:1587-1588`
     두 `04_documents-c6` 행), 그 표가 Phase 4 종료 판정의 근거로 쓰인다.
- **심각도 판단**: 단독으로는 **수정 필요**로 둔다. 근거 둘 — ⑴ 설계가 「선언 기록」임을 명시하고
  그 한계를 문서화했다(`00_progress.md:1590-1595`) ⑵ 이연 장부 `0fda906` 행이 이 강제자를
  **동결 (교체 계획 R-2 로 소멸 예정)** 로 적었다. **다만 R-2 의 범위에 「역할 구성 검사」가
  포함되는지는 이 리뷰가 확인할 수 없다** — 포함되지 않으면 교체 후에도 같은 빈자리가 남으므로
  리더 확인이 필요하다.
- **처방** (셋 다 이미 파싱된 데이터로 가능, 새 층 없음)
  1. `len(artifacts) >= 2` → `{stem}_codex-reviewer.md` **와** `{stem}_cross.md` 가 **둘 다** 실재.
     필수 레인의 부재가 곧 행 무효가 된다.
  2. `git cat-file -e <end>:<path>` 로 두 산출물이 그 행의 `end` 커밋 시점에 **추적돼 있었는지** 본다.
  3. `_cross.md` 가 그 행의 `a..b` 문자열을 담는지 본다 — 자기 입력 범위를 적지 않은 종합본은 그
     범위를 판정한 근거가 아니다. (셋 다 「내용이 참인가」는 여전히 재지 않는다 — 그 한계는 유지)
- **관련 종료 조건**: §6 게이트·탐지기 자신 · Phase 4 종료 판정의 근거 무결성

#### M-5 [수정 필요] `{scope}` 어간 규약의 **미선언 드리프트** (명시 질문 4)

- **사실**
  - `.claude/skills/kotlin-migration/SKILL.md:214-228` 의 정본 표: Phase 4 = `upload` · `extract` ·
    `crypto` · `documents` · `export`. Phase 밖 = `pre-phase0` · `harness`.
  - 규칙: *"**표에 없는 값을 쓰지 않는다.** 새 대상이 생기면 이 표에 먼저 추가하고…"*(`:230`)
  - 실사용(`ls docs/migration/_workspace/reviews/`): `03_auth-fixes` · `03_phase3-close` ·
    `03_security-workspaces-fixes` · `03_security-scanner` · `04_documents-c3` · `04_documents-c4c5` ·
    `04_documents-c6` · 이번 `04_documents-c6r2` · `xx_harness-fixes`. **표에 있는 값은 하나도 없다**
    (접미가 붙은 파생형이다).
  - **강제자 0**: `tests/test_harness_scope_reach.py` 에 `reviews/` 파일명을 표와 대조하는 검사가
    없다(`grep` 확인). 오히려 `test_review_coverage_reach.py:180` 이 `f"{stem}_*.md"` 로 **임의 어간을
    받아들여** 드리프트를 기제에 편입했다.
- **왜 지적인가 — 두 가지 실질 위험**
  1. **규약이 거짓이 되면 그 규약이 지키던 것도 함께 죽는다.** 표의 존재 이유는 *"어간이 갈리면
     1단계 파일이 만들어져도 3단계가 입력을 못 찾아 게이트가 닫히지 않는다"*(`:210`)다. 오늘은
     리더가 어간을 내려보내 실무로 막고 있지만, 그 규율의 근거 문서는 이미 거짓이다.
  2. **은폐형으로 처방하면 안 된다.** 표에 `documents-c3`·`documents-c6`·`documents-c6r2` … 를
     열거해 넣는 것은 회차마다 표를 늘리는 은폐형이고 규칙 4 ⑵ 의 거부권에 걸린다.
- **처방 — 문법을 선언하고 탐지형으로 강제한다** (규칙 4 ⑴: 빈자리를 **종류로** 댈 수 있으므로
  종류만큼 넓힌다)
  1. **표에 문법 규칙 한 줄을 더한다**:
     > `{scope}` 는 이 표의 값 하나로 **시작**해야 하고, 뒤에 `-` + `[a-z0-9]+` 형태의 **회차 표식**을
     > 0회 이상 붙일 수 있다(예: `documents-c6r2`, `auth-fixes`, `harness-fixes`). 표식은 새 대상이
     > 아니라 **같은 대상의 다음 회차**를 뜻하므로 표를 늘리지 않는다. 표의 값으로 시작하지 않는
     > 어간은 금지다.
     - 이렇게 하면 `phase3-close` 는 여전히 위반이다(`{scope}` 에 `phase3-close` 도, 그것으로
       시작하는 값도 없다). **그 한 건은 표에 추가할지 이름을 바꿀지 리더 판정이 필요하다** —
       Phase 종료 판정 회차는 특정 `{scope}` 가 아니라 Phase 전체를 보므로 표에
       `phase-close`(전 Phase 공통) 를 넣는 것이 정직하다고 본다.
     - `security-workspaces-fixes`·`security-scanner` 는 `security`(전 Phase 공통) 로 시작하므로 문법에 든다.
  2. **`tests/test_harness_scope_reach.py` 에 탐지자를 하나 만든다** — SKILL.md 의 표를 파싱해
     허용 `{scope}` 집합을 뽑고, `docs/migration/_workspace/reviews/*.md` **전건**의 어간을 대조한다.
     `{reviewer}` 도 같은 자리에서 검사한다(`codex-reviewer|migration-reviewer|privacy-gate|cross`).
     분모가 디렉터리 전수이므로 **새 파일이 자동으로 든다**(fail-closed). 그 검사기는 이미
     SKILL.md 를 읽는 파일 안에 있으므로 새 층이 아니다.
  3. **표를 파싱할 수 없으면 실패시킨다** — 표 형식이 바뀌어 0건을 읽으면 통과가 아니다(규칙 4 ⑶).
- **관련 종료 조건**: §6 게이트·탐지기 자신. **마감**: 이 회차의 3단계(교차 종합) 직후 —
  다음 회차 어간이 정해지기 전.

#### M-6 [수정 필요] 이연 장부의 `대기` 가 **닫히지 않는다** — 「빚」에 회수 장치가 0

- **사실**
  - `00_progress.md:1601` 이 규정한다: *"`대기` = **필수 축에 닿는데 아직 리뷰를 못 받은 것** —
    이연이 아니라 빚이다."*
  - 오늘 장부 11행 전부 `상태 = 대기`, **`닫힘` 열이 전부 `-`**(신설 2026-08-21 이후 한 행도 닫히지 않았다).
  - 판정자는 행의 SHA 존재만 본다(`test_review_coverage_reach.py:214-258`) — `닫힘` 값을 읽지 않는다.
    즉 **`대기` 행 하나가 그 커밋을 영구히 「계상됨」으로 만든다.** `CLAUDE.md` 의
    *"모든 변경은 여전히 정확히 한 번 리뷰를 받고 바뀐 것은 **시점**이다"* 를 기계로 재는 것이 0이다.
  - **`리뷰할 회차` 칸이 어간 문법이 아니다** — 이 회차 4행이 전부 `04_documents-c6 2회차`(산문)인데
    실제 어간은 `04_documents-c6r2` 다. 산문이라 M-5 의 어간 검사자로도 대조할 수 없다.
  - **오늘 「리뷰됐는데 안 닫힌 행」은 없다**(실측: 장부 11 SHA × 커버리지 3 범위 교집합 **0**).
    처방을 지금 넣으면 **이 회차의 3단계가 그 처방의 첫 소비자**가 된다.
- **처방**
  1. `리뷰할 회차` 칸을 산문 → **어간**(`04_documents-c6r2`)으로 규격화한다. M-5 의 어간 문법을 공유한다.
  2. `test_review_coverage_reach.py` 에 규칙 하나: **`대기` 행의 SHA 가 어느 커버리지 범위에 들면
     `닫힘` 칸이 `-` 가 아니어야 한다.** 이미 파싱하는 두 자료(`_reviewed_shas`, `_recorded_shas`)의
     교집합이므로 새 파싱이 없다. 리뷰가 실제로 돌면 장부가 닫히도록 강제된다.
  3. (선택) `리뷰할 회차` 어간이 `reviews/` 에 실재하면 `닫힘` 을 요구한다 — ⑵ 보다 이르게 발동한다.
- **관련 종료 조건**: §6 게이트·탐지기 자신 · Phase 4 종료(장부 4행이 이 Phase 판정에 걸려 있다)

#### R-3 [권고] CR-3b 의 두 기대값이 SQL 리터럴과 **두 곳에 따로 적혀** 있다

- `ConversionReadReachTest.kt:598-599` 가 `model = 'stored-model-probe'` · `provider_name = 'stored-provider'`
  를 SQL 리터럴로 심고, `:565-566` 이 같은 문자열을 `STORED_MODEL`·`STORED_PROVIDER` 상수로 다시 적는다.
  `:137-141` 의 `doesNotContain(STORED_MODEL)` 는 그 두 사본이 같아야만 의미가 있다 — SQL 쪽만
  고치면 **응답에 실린 적 없는 문자열의 부재를 재는** 공허한 단언이 된다.
- **처방**: `MARK_DONE_SQL` 에 `%s` 자리를 둘 더 만들고 `markDone` 이 상수를 넘긴다. `easy_text` 등
  다른 값이 이미 그 형태다. `%s` 를 늘려도 「SQL 은 companion 상수 리터럴」 규약을 깨지 않는다.

#### ✅ 검토함 — 지적 없음 (신설 라쳇 셋의 자체 건전성)

- `test_전수_스캐너의_캐시가_선언과_정확히_같고_실제로_적중한다`: AST 인구조사 ↔ 선언의 **양방향**
  대조 + `cache_info().hits >= 1` 실제 적중. `_call_twice` 가 인자 있는 함수에 실제 파일 경로를
  먹이고 인자 모양이 달라지면 `TypeError` 로 빨개진다(`:2851-2862`) — 조용히 건너뛰지 않는다 ✓
  5개 함수 시그니처 확인: `_source_pair(path_key)` 만 인자를 받고 나머지 넷은 무인자 → 오늘 정합 ✓
- `test_실수_상한_상수가_이력_최솟값보다_높지_않다`: `_decimal_in` 이 `Decimal` 로 읽어 `1.5 < 1.51`
  비교의 표현 오차를 없앤다 ✓ 정수 표기(`= 2`)도 받는다 ✓ **잔여 하나**: 정규식이
  `(\d+(?:\.\d+)?)` 라 `1.5e1` 을 `1.5` 로 읽는다(권고 수준 — 오늘 그런 표기 0건).
- `test_이름_튜플_선언이_이력에서_줄지_않았다`: `_name_tuple_in` 이 Tuple/List 가 아니면 빈 집합을
  돌려주고 호출자가 `assert current` 로 끊는다 → `TIMED_SCANNERS = tuple(X)` 같은 우회가 빨개진다 ✓
  **상호 폐쇄를 확인했다**: `RATCHET_PIN_TABLES` ∈ `RATCHET_NAME_TUPLE_PINS` 이고
  `RATCHET_NAME_TUPLE_PINS` ∈ `RATCHET_PIN_TABLES` — 어느 쪽을 비워도 상대의 `assert current` 가
  울린다(빈 `parametrize` 는 pytest 가 실패로 만들지 않으므로 이 상호 단언이 유일한 방어이고,
  그것이 실제로 있다) ✓
- 이력 부재 처분: `_report_or_fail_history`(`:2489-2508`)가 **모든 모드에서 raise** ✓ 그리고 CI 두
  잡에 `fetch-depth: 0` 이 실재하고 그 잡들이 이 파일을 경로 명시로 돌린다 → **도달 확인** ✓
- 실행 결과 **11 passed · skipped 0** — 라쳇이 이력 부재로 조용히 넘어가지 않았다 ✓

---

## 4. 도달 범위 점검 결과 (다섯 축을 가로지르는 필수 구획)

> 이 구획은 비워 두지 않는다. 「검토함 — 지적 없음」과 「미검토(사유)」는 다른 정보다.

### 4.1 장치 분류를 먼저 한다

| 장치 | 분류 | 이 회차의 변화 | 판정 |
|---|---|---|---|
| `PrivateHeaderFloorCensusTest` | **탐지형** (분모 = 계약 `applies_to` 10곳) | 신설 | 도달 **8/10**. 선언은 「전건」 → **C-1** |
| `NOT_YET_IMPLEMENTED` (K-1) | **은폐형** (면제·유보 목록) | 신설, 항목 2 | 상한 없음 → **C-2** |
| `VARIABLE_HEADERS` (S-2) | **은폐형** | 상한 `MAX_VARIABLE_HEADERS = 1` 여유 0 + 이력 라쳇 | 규칙 4 ⑵ 준수(넓히지 않고 **증가를 드러내는** 쪽으로) ✓ |
| `EXPECTED_UNGUARDED` (S-5) | **은폐형** | 상한 `MAX_UNGUARDED_STATEMENTS = 7` 여유 0 + 이력 라쳇 | ✓ |
| `RATCHET_CEILING_*_PINS` (Kotlin 부분) | **범위 선언형** | Kotlin 으로 확장 | 인구조사 없음 → **M-2** |
| `RATCHET_NAME_TUPLE_PINS` · `CACHED_SCANNERS` | **범위 선언형** + 탐지형 | 신설 | AST 인구조사 양방향 + 이력 → 빈 선언에서 통과 안 함 ✓ |
| `MIN_TESTS_*` · `MIN_ASSERTIONS_BY_CLASS` | **강제·표현형** (하한) | 클래스 축만 상환 | 메서드·단언 축에 창 → **M-3** |
| 리뷰 커버리지 표 + 판정자 | **범위 선언형** | 표 2행 증가 | 역할 구성 미검사 → **M-4** |
| 이연 장부 + 판정자 | **강제·표현형** | 4행 증가 | `닫힘` 회수 장치 0 → **M-6** |
| `{scope}` 정본 표 | **범위 선언형** | 변화 없음(실사용만 드리프트) | 강제자 0 → **M-5** |

### 4.2 항목별 점검

| 점검 항목 | 결과 |
|---|---|
| 「전역」·「모든」·「전건」이라 선언한 강제 수단이 **닿지 않는 경로** | **지적 2건.** ⑴ `PrivateHeaderFloorCensusTest` KDoc `:23` 이 *"하한선 열거 **전건**"* 이라 선언하는데 실도달 8/10 (**C-1**). ⑵ 계약 `:1311` 이 검사 순서를 *"복합 결함에도 적용된다"* 로 오퍼레이션 단위 선언하는데 강제자는 multipart 팔만 (**M-1**) |
| 그 게이트가 **지금 어디서 도는가** (도달 0 의심) | **검토함 — 지적 없음.** `PrivateHeaderFloorCensusTest` 는 `TEST_CLASSES:267` 에 있어 `ci:kotlin` 의 리포트 XML 대조 대상. 신설 라쳇 셋은 `ci:quality`(`:198`)·`ci:kotlin`(`:374`)에서 경로 명시로 돌고 둘 다 `fetch-depth: 0`. **실행으로 11 passed 확인.** 도달 0 항목 없음 |
| 측정이 **대리 경로**에서 이뤄지지 않았는가 | **지적 1건 (반대 방향의 개선 확인).** K-1 은 오히려 **대리 경로를 걷어낸** 조치다 — 종전 아홉 자리가 전역 필터를 태워 재는 것이 0이었고, `@WebMvcTest` 로 필터 없는 컨텍스트를 만들어 실제 개별 부착을 재게 했다. 그 컨텍스트가 정말 필터 없음은 `PrivateResponseHeadersConfig` 가 `@Configuration` `@Bean` 이라는 구조로 뒷받침되고 테스트가 직접 단언한다 ✓ 반면 **M-1** 의 JSON 팔은 measurement 자체가 없다(대리도 아니고 0) |
| 검사의 **기준이 검사 대상 자신에게서 나오지 않는가** | **검토함 — 지적 없음.** 이 회차의 새 단언은 분모를 전부 계약에서 읽는다 — `ContractSpec.globalHeaderValues()` · `privateResponseHeaderTargets()` · `successStatus()` · `schemaRequired("ConversionResponse")` · `schemaEnum(ConversionStatus)` · `ContractCheckOrder.stages()` · `pathExampleDetail()` · `responseExampleDetail()`. 특히 `ContractCheckOrder` 는 검사 **순서**를 테스트에 복제하지 않고 계약 산문에서 파싱하며(`:19-46`), `MIN_CHECK_ORDER_STAGES = 5` 로 파서가 조항을 놓치면 끊는다 ✓ 「아홉」도 코드에 안 적고 `required − 넷` 으로 계산한다 ✓ |
| 판정이 **대리 지표**로 이뤄지지 않는가 | **지적 1건.** `PrivateHeaderFloorCensusTest:85-104` 가 「핸들러가 없다」를 **상태 코드 404/405** 라는 대리 지표로 판정한다. 저장소 자신이 그 대리가 틀렸음을 아는 단언을 갖고 있다(`ConversionReadReachTest:334-342` — 파기 404 와 매핑 부재 404 는 **본문에서만** 갈린다) → **C-1** |
| 규칙·패턴의 **범위가 근거보다 넓지 않은가** (은폐형 확대) | **검토함 — 지적 없음.** S-2 가 정확히 이 판단을 했고 옳다 — `VARIABLE_HEADERS` 를 넓히는 대신 상한을 걸어 「증가를 드러내는」 쪽으로 갔다(`tests/test_kotlin_gate_reach.py:858-861`). `MAX_TIMING_RATIO` 사본 넷을 **합치지 않은** 판정도 옳다(영향 범위 · 모듈 경계, `:882-895`). 새로 넓어진 은폐형 없음. 단 새로 **생긴** 은폐형에 상한이 없다 → **C-2** |
| **음성 대조**가 붙어 있는가 | **부분.** ⑴ `PrivateHeaderFloorCensusTest:33-43` 은 명시적 음성 대조를 테스트 안에 상주시켰다 — 모범적이다 ✓ ⑵ G-1·G-2 는 커밋 시점 실측을 주석에 적었다(`:983-989` 데코레이터 삭제 → 281 passed · exit 0; `:1030-1037` 두 낱말 삭제 → 280 passed) ✓ ⑶ L-㉛ 이 일회용 worktree 에서 「전」을 재고 세 축이 같은 여섯을 지목했다고 적는다 ✓ ⑷ **이 리뷰가 낸 지적의 음성 대조는 미실행** — 감사 회차라 저장소를 변경하지 않았다(§6) |
| 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **검토함 — 지적 없음.** `RATCHET_NAME_TUPLE_PINS` ↔ `RATCHET_PIN_TABLES` 상호 폐쇄를 확인했다(§3.5). `CACHED_SCANNERS` 는 AST 인구조사와 양방향 + 자기 이름이 이력 축에 있다(2층, 규칙 7 상한 안). `test_이_파일의_수치_상수가_전부_분류돼_있다` 는 자기 파일을 전수로 본다 — 단 **Kotlin 확장분은 그 자기검사 밖**(**M-2**) |

---

## 5. Phase 종료 조건 대비 현황

> **1차 산출물이므로 판정이 아니다.** 리더가 2단계 교차 종합(`..._cross.md`)을 근거로 판정한다.

| 종료 조건 (§5 Phase 4 / §6) | 이 범위 기준 현황 | 미해결 |
|---|---|---|
| Contract 게이트 — status/body/header/error 가 v1 spec 과 일치 | 조회 경로 크게 개선(완료 전 노출 아홉 닫힘 · 500 문구 정합 · 13키 정확 일치 세 상태 전수) | **M-1** (JSON 팔 검사 순서 + `detail` 모양) |
| 사적 응답 헤더 하한선 10곳 | 1/10 → **8/10** (K-1) | **C-1** (남은 2/10 의 유보 해제 장치 도달 0) |
| 게이트·탐지기 자신의 무결성 | 라쳇 4축 신설·상환·상호 폐쇄로 크게 개선 | **C-2**, **C-3**, **M-2**, **M-3** |
| 보장 재배치 추적(§6) | 하한 표 3종 운영 중 | **M-3** (이 회차 강제자 5개가 창 안), **C-3** (표 밖) |
| 리뷰 게이트 무결성 | 커버리지·장부 강제자 동작 중(리더 미기재를 4회 잡았다) | **M-4**, **M-5**, **M-6** |
| 보안 불변식(I-7·마스킹 대응표·소유권 은닉) | 변조 거부 팔 신설 · `failed` 대응표 차단 · 은폐 목록 상한 2건 | 신규 지적 없음 (최종 판정은 `privacy-gate`) |

### 5.1 차단 항목과 마감

| ID | 갈래 | 마감 (그 게이트의 첫 실사용) |
|---|---|---|
| **C-1** | ② 장치 | Phase 4 `export` 착수 / C7 `PUT` 착수 — **그 시점에 유보가 자동으로 끊기지 않으므로 그 전에** |
| **C-3** | ② 장치 | Phase 4 `documents` 종료 판정 (DC-26·DC-27 이 근거로 인용되는 시점) |
| **C-2** | ② 장치 — **조건부** | C-1 과 같다. C-1 이 처방대로 닫히면 **권고로 내려간다**; C-1 이 미조치로 남으면 **차단으로 올려야 한다**. 조건부 판정을 그대로 올린다 |

**착수 차단 여부는 판정하지 않는다** — 리더에게 넘긴다. 다만 「아직 안 쓰이는 게이트니 차단이
아니다」로 스스로 낮추지 않았다.

### 5.2 이전 회차 미해결 항목의 상태

| 항목 (출처) | 상태 | 근거 |
|---|---|---|
| G-γ 차단 6건 (K-1·G-1·G-2·S-1·S-2·S-5) | **해소** (K-1 은 **부분 해소** — C-1·C-2 잔여) | `b4c2fda`. K-1: `PrivateHeaderFloorCensusTest` 신설 + 바닥 편입. G-1: `CACHED_SCANNERS` + 적중 단언. G-2: `RATCHET_NAME_TUPLE_PINS`. S-2/S-5: 여유 0 상한 + 이력 라쳇. 실행 11 passed |
| L-㉚ 「`CR-3b` 분모가 잘못 준 상태를 놓친다」 | **해소** | 분모를 상태 **이름** 축으로 바꾸고 상수 자체를 계약 enum 과 대조 (`ConversionReadReachTest:104-106`, `ConversionQueryServiceTest:95-97`) |
| L-㉚·L-㉛ 「완료 전 결과 필드 셋만 닫혔다」 | **해소** | `4ac13ec` 로 아홉. 대상 집합을 계약 `required` 에서 계산 |
| L-㉛ 잔여 「`beforeDone` 이 넷을 *잘못 채우는지*는 못 잰다」 | **미해소 (부분)** | `failureCode` non-null 단언은 있다(`ConversionQueryServiceTest:107`). **`id`·`document_id` 오배정은 여전히 미검사.** 처방: CR-3b 가 이미 `createDocument(token)` 에서 `Pair<documentId, conversionId>` 를 받으므로(`:413-424`) `assertThat(body["id"]).isEqualTo(conversionId.toString())` · 같은 형태로 `document_id` 두 줄을 더하면 닫힌다 — **1회차 지적으로 올린다** |
| L-㉛ 잔여 「목록 `reviewed_at` 비대칭 — 탐지 없음」 | **미해소 (리더 판정 수용)** | 리더 판정(계약 `:2486` 이 그 필드를 적극 요구, 목록은 결과 본문 미탑재)에 **동의한다.** 코드로 확인: `DocumentListItemResponse.of`(`DocumentDtos.kt:70-82`)가 `easy_text`·`masked_items`·토큰을 아예 담지 않는다. 다만 「C7 이 전제를 깨면 계약 판정」에 **탐지가 0** 인 것은 남는다. 처방: `DocumentListReachTest` 에 「완료 전 상태의 목록 행 `reviewed_at` 이 상세 응답의 그 필드와 같다」 단언 한 줄 — 오늘은 둘 다 `null` 이라 통과하고, C7 이 전제를 깨는 순간 빨개진다. **저비용 탐지자** |
| L-㉛ 잔여 「네 번째 조립 지점 → 500 vs 계약 200」 | **미해소 (구조)** | 오늘 조립 지점이 하나뿐임을 실측 확인. R-2 가 이것을 타입으로 좁히는 처방 |
| L-㉛ 「② 의 500 문구 판정(예시 = 규범?)」 | **미결 → 이 리뷰가 판정 근거를 하나 보탠다** | 계약 `:2109-2112` 의 `InternalError.description` 이 *"`StorageError`(코드 버그) → 저장소가 만든 고정 문자열"* 이라 적는다. 즉 **문면이 「저장소가 정한다」로 위임**하므로 `examples.storage` 는 규범이 아니라 예시로 읽는 것이 자연스럽다. 그렇다면 `6c970b6` 의 문구 변경은 **계약 위반 해소가 아니라 선택**이고, R-1 이 지적한 「자원 무관 선언 vs 자원 특정 문면」 불일치가 남는다. **`contract-keeper` 판정 요청** |
| 주석 예산 (여유 53자) | **미해소 — 리더 소관** | 리더 권고 ⑵(테스트 파일을 예산에서 분리)에 대한 의견: **동의한다.** `CLAUDE.md` 의 예산 취지가 *"`.kt` 에 이력이 쌓이는 것"* 을 막는 것이고, 테스트 파일의 긴 근거 주석은 이 회차에서 실제로 **가치를 냈다**(예: `ConversionReadReachTest:352-357` 의 스캐너·가드 규약이 없으면 다음 사람이 SQL 을 호출부로 되돌린다). 다만 분리하면 그 취지의 **강제자가 테스트 쪽에서 0** 이 되므로, 분리와 함께 「테스트 파일에는 회고를 쌓아도 되는가」를 **명시**해 두어야 한다 — 안 적으면 다음 회차에 무한 팽창한다 |
| 이전 회차 충돌 항목의 리더 판단 | **확인 불가** | 1회차라 `04_documents-c6_cross.md` 를 읽지 않았다(격리). **2단계에서 확인한다** |

---

## 6. 미실행·확인 불가 항목

| 항목 | 사유 | 영향 |
|---|---|---|
| Kotlin `:test` 전체 (`PrivateHeaderFloorCensusTest`·CR-3b·DC-26·DC-27 실행) | Testcontainers·Docker·Gradle 미실행. 리더가 `b4646ee` 커밋 메시지에 *"Gradle exit 0 · 요구 모드 게이트 298 passed"* 를 적었으나 **이 리뷰가 재현하지 않았다** | 새 테스트가 **초록인지**는 리더 실측에 의존. 이 리뷰의 지적은 전부 **소스 구조**에 근거하므로 실행 결과와 독립이다 |
| 이 리뷰 지적의 음성 대조 | **감사 회차라 저장소를 변경하지 않았다**(스킬 규약: 고치지 않는다). 규칙 5 의 복원 절차(git 경유 + sha256)를 쓰려면 조치 레인의 권한이 필요하다 | **C-1 은 음성 대조 없이도 확정**이다 — 저장소가 이미 그 대조를 갖고 있다(`ConversionReadReachTest:334-342` 이 「매핑 부재 404 ≠ 자원 부재 404」를 본문으로만 가른다). **C-2·C-3·M-2·M-3 은 구조 판정**이라 실행이 필요 없다. **M-1 만 실행 확인이 있으면 더 강해진다** — 조치 레인이 JSON 팔에 복합 결함 요청을 한 번 보내면 `detail` 모양과 순서가 확정된다 |
| `scan_privacy_invariants.py` | 미실행 | 보안 축 최종 판정은 `privacy-gate` 소관. 이 회차의 새 SQL(`MARK_DONE_SQL` 에 `reviewed_at = %s` 추가)이 스캐너의 논리 줄 결합기에 걸리는지 확인 못 함 — **`privacy-gate` 에 확인 요청** |
| 골든셋 (`tests/golden`) | 프롬프트·스타일 규칙·LLM 설정 미변경이라 해당 없음 | 없음 |
| `parity/` 리포트·도메인별 mismatch·coverage | 이 범위가 parity 도메인에 닿지 않는다 | 축 B 를 「검토함 — 지적 없음」으로 낼 근거는 diff 자체로 충분 |
| `privacy-gate` 감사 산출물 (이 회차분) | 존재하지 않음(`ls reviews/` 확인 — `04_documents-c6r2_privacy-gate.md` 없음) | 보안 축을 **감사 산출물 없이** 리뷰했다. 판정이 갈리면 `privacy-gate` 우선 |
| codex 레인 산출물 | **1차 회차라 존재하지 않는다 — 정상이다.** 재요청하지 않았고 실패로 기록하지 않았다 | 교차 대조표는 이 문서에 **만들지 않았다**. 2단계에서 만든다 |

---

## 7. 리더에게 (1차 인계)

- **산출물 경로**: `docs/migration/_workspace/reviews/04_documents-c6r2_migration-reviewer.md`
- **교차 종합 재호출 필요**: **예.** 어간 `04_documents-c6r2` 로 codex 산출물
  (`04_documents-c6r2_codex-reviewer.md`)과 이 파일 두 경로를 주어 2단계로 불러 달라.
  정본은 `04_documents-c6r2_cross.md` 다.
- **지적 요약**: 차단 2(C-1, C-3) · 수정 필요 5(C-2, M-1, M-2, M-3, M-4) + 규약 3(M-5, M-6 은
  수정 필요) · 권고 3(R-1, R-2, R-3) · 판정 필요 2(500 문구의 예시=규범 여부 → `contract-keeper`,
  `MAX_TIMING_RATIO` 사본 열거의 리더 핀 범위)
- **이 회차의 성과를 깎지 않는다**: K-1 은 사적 헤더 하한선의 실측 도달을 **1/10 → 8/10** 으로
  올렸고, 완료 전 노출 강제자는 **네 자리 전부에 실제 단언**을 갖췄으며, 라쳇 상호 폐쇄와 캐시
  인구조사는 예산 축이 원리적으로 못 보는 절반을 실제로 메웠다. 지적은 그 위에 남은 **가장자리**다 —
  특히 **C-1·C-2·C-3 은 셋 다 「이 회차가 만든 좋은 장치를 보호하는 층」의 문제**이고 처방이
  전부 저장소 안에 이미 있다(`ServedOperations`, `_named_enforcer_census`, S-2/S-5 의 상한 형태).
- **누가 고치는가**: C-1·C-2·C-3·M-2·M-3·R-3 → `kotlin-implementer` + 하네스 레인.
  M-1·R-1 → `contract-keeper` 판정 후 `kotlin-implementer`. M-4·M-5·M-6 → 하네스 레인
  (`.claude/**` + `tests/**`). **`00_progress.md` 편집은 리더 소관이라 손대지 않았다.**
- **범용 품질 축**: 이 리뷰는 마이그레이션 고유 축만 봤다. 보안·성능·유지보수성의 일반 관점이
  필요하면 글로벌 `multi-review` 를 별도로 돌리는 것을 권고한다.

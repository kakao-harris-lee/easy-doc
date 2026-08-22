# 하네스 단위 레인 A — 계획 (`04_documents-c6r2` Kotlin 쪽 차단 해소)

**정본 근거**: `docs/migration/_workspace/reviews/04_documents-c6r2_cross.md`
**소유 파일**: `backend-kotlin/**` · `tests/test_kotlin_gate_reach.py`
**금지**: `tests/test_review_coverage_reach.py` · `tests/test_harness_scope_reach.py` ·
`tests/test_kotlin_comment_budget.py` · `.claude/**` · `CLAUDE.md` · `docs/migration/_workspace/00_progress.md` ·
`contracts/easy-doc-v1.yaml`(개정 필요 시 보고만)

---

## 0. 착수 전 실측 — 판정 장치에 직접 물었다 (`grep` 아님)

`_declared_test_count` · `_assertion_tokens` 를 모듈로 적재해 물은 값. 교차 종합 1차 §3.5 표의
`grep` 값과 **여섯 행 중 셋이 다르다** — 그 표 자신이 「게이트 값은 이보다 작거나 같을 수 있다」고
적었고, 실제로 그랬다. **아래 값이 기준이다.**

| 클래스 | `@Test` (장치) | §3.5 `grep` | `assert…(` (장치) | 현재 하한 (`@Test` / `assert`) |
|---|---|---|---|---|
| `DocumentEndpointReachTest` | **19** | 20 | **56** | 없음 / 없음 |
| `DocumentListReachTest` | **13** | 14 | **31** | 11 / 29 |
| `ConversionReadReachTest` | **12** | 13 | **45** | 10 / 34 |
| `ConversionReadContractTest` | **6** | 6 | **26** | 5 / 21 |
| `PrivateHeaderFloorCensusTest` | **3** | 3 | **7** | 3 / 7 |
| `OwnershipPredicateGuardTest` | **14** | 14 | **16** | 13 / 15 |

계약 쪽 실측(`yaml.safe_load` 로 직접): `x-private-response-headers.applies_to` **10** ·
`x-global-response-headers.headers` **2** · `x-retired-responses` **1** ·
최상위 `x-` 노드 **15** · `unreachable_by_filter.cases` **6** · 오퍼레이션 **14** ·
`x-request-field-constraints.fields` **5**.

Kotlin 테스트의 `MIN_*`/`MAX_*` 상수 전수: 선언 **25**개 / 이름 **19**종
(`MAX_TIMING_RATIO` 4 · `MIN_MEASURABLE_MILLIS` 3 · `MIN_SECRET_BYTES` 2 · `MAX_UPLOAD_BYTES_KEY` 2 · 나머지 1).

---

## 1. 재사용하는 기존 것 (바퀴를 새로 만들지 않는다)

| 처방 | 재사용할 기구현 | 위치 |
|---|---|---|
| X-6 엔진 직접 질의 | `ServedOperations.methodsOn(handlerMapping, environment, path)` | `api/src/test/.../support/ServedOperations.kt:41-45` |
| X-6 주입 선례 | `@WebMvcTest` 슬라이스에서 `RequestMappingHandlerMapping`·`Environment` 오토와이어 | `AuthenticationCoverageContractTest.kt:21-25` · `ValueSlotInvariantReachTest.kt` |
| X-1b 정체성 고정 형태 | auth 3건을 이름으로 고정하는 케이스 | `RequestFieldConstraintLayerTest.kt:160-164` |
| X-1a 상한/하한 형태 | `MAX_UNGUARDED_STATEMENTS`(S-5) · `MAX_VARIABLE_HEADERS`(S-2) | `RATCHET_CEILING_PINS` |
| X-7 강제자 요구 | `_named_enforcer_census()` fail-closed + `test_명명된_강제자가_전부_개수_핀을_갖는다` | `tests/test_kotlin_gate_reach.py:1502-1607` |
| X-9 인구조사 형태 | `_kotlin_test_sources()` · `_source_pair()`/`_blanked()`/`_raw()` · `_pin_tuples_in()` · `_name_tuple_in()` | 같은 파일 |
| X-9 이력 보호 | `RATCHET_NAME_TUPLE_PINS` + `test_이름_튜플_선언이_이력에서_줄지_않았다` | 같은 파일 |
| X-10 라쳇 표 | `MIN_TESTS_IN_FLOOR_CLASS` · `MIN_TESTS_BY_NAMED_ENFORCER` · `MIN_ASSERTIONS_BY_CLASS` | 같은 파일 |
| P-3 하한 선례 | `ContractCheckOrder.MIN_CHECK_ORDER_STAGES = 5` | `support/ContractCheckOrder.kt:16` |

**새로 만드는 것은 셋뿐이다**: ⑴ Kotlin 라쳇 상수 인구조사(X-9) ⑵ 계약 확장 열거 접근자 하한
인구조사(P-3) ⑶ `PrivateHeaderFloorCensusTest` 의 정체성·상한·하한 단언(X-1a/X-1b/X-6).

---

## 2. 커밋 순서 — 각 커밋이 그 자체로 초록

세션 사망으로 작업이 소실된 이력이 있으므로 **점진 커밋**한다(리더 지시). 푸시는 하지 않는다.

| # | 항목 | 파일 | 그 커밋의 초록 조건 |
|---|---|---|---|
| C1 | 이 계획 문서 | 이 파일 | — |
| C2 | **X-9** Kotlin 라쳇 상수 양방향 인구조사 | `tests/test_kotlin_gate_reach.py` | 게이트 단독 (기존 Kotlin 핀 6개로 초록) |
| C3 | **X-1b·X-1a·X-6** 하한선 인구조사 3결함 | `PrivateHeaderFloorCensusTest.kt` + 게이트(핀 2개) | Gradle `:api:test` + 게이트 |
| C4 | **X-7** 강제자 이름 지목 | `DocumentController.kt` + 게이트(개수·단언 핀) | 게이트 (인구조사 fail-closed) |
| C5 | **X-10** 하한 6행 상향 (여유 0) | 게이트 | 게이트 + Gradle |
| C6 | **P-3** 계약 확장 열거 접근자 하한 인구조사 | `ContractSpec.kt`·`ContractCheckOrder.kt` + 게이트 | Gradle + 게이트 |
| C7 | 최종 전건 | — | `./gradlew --no-build-cache --rerun-tasks build parityHarness` → 요구 모드 게이트 → `uv run pytest -q` |

---

## 3. 항목별 설계

### X-6 [차단] 유보 해제 술어를 **엔진 질의**로 바꾼다

**현재 결함**: `NO_HANDLER_STATUSES = {404, 405}`. 유보 두 자리
(`PUT /conversions/{conversion_id}` · `GET /conversions/{conversion_id}/export`)는 소유권 은닉 404 가
**계약 요구**인 자원 경로라 구현된 뒤에도 404 다 → 유보가 영구히 열리지 않는다.

**처방**: HTTP 프로브(`probeUnimplemented`)를 버리고 `RequestMappingHandlerMapping` 에 직접 묻는다.

- 유보 자리: `ServedOperations.methodsOn(handlerMapping, environment, target.path)` 에
  `target.method` 가 **없어야** 한다. 구현되는 순간 들어오므로 빨개진다.
- **같은 케이스에 양의 팔을 붙인다**: 구현된 8자리는 그 집합에 **있어야** 한다. 이것이 이
  술어 자신의 음성 대조다 — 질의가 언제나 빈 집합을 돌려주는 변이에서 유보 단언만 있으면
  공허하게 통과한다.

**선행 확인 1건 (실측 필요)**: `ServedOperations.isProductionClass` 가 요구하는
`easydoc.kotlin.source.root` 배선이 이 `@WebMvcTest` 슬라이스에서 서는가.
근거 예상: `backend-kotlin/build.gradle.kts:139,226` 이 **모든 Test 태스크**에 그 속성을 주고,
같은 파일이 이미 `ContractSpec`(같은 속성 사용)을 쓰며, `AuthenticationCoverageContractTest` 가
`@WebMvcTest` 에서 `ServedOperations.of` 를 호출한다. **실행으로 확정한다.**

### X-1b [차단] 하한선 목록을 **정체성**으로 고정한다 (상류 절단)

계약 `applies_to` 에서 5건을 지우면 auth 3건만 돌고 초록. **개수만 고정하면 동일 개수 치환이
통과**하므로 (메서드, 경로) 10짝을 `DECLARED_FLOOR_TARGETS` 로 선언하고 계약 집합과
**양방향 일치**를 단언한다.

### X-1a [조건부 차단] 유보 목록에 **상한**, 분모에 **하한**

`MAX_DEFERRED_FLOOR_TARGETS = 2`(여유 0) · `MIN_FLOOR_CENSUS_TARGETS = 8`(여유 0).
두 상수는 Kotlin 이므로 **X-9 인구조사가 핀을 요구**한다 → `RATCHET_CEILING_PINS`(상한) ·
`RATCHET_SCALAR_PINS`(하한).

### X-7 [차단] DC-26·DC-27 을 하한 표에 **자동으로** 들여보낸다

새 표·새 층을 만들지 않는다(규칙 7 — 열거형 3층). `DocumentController` 의 기존 주석
(인자 평가 순서 불변식을 적은 자리)에 `DocumentEndpointReachTest` 를 **이름으로** 지목하면
`_named_enforcer_census` 가 자동으로 분모에 넣고 핀을 요구한다(fail-closed).
그 요구를 `MIN_TESTS_BY_NAMED_ENFORCER = 19` · `MIN_ASSERTIONS_BY_CLASS = 56` 으로 채운다(장치 실측).

**주석 예산**: 여유 53자뿐이므로 근거를 `.kt` 에 쓰지 않는다. 기존 주석에 강제자 이름만
백틱으로 얹고 사유는 이 문서에 둔다. 상한은 올리지 않는다.

### X-9 [수정 필요] Kotlin 라쳇 상수 **양방향** 인구조사

`KOTLIN_RATCHETED_CONSTANT_NAMES` 를 선언하고,
`_kotlin_test_sources()` 전수에서 그 이름의 `val` 선언을 찾아 **(파일, 이름) 짝**을 만든다.
그 집합이 세 핀 표(`RATCHET_SCALAR_PINS`·`RATCHET_CEILING_PINS`·`RATCHET_CEILING_DECIMAL_PINS`)의
**Kotlin 경로 부분집합과 정확히 같아야** 한다. → 다섯째 `MAX_TIMING_RATIO` 사본이 생기면
인구조사가 찾고 핀이 없어 빨개진다(**리더 판정 P-2**: 범위는 「인구조사가 찾는 것」이다).

이름 튜플 자신은 `RATCHET_NAME_TUPLE_PINS` 에 넣어 이력이 지킨다.

**닫지 못하는 것 (정직하게)**: 이 인구조사에 **없는 새 이름**으로 라쳇 성질의 Kotlin 상수를
만드는 경로. 사유 있는 면제표(`KOTLIN_NON_RATCHET_NAMES`)로 그 자리를 닫는 갈래는
**버린다** — `test_라쳇_핀_목록이_이력에서_줄지_않았다` 의 KDoc 이 같은 후보를 이미 기각했고
(*"허위 사유 문장으로 바꿀 뿐이고 그 사유가 참인지 재는 실행이 다시 0"*), 규칙 4 ⑵ 의
은폐형 거부권에 걸린다. `_bound_direction` 은 이 파일의 Python AST 만 보므로 Kotlin 방향을
기계로 판정할 수단이 오늘 없다.

### X-10 [수정 필요] 하한 6행을 오늘 실측으로 (여유 0)

§0 표의 장치 값으로 올린다. `PrivateHeaderFloorCensusTest` 는 C3 이후 값이 커지므로
**C5 에서 재측정해** 넣는다. `DocumentEndpointReachTest` 는 C4 가 인구조사로 자동 요구한다.

### P-3 적용 [리더 판정] 「계약에서 분모를 읽는 것」의 안전 조건을 기계로 요구한다

리더 판정: *분모를 계약에서 읽는 것은 계약 쪽 집합에 자기 하한이 붙어 있을 때만 안전*.
선례는 `ContractCheckOrder.MIN_CHECK_ORDER_STAGES = 5` 이고 **K-1 에만 없었다.**

**분모를 소비자 쪽에 두지 않는다.** 계약 확장 열거를 읽는 테스트 파일은 31개까지 퍼져 있고
(실측), 그 전부에 하한을 요구하면 오탐이 곧 면제 목록을 낳는다(규칙 4 ⑵). 대신 하한을
**접근자 쪽 한 곳**에 둔다 — 계약 편집으로 집합이 깎이면 소비자 전부가 한 번에 보호된다.

**인구조사(열거 아님)**: Kotlin 테스트 소스 전수에서
`fun 이름(): List<…>|Set<…>|Map<…>` 형태의 **무인자 열거 접근자** 중 본문에 `"x-…"` 리터럴이
있는 것. 그 본문은 같은 파일에 선언된 `MIN_…` 상수를 참조해야 하고, 그 상수 이름은
`KOTLIN_RATCHETED_CONSTANT_NAMES` 에 있어야 한다(→ 이력이 지킨다).

- **무인자** 가 구조적 판별자다. `authStrings(key)`·`inputLimit(name)` 처럼 호출자가 키를 주는
  조회는 계약 쪽 집합이 고정되지 않으므로 하한이 정의되지 않는다.
- 오늘 분모 예상 5: `globalResponseHeaders()`(2) · `privateResponseHeaderTargets()`(10) ·
  `retiredResponseStatuses()`(1) · `extensionNodeNames()`(15) · `containerRejectedCases()`(6).
  기존 `MIN_CHECK_ORDER_STAGES` 도 인구조사에 편입시켜 핀을 붙인다.
- **닫지 못하는 것**: `storedTextDomain()` 처럼 열거를 읽고 **래퍼 타입**을 돌려주는 접근자는
  반환 타입 모양으로 걸리지 않는다(오늘 `require(isNotEmpty())` 만 있다). 보고에 남긴다.

---

## 4. 음성 대조 계획 — 「구멍이 초록이었음」을 먼저 재고, 처방 뒤 「지목」을 잰다

판정 기준은 「무언가 빨개졌는가」가 아니라 **「겨눈 장치가 그 자리를 지목했는가」**다.
변이는 **저장소 밖 일회용 `git worktree`**(`git worktree add --detach` → `remove --force`)에서만
한다. 본 저장소를 변조하지 않으므로 복원 절차가 필요 없다. 파이프를 쓰지 않는다.

| ID | 변이 | 처방 전 기대 | 처방 후 기대 지목 |
|---|---|---|---|
| N-1 | 계약 `applies_to` 에서 구현 대상 5건 삭제 | 초록 | X-1b 정체성 케이스 |
| N-2 | `applies_to` 10건을 **개수 유지·경로 치환** | 초록 | X-1b (개수 고정으로는 못 잡음을 대비 대조) |
| N-3 | `NOT_YET_IMPLEMENTED` 에 구현된 자리 6개 추가 | 초록 | X-1a 상한 + 하한 |
| N-4 | 유보 두 자리를 **구현했다고 가정**(스텁 핸들러 추가) | 초록(404 유지) | X-6 엔진 질의 |
| N-5 | `ServedOperations.methodsOn` 이 항상 빈 집합 | 초록 | X-6 양의 팔 |
| N-6 | `DocumentEndpointReachTest` 에서 DC-26·DC-27 삭제 | 초록 | X-7 개수 핀 |
| N-7 | 다섯째 `MAX_TIMING_RATIO` 사본 추가 | 초록 | X-9 인구조사 |
| N-8 | Kotlin 핀 하나를 표에서 삭제 (상수는 남김) | (기존 이력 축이 잡음) | X-9 + 이력 축 |
| N-9 | `OwnershipPredicateGuardTest` 의 S-5 테스트 하나 삭제 | 초록 | X-10 상향된 하한 |
| N-10 | `privateResponseHeaderTargets()` 의 `MIN_` 하한 삭제 | 초록 | P-3 인구조사 |
| N-11 | 새 무인자 `x-` 열거 접근자를 하한 없이 추가 | 초록 | P-3 인구조사 (fail-closed) |

## 5. 검증 (규약)

표식을 **빌드 앞에서** 박고 같은 값으로:
`./gradlew --no-build-cache --rerun-tasks build parityHarness` →
`KOTLIN_GATE_REACH_REQUIRE_REPORT=1` 요구 모드 게이트 → `uv run pytest -q`.
(초판은 존재하지 않는 이름 `KOTLIN_GATE_REACH_REQUIRE_FRESH_REPORTS` 를 적었다 —
없는 변수 설정은 **조용한 무동작**이라 그 문면대로 검증하면 요구 모드 5축이 한 번도
판정되지 않은 채 「요구 모드 통과」가 성립한다. `xx_harness-r2` M-1, 실측으로 재현됨.)
`uv run pytest -q tests/test_kotlin_comment_budget.py`(Kotlin 변경 후) — **파일은 건드리지 않고 실행만** 한다.

## 6. 보고할 잔여 (미리 적어 둔다)

- X-9: 인구조사에 없는 **새 이름**의 Kotlin 라쳇 상수 — 자동 탐지 0.
- P-3: 열거를 읽고 래퍼 타입을 돌려주는 접근자(`storedTextDomain()`) — 모양으로 안 걸린다.
- X-1b: 계약 자신이 규범인가는 재지 않는다. 정체성 집합과 계약이 **함께** 바뀌는 편집은
  리뷰 diff 가 최종 방어선이다(`ci.yml:263-264` 와 같은 자리).

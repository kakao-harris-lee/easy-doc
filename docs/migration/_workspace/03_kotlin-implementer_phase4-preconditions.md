# 게이트 24 확정 2건 — Phase 4 착수 전 조건

- **작성**: `kotlin-implementer` (2026-08-19)
- **입력**: `reviews/03_phase3-close_cross.md` §3-ⓑ·ⓔ · `reviews/03_security-phase3-close_privacy-gate.md` §1-5(A-3′) ·
  `reviews/03_phase3-close_codex-reviewer.md`(X24-3·X24-5) · `reviews/03_phase3-close_migration-reviewer.md`(R-5)
- **커밋**: `44eec3f` (7 파일, +801 / −216). 손댄 것은 **`backend-kotlin/api` 의 테스트·빌드 스크립트뿐**이다 —
  제품 Kotlin 코드 0줄, `contracts/` 0줄, `00_progress.md`·`.claude/`·`frontend/`·Python 0줄.

| 항목 | 처분 |
|---|---|
| ⓑ A-3′ (privacy-gate 실측) — 탐지기가 value-class-first `data class` 를 통째로 건너뜀 | **해소** (§1) |
| ⓑ R-5 (Claude) — 제외 사유가 강제되지 않는 불변식 | **해소** (§1.3·§1.4) |
| ⓑ X24-3 (codex) — 이름 규약 밖 `String` 은 여전히 후보가 아니다 | **부분** — 컬렉션 절반은 닫혔고 넓은 강제는 **채택하지 않았다**(§1.5, 리더 판단 요청) |
| ⓔ X24-5 (codex) — 인라인 헤더를 조용히 무시 | **해소.** 단 **전제 정정 1건**(§2.1) |

---

## 1. A-3′ + R-5 — `SensitiveToStringReachTest` 를 Kotlin 반사로 다시 세웠다

### 1.1 무엇이 틀렸었나 (재현 확인)

privacy-gate 의 기제 서술을 그대로 재현했다. 종전 판(`70ec78f`)은 `componentN()` 접근자를
`Regex("""component\d+""")` 로 세어 필드 목록을 만들었는데, `@JvmInline value class` 파라미터의
`componentN()` 은 JVM 에서 이름이 맹글링돼 그 정규식에 걸리지 않는다. 계수가 어긋나면
`fields.take(components)` 가 짧아지고 생성자 타입 비교가 실패해 `:124` 의 `?: return@mapNotNull null` 로
**클래스가 통째로 빠졌다.**

실측(§3 음성 대조 NC1/NC2): 종전 판에서 `PgAudit24ValueFirst(head: <value class>, body: String)` 는
**본문 전문을 그대로 찍는데 BUILD SUCCESSFUL** 이었다. 제품 실례는 `core.privacy.MaskingResult` 다.

### 1.2 고친 방식 — 판정 근거를 주 생성자 파라미터로

`kotlin-reflect` 의 `primaryConstructor.parameters` 로 이름·타입을 읽는다. 맹글링·박싱과 무관하게
소스에 적힌 그대로를 보므로 A-3′ 의 기제가 성립하지 않는다. `kotlin-reflect` 는 이미 `api` 의
**런타임 의존**이었고(`runtimeOnly`), 컴파일 시점으로 한 단계 올린 것뿐이다(락파일 변경 1줄).

**탈락 경로를 없앴다.** 후보 선정의 두 `return null` 갈래를 지웠고, 파라미터 타입을 만들 줄 모르면
`error()` 로 끊는다. 판정 불가는 통과가 아니다(`CLAUDE.md` 규칙 4 ⑶).

- 새 파일: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/GeneratedToStringProbes.kt`
- 새 파일: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ProductClasses.kt`
- 고친 파일: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SensitiveToStringReachTest.kt`

### 1.3 R-5 ⓐⓑ — 제외 사유를 **단언**으로 옮겼다

종전 KDoc 은 *"`Secret`·`MaskedText` 처럼 값을 감싸는 타입이 자기 `toString()` 에서 이미 가리기
때문"* 을 「`String` 이 아닌 파라미터는 대상 아님」의 사유로 적었다. 참이었지만 **그 성질을 강제하는
장치가 없었다.**

신설 테스트 `값을 감싸는 타입이 값을 찍지 않는다` 의 대상은 **열거가 아니라 두 종류**다.

1. `kr.easydoc.**` 의 `@JvmInline value class` 중 텍스트를 감싸는 것 — 클래스패스 전수.
2. `data class` 필드로 **닿은** 1-파라미터 래퍼 — 도달 기록이라 목록에 손대지 않아도 늘어난다.
   `Secret` 이 이 갈래로 들어온다(`MaskedItem.original`·`AnthropicSettings.apiKey`).

그리고 **래퍼 안쪽까지 표식을 심는다.** `data class DocumentResponse(title: ProbeTitle)` 처럼 평범한
`String` 민감 필드가 하나도 없는 DTO 도, 래퍼가 값을 찍으면 DTO 쪽에서 함께 빨개진다(NC7).

### 1.4 R-5 ⓓ — 클래스패스 제외가 검사받는다

`ProductClasses.declaredInMainSources()` 가 `<모듈>/src/main/kotlin` 소스에서 `data class`·`value class`
선언을 세고, 적재된 집합이 그것을 **전부 포함하는지** 단언한다(실측 44 선언 / 적재 179 클래스).

이것이 막는 것은 셋이다 — ⑴ 경로 표식이 제품 산출물까지 걸러 버리는 경우, ⑵ 적재 필터(합성·익명
클래스 제외)가 넓어지는 경우, ⑶ **`api` 테스트 런타임에 없는 모듈**. ⑶ 은 privacy-gate 가 부수 실측으로
기록한 `worker` 도달 0 이고, 그 모듈에 첫 `data class` 가 생기는 커밋에서 빨개진다(NC3 실측).

### 1.5 X24-3 — 채택한 것과 채택하지 않은 것

codex 처방은 *"제품 `data class` 의 `String` 및 `String` 컬렉션 필드를 **모두** fail-closed 분류하고
안전·민감 중 하나를 명시하도록 강제하라"* 였다.

- **채택**: `String` **컬렉션**. 종전 `valueFor` 는 민감 여부와 무관하게 `listOf(FILLER)` 를 넣어,
  민감 이름을 가진 `List<String>` 파라미터에 표식이 들어가지 않았다. 이제 컬렉션 원소까지 심는다.
- **채택하지 않음**: 「모든 `String` 을 명시 분류」. 사유는 둘이다.
  1. **면제 조항을 낳는다.** 「안전」을 선언하는 애너테이션이 곧 은폐형이고, `UserContent` KDoc 은
     *"검사를 끄는 용도로는 쓸 수 없다 — 면제 조항은 은폐형"* 이라고 이미 못박았다(`CLAUDE.md` 규칙 4).
  2. **범위가 근거를 넘는다.** 실측으로 새로 빨개지는 타입을 세었다 — `HealthResponse(status)`,
     `ErrorResponse(detail)`, `ValidationErrorItem(loc·msg·type)`, `Argon2Phc(variant)`,
     `AnthropicSettings(model·baseUrl)`, `LlmProperties`, `AuthProperties` 등 **8~10 종**이고, 전부
     고정 문구·설정값이라 가려서 얻는 것이 없고 진단만 잃는다.
- **남은 빈자리**: `data class ExportEnvelope(payload: String)` 처럼 **이름 규약 밖의 `String`**.
  오늘 제품 코드에 해당 0건. 메우는 장치는 `@UserContent` 이고, 그 사실을 테스트 KDoc 의
  「막지 못하는 것」 ⑵ 에 **선언으로 적었다**(R-5 ⓓ 가 요구한 형태).
- **리더 판단 요청**: 위 트레이드오프를 뒤집을지 여부. 이 산출물은 뒤집지 않은 상태다.

---

## 2. X24-5 — 계약 헤더 파서를 fail-closed 로

### 2.1 전제 정정 — **오늘 계약의 인라인 헤더는 0건이 아니라 2건이다**

리더 지시와 교차 종합 ⓔ 는 *"오늘 계약에 인라인 헤더 0건이라 빨강 0"* 을 전제했다. 실측은 다르다
(`contracts/easy-doc-v1.yaml`, 2026-08-19 파싱):

| 헤더 | 자리 | 왜 인라인인가 |
|---|---|---|
| `Location` | `POST /documents` 202 | 값이 `/conversions/{id}` 로 **계산된다** — `const` 로 못박을 수 없다 |
| `Content-Disposition` | `GET /conversions/{id}/export` 200 | 파일명이 문서마다 다르다(RFC 5987 확장 포함) |

`$ref` 갈래는 20건(`Cache-Control` 10 · `X-Content-Type-Options` 10)이고, 그 밖에
`components/responses/Unauthorized` 가 `WWW-Authenticate` 를 든다.

따라서 codex 처방 후단(*"지원 전까지 `$ref` 없는 모든 헤더 선언에서 명시적으로 실패하라"*)을 문면
그대로 넣으면 **오늘 즉시 빨간불**이고, 그것은 계약이 잘못돼서가 아니라 파서가 갈래를 하나만 알기
때문이다. Phase 4 의 두 엔드포인트를 만들기 전에 계약을 고치는 것은 `contract-keeper` 소관이고
이 산출물의 범위 밖이다.

### 2.2 그래서 무엇을 넣었나 — 버리는 대신 갈래로 나눠 센다

`ContractSpec.collectHeaderRefs` 의 `?: return@forEach` 를 없애고
`headerDeclarations(): Map<String, ContractHeaderDeclaration>` 로 바꿨다. fail-closed 는 네 자리다.

1. 응답·헤더 노드가 매핑이 아니면 끊는다(파서가 읽을 줄 모르는 모양 — `requiredOf`·`pathParameters`·
   `errorDetailUnionTypes` 가 X-4 에서 받은 처방과 같은 형태).
2. 응답이 `$ref` 면 `components/responses/…` 를 **따라 들어간다.** 종전에는 따라가지 않아
   `WWW-Authenticate` 선언이 이 표에 **한 번도 오르지 못했다** — X24-5 와 같은 종류의 빈자리다.
3. 인라인 선언에 `schema` 가 없으면 끊는다(NC6).
4. 같은 이름이 계약 안에서 **서로 다르게** 선언되면 끊는다 — 컴포넌트 ↔ 인라인 혼재 포함(NC5).
   전역 부착 헤더가 인라인이면 `globalHeaderValues()` 가 별도로 끊는다.

### 2.3 마감의 **강제자** — 인라인 집합을 고정한다

새 파일 `ContractHeaderDeclarationTest.kt` 가 `inlineHeaderNames()` 를 `[Location, Content-Disposition]`
으로 고정한다. 셋째가 들어오는 커밋이 실패하고, 그때 정할 것은 둘 중 하나다 — 값이 고정이면
`components/headers` 로 옮겨 `const` 를 주고, 계산되는 값이면 형식을 재는 테스트를 함께 넣는다.
codex 가 지적한 「그 커밋을 실패시키는 강제자」가 이것이다. 목록을 늘리는 것 자체가 리뷰에 올라가는
diff 이므로 면제가 조용히 자라지 않는다.

같은 파일이 ⑴ 헤더 5종(`WWW-Authenticate` 포함)이 세어지는지, ⑵ 컴포넌트 갈래가 전부 `const` 를
갖는지도 확인한다.

---

## 3. 음성 대조 (일회용 worktree, `git worktree add --detach`)

**절차**: 주 트리는 한 번도 건드리지 않았다. 주입은 worktree 안에서만 했고 복원은 `git checkout --`
+ sha256 대조로 확인했다(`Masking.kt` 복원 후 sha `fa02f799…` 가 주 트리와 동일). 마지막에
`git worktree remove --force` 로 지웠고 `git worktree list` 에 남은 항목 없음.

| # | 주입 | 종전 판(`70ec78f`) | 이 판(`44eec3f`) |
|---|---|---|---|
| NC1 | `PgAudit24Control(id: String, body: String)` | **RED** | **RED** |
| NC2 | `PgAudit24ValueFirst(head: @JvmInline value class, body: String)` | **초록(미탐)** ← A-3′ | **RED** |
| NC2b | `@JvmInline value class PgAudit24Head(val v: String)` 자체 | 검사 대상 아님(테스트 2개뿐) | **RED** ← R-5 ⓐ |
| NC3 | `worker/src/main` 에 `data class PgAudit24WorkerDto(id, body)` | 검사 없음 | **RED**(소스 대조) ← R-5 ⓓ |
| NC4 | `MaskedText.toString()` 재정의 제거 | — | **RED**(래퍼) |
| NC4b | `MaskedText` + `MaskingResult` 재정의 **둘 다** 제거 | — | **RED**(`MaskingResult` 후보로 등장) ← A-3′ 검증 ⑶ |
| NC5 | 계약에 인라인 헤더 1건 추가(`X-PgAudit24-Inline`) | **초록(조용한 무시)** | **RED** |
| NC5b | `Cache-Control` 을 한 경로에서 인라인으로 바꿔치기 | **초록(조용한 무시)** | **RED**(정본 둘) |
| NC6 | `schema` 없는 인라인 헤더 | **초록** | **RED** |
| NC7 | `ProbeDocumentResponse(id, title: ProbeTitle)` — 누출이 래퍼를 통해서만 | 검사 없음 | **RED** ← R-5 ⓑ |
| NC7b | 재정의를 **가진** 래퍼와 그것을 든 DTO(`ProbeSafeTitle`) | — | **초록**(과잉 탐지 0) |

- NC4 단독(= `MaskingResult` 재정의만 제거)은 **초록이고 그것이 옳다** — `MaskedText` 가 가리므로
  실제 누출이 없다. A-3′ 가 지적한 것은 「누출」이 아니라 「탐지 밖」이었고, 그 해소 증거는
  NC4b(둘 다 제거 → `MaskingResult` 가 후보로 나타나 RED)와 `KNOWN_SENSITIVE_TYPES` 바닥 통과다.

---

## 4. 검사 표

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 전 구간 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **exit 0** (81 tasks executed) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **exit 0** |
| 모듈 건수 | `settings.gradle.kts` | **5** (`core`·`application`·`infrastructure`·`api`·`worker`) — 변동 없음 |
| 테스트 건수 | 모듈별 XML 집계 | core 359 · application 44 · infrastructure 115 · **api 183** · worker 3 — 실패·오류·건너뜀 **0** |
| 스캐너(CI 명령) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** |
| Python lint | `uv run ruff check .` | **exit 0** |
| Python 타입 | `uv run mypy . .claude` | **exit 0** (137 files) |
| Python 테스트 | `uv run pytest -q` | **exit 0** — 1281 passed · 68 skipped · 5 deselected · 5 xfailed (§4.1) |
| Python 무변경 | `git status --porcelain` | 커밋 대상 7 파일 전부 `backend-kotlin/api/**` — `app/`·`tests/`·`contracts/`·`.claude/`·`frontend/`·`00_progress.md` **0건** |

### 4.1 Python 테스트

`uv run pytest -q` 는 이 배치와 **파일이 겹치지 않는다**(Python 0줄 변경). 실행 시점
2026-08-19, 결과 **1281 passed / 68 skipped / 5 deselected / 5 xfailed / 실패 0**, 67.95s.

privacy-gate 레인이 같은 시각 스캐너(`.claude/skills/migration-safety-gate/scripts/`)를 고치고 있어
`tests/test_privacy_scanner.py` 의 결과는 그쪽 진행 상태를 반영한다. 이 시점에는 전건 통과였고,
이후 그쪽 커밋으로 값이 달라지면 그것은 이 배치의 회귀가 아니다 — 파일 경로로 가른다.

---

## 5. 남은 것 · 다음 사람에게

1. **X24-3 의 남은 절반**(§1.5) — 이름 규약 밖 `String`. 오늘 대상 0건, 장치는 `@UserContent`,
   빈자리는 KDoc 에 선언. **넓은 강제로 갈지는 리더 판단.**
2. **`worker` 모듈 도달** — 소스 대조가 「빨개지게」는 만들었지만 **검사하게** 만들지는 않았다.
   Phase 5 가 worker 에 DTO 를 만들 때 이 테스트를 그 모듈에서도 돌리는 배선이 필요하다.
   지금 미리 옮기지 않은 이유: 오늘 worker 에 `data class` 가 0건이라 배선의 도달도 0 이고,
   도달 0 인 장치를 세우는 것이 이 저장소가 반복해 온 실패 형태다.
3. **인라인 헤더 두 건의 값 검증**(§2.1) — `Location`·`Content-Disposition` 의 실제 형식은 Phase 4 가
   그 엔드포인트를 만들 때 재야 한다. 오늘 확인한 것은 「계약이 `schema` 로 형식을 적어 두었다」까지다.
4. **`Secret`·`PasswordHash`** — `Secret` 은 도달로 검사 범위에 들어왔다(§1.3). `PasswordHash` 는
   오늘 어떤 `data class` 의 필드도 아니라 **닿지 않는다**. 그쪽은 여전히 개별 KDoc 규율뿐이다.

---

## 6. 게이트 25 — 소스↔적재 대조 키를 **바이너리 이름**으로 (stop-time 지적 반영)

§1.4 가 세운 「소스에 선언된 타입이 전부 탐지 범위에 든다」는 **공허하게 통과할 수 있었다.**
지적을 받고 확인·수정한 내용이다. §1~§5 는 그대로 두고 여기 덧붙인다.

### 6.1 무엇이 틀렸었나

대조가 **단순 이름**으로 이뤄졌다 —
`declared.filterNot { it.simpleName in loaded }`(`loaded` 는 `KClass.simpleName` 집합).
그래서 `worker` 모듈의 DTO 가 다른 모듈의 **같은 단순 이름** 클래스와 충돌하면,
클래스패스에 `worker` 가 통째로 없는데도 「발견됨」으로 통과한다.

이것은 §1.4 가 막으려던 것과 정확히 같은 형태의 결함이다. §1.4 는 「제외 사유가 검사받지
않는다」를 고쳤는데, 그 검사 자체가 **키 충돌로 무력화**될 수 있었다.

### 6.2 고친 방식

- 소스 쪽에서 `package` 선언과 **중첩 사슬**을 읽어 JVM 바이너리 이름을 만든다
  (`kr.easydoc.application.conversion.Outcome$Body`). 적재 쪽은 `KClass.java.name`.
- 중첩 판정은 **중괄호를 세어서** 한다. 그러려면 주석·문자열이 먼저 지워져야 해서
  (`"{"` 한 글자, `"http://…"` 안의 `//`, `"${f { it }}"` 템플릿이 전부 깊이를 어긋낸다)
  중첩 블록 주석·원시 문자열·문자열 템플릿 안 코드까지 다루는 렉서를 넣었다.
- `companion object` 는 컴파일러가 넣는 `Companion` 칸을 그대로 반영한다
  (`Named$Factory$Made` 처럼 이름 붙은 것도).
- **읽어 내지 못하면 끊는다** — `package` 없음, 짝 안 맞는 중괄호, 안 닫힌 문자열은 예외다.
  조용히 넘기면 그 파일의 선언이 전부 검사 밖으로 나간다.

### 6.3 고치다 드러난 **부수 결함 1건** — 중첩 3건이 최상위로 잘못 나왔다

바이너리 이름을 붙이자마자 셋이 빨개졌다. 원인은 파서가 **주 생성자 파라미터를 멤버 선언으로
셌기** 때문이다 — 파라미터가 여러 줄로 펴지면 `val` 이 줄 머리에 오고, 그것을 멤버로 세면
본문을 기다리던 바깥 타입이 파라미터 목록에서 지워진다. 처방은 「괄호 안에서는 선언 머리를
보지 않는다」. 실측으로 드러난 세 건:

| 선언 | 잘못 낸 이름 | 고친 뒤 |
|---|---|---|
| `EasyDocProperties.CryptoProperties` | `kr.easydoc.api.config.CryptoProperties` | `…EasyDocProperties$CryptoProperties` |
| `Argon2Phc.Costs` | `kr.easydoc.infrastructure.auth.Costs` | `…Argon2Phc$Costs` |
| `Argon2Phc.Header` | `kr.easydoc.infrastructure.auth.Header` | `…Argon2Phc$Header` |

단순 이름 대조에서는 이 셋이 **전부 조용히 통과**했다. 키를 좁히지 않았다면 드러나지 않았을
결함이라, 게이트를 좁힌 값어치가 이 셋에 그대로 나온다.

### 6.4 지적 ③ — 파서의 다른 공허 경로를 **실측**했다

「잡는 것/못 잡는 것」을 KDoc 산문으로만 두면 파서가 바뀌어도 산문은 그대로 남는다. 그래서
합성 소스로 재는 `SourceScanFormsProbe`(api 테스트, 4건)를 새로 넣고 KDoc 은 그 결과를 적는다.

**잡는다** — `internal`/`private` 등 수식어 · 앞 줄 애너테이션(인자 있어도) · **같은 줄**
애너테이션(`@JvmInline value class`, `@Suppress("x") data class`) · `data object` ·
클래스/`sealed interface`/`object`/`enum class`/`companion object` 안의 중첩 ·
주 생성자를 `@JsonCreator constructor(…)` 로 분리한 형태(`AuthDtos.kt`) ·
주 생성자 파라미터가 여러 줄인 형태(§6.3) · 주석·문자열 **안**의 `data class` 는 세지 않는다(과잉 탐지 0).

**못 잡는다** — ⑴ 애너테이션 인자가 여러 줄에 걸친 뒤 같은 줄에 오는 선언, ⑵ 줄 머리가 아닌
선언(`val x = 1; data class Y`), ⑶ use-site target 이 붙은 같은 줄 애너테이션
(`@field:Suppress("x") data class Y`), ⑷ 함수 본문 안의 **지역** `data class`(이름은 뽑지만
사슬을 함수 몸통까지 따라가지 못한다).
⑴~⑶ 은 미탐지라 조용하고, ⑷ 는 틀린 이름을 내 시끄럽게 깨진다. 넷 다 오늘 main 소스 **0건**.
넓히지 않은 이유: 넷 다 이 저장소의 선언 형태가 아니고, 파서를 늘리는 비용이 선언 형태를
바꾸는 비용보다 크다. **미탐지 방향이라 과잉 차단을 만들지도 않는다.**

### 6.5 음성 대조 (일회용 worktree 3개, `git worktree add --detach` / `cp` 미사용)

`worker/src/main/kotlin/kr/easydoc/worker/CollisionProbe.kt` 에
`data class HealthResponse(val status: String)` 를 넣었다 — `api` 의
`kr.easydoc.api.health.HealthResponse` 와 **단순 이름이 같다**.

| # | 대조 | 결과 |
|---|---|---|
| ⑴ | 옛 판(`44eec3f`) + 충돌 DTO | **exit 0 (초록)** — 미적재인데 통과. 지적이 실재한다 |
| ⑴′ | 새 판 + 충돌 DTO | **exit 1 (빨강)** — `data class kr.easydoc.worker.HealthResponse` 로 지목 |
| ⑵ | 새 판, 충돌 DTO 없음 | **선언 44 · 미매치 0** — 기존 전건 매치, 과잉 탐지 0 (`grep` 실측 44 와 일치) |
| ⑶ | 새 판 + 충돌 DTO + `worker` 산출물을 테스트 런타임에 얹음 | **exit 0 (초록)** |
| ⑶′ | 다시 `worker` 제외 | **exit 1 (빨강)** — 빨강이 클래스패스 소속을 따라간다 |

⑶ 은 「빨강이 우연이 아니라 클래스패스 미포함 때문」임을 가른다. ⑵ 의 44 건에는 §6.3 의 중첩
셋이 올바른 바이너리 이름으로 들어 있다.

### 6.6 검사 표

**주의** — 이 배치를 재는 동안 같은 작업 트리에서 다른 레인이 AEAD 저장 암호화
(`core/crypto/`·`infrastructure/crypto/`·`V3__…sql`)를 진행 중이었고, 그 미완성 코드가
전체 빌드를 빨갛게 만든다(`StoredContent.kt` ktlint 5건, `AesGcmContentCipher.kt` detekt
`ReturnCount`, Flyway V2/V3 관련 테스트 실패). **그것은 이 배치의 회귀가 아니다.** 그래서
검사는 `HEAD(7fb47ee)` + **이 배치의 3파일만** 얹은 일회용 worktree에서 쟀다.

| 검사 | 명령 | 결과 |
|---|---|---|
| api 단독 | `./gradlew :api:test --rerun-tasks` | **exit 0** |
| Kotlin 전 구간 | `./gradlew ktlintCheck detekt build --continue` | **exit 0** (81 tasks) |
| 스캐너(CI 명령) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** |
| 무접촉 | 커밋 대상 | `backend-kotlin/api/src/test/**` 3파일뿐 — `00_progress.md`·`contracts/`·`.claude/`·`app/` **0건** |

Python 게이트(`ruff`/`mypy`/`pytest`)는 **이번에 실행하지 않았다** — 이 배치는 Python 0줄
변경이고, 같은 트리에서 다른 레인이 `contracts/`·`.claude/` 를 동시에 고치고 있어 그 결과가
이 배치의 것이 아니게 된다. §4 의 기록이 마지막 실측이다. **미실행이지 통과가 아니다.**

### 6.7 남은 것

- §5-2(`worker` 모듈 도달)는 **그대로 남아 있다.** 이번 수정은 「충돌로 못 잡던 것을 잡게」
  만들었을 뿐, worker 를 검사 범위에 넣지 않았다. Phase 5 가 worker 에 DTO 를 만들면
  이 테스트가 빨개지고, 그때 그 모듈에서도 돌게 배선해야 한다(§6.5 ⑶ 이 그 배선이
  실제로 초록을 만든다는 것까지 재 두었다).
- 파서의 미탐지 ⑴~⑶(§6.4)은 넓히지 않았다. main 소스에 그 형태가 들어오면
  `SourceScanFormsProbe` 를 함께 고친다.

### 6.8 후속 지적 — FQCN 대조에 남아 있던 **완전 동일 이름** 갈래

§6.2 는 **단순 이름** 충돌을 막았을 뿐, 두 모듈이 **같은 `package` + 같은 이름**을 선언하는
경우는 그대로 통과했다. 적재 집합은 이름당 한 건이므로, 선언 두 건이 **둘 다 그 한 건에
매치**돼 실제로는 하나만 적재됐는데 `missing` 이 비었다.

처방은 대조 전에 선언 쪽을 **다중집합**으로 세는 것이다 — 같은 바이너리 이름이 2건 이상이면
그 자체를 실패로 본다. 우회가 아니라 정면이다: JVM 에서 같은 FQCN 이 둘이면 클래스패스
순서가 승자를 정하는 **모호성 결함**이고, 진 쪽의 `toString()` 은 어떤 게이트도 보지 못한다.
중복이 0 임을 확인한 뒤라야 「선언 건수 ↔ 적재 건수」가 1:1 로 대응한다.

**음성 대조** (일회용 worktree 2개, `cp` 미사용). `worker` 모듈에 `package kr.easydoc.api.health`
+ `data class HealthResponse(val status: String)` 주입 — `api` 의 것과 **package·이름이 모두 같다**:

| # | 대조 | 결과 |
|---|---|---|
| ⑴ | 옛 판(`eb075f1`) + 동일 FQCN 충돌 | **exit 0 (초록)** — 지적이 실재한다 |
| ⑴′ | 새 판 + 동일 FQCN 충돌 | **exit 1 (빨강)** — `kr.easydoc.api.health.HealthResponse` 와 **선언 두 자리**(`HealthController.kt` / `FqcnCollisionProbe.kt`)를 지목 |
| ⑵ | 새 판, 충돌 없음 | **선언 44 · 고유 FQCN 44 · 중복 0 · 미매치 0** — §6.5 ⑵ 에서 무변동, 과잉 탐지 0 |

**검사** (HEAD `eb075f1` + 이 배치 파일만 얹은 일회용 worktree — `crypto` 레인 미완성 코드 분리):

| 검사 | 명령 | 결과 |
|---|---|---|
| api 단독 | `./gradlew :api:test --rerun-tasks` | **exit 0** |
| Kotlin 전 구간 | `./gradlew ktlintCheck detekt build --continue` | **exit 0** |
| 스캐너(CI 명령) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** |

Python 게이트는 §6.6 과 같은 사유로 **미실행**(Python 0줄 변경, 동시 레인이 `contracts/`·`.claude/`
를 고치는 중). 미실행이지 통과가 아니다.

package kr.easydoc.api

import kr.easydoc.api.support.GeneratedToStringProbes
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.core.privacy.UserContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * **사용자 콘텐츠·개인정보를 든 타입은 `toString()` 이 그 값을 내지 않는다** —
 * 열거가 아니라 **종류**를 잡는다 (게이트 23: Claude F-2 · codex C-4 · privacy-gate 3a /
 * 게이트 24: privacy-gate A-3′ · Claude R-5 · codex X24-3).
 *
 * ## 왜 이 테스트가 필요한가
 *
 * 종전 방어는 `WorkspaceDtoLeakTest`·`WorkspaceNameLeakTest` 두 개였고 **클래스 넷을 손으로
 * 나열**했다. 그래서 같은 배치가 작업 공간 이름을 막는 동안 `User.email`·`UserResponse.email`·
 * `SentenceIssue.sentence`·`RepairPrompt`·`Outcome.Body`·`Adoption` 여섯이 그대로 남았다 —
 * **비대칭이 사라진 것이 아니라 옮겨간** 상태였다. 열거식 목록은 이 저장소가 이미 여러 번
 * 놓친 형태이고(`AuthenticatedEndpoints` KDoc 이 같은 사실을 적는다), `CLAUDE.md` 규칙 4 는
 * 빈자리를 **종류로** 댈 수 있으면 그 종류만큼 넓히되 **탐지형**으로 가라고 한다.
 *
 * 종류는 둘이다 — **컴파일러가 `toString()` 을 만들어 주는 두 선언**, 곧 `data class` 와
 * `@JvmInline value class`. 그 밖의 클래스는 `Any.toString()`(식별 해시)을 물려받거나 손으로
 * 쓴 재정의를 갖는다. Phase 4 가 `DocumentResponse(title=…)`·`ExportRequest(filename=…)` 를
 * 만들면 기본 `toString()` 이 그대로 돌아오는데, 그 커밋에서 이 테스트가 빨개진다.
 *
 * ## 판정 방식 — 「재정의가 있는가」가 아니라 **「값이 나오는가」**
 *
 * `data class` 는 컴파일러가 언제나 `toString()` 을 **선언**하므로 「재정의 유무」는 반사로
 * 구분되지 않는다. 그래서 표식 문자열을 민감 자리에 심어 실제로 인스턴스를 만들고,
 * `toString()` 산출에 그 표식이 없음을 단언한다. 재정의를 형식만 해 두고 값을 그대로 찍는
 * 구현은 이 판정을 통과하지 못한다.
 *
 * ## 게이트 24 에서 고친 것 — 빈자리 셋
 *
 * **⑴ `componentN` 맹글링으로 value-class-first `data class` 가 통째로 탈락**
 * (privacy-gate A-3′, 실례 `MaskingResult`). → 판정 근거를 JVM 시그니처에서 **주 생성자
 * 파라미터**로 옮기고, 후보 선정의 `return null` 두 갈래를 없앴다.
 *
 * **⑵ 「`String` 이 아닌 파라미터는 대상 아님」의 사유(래퍼가 스스로 가린다)가 강제되지 않음**
 * (Claude R-5 ⓐⓑ). → 값을 감싸는 타입을 **직접 검사하고**, 래퍼 **안쪽까지 표식을 심어**
 * 그것을 든 DTO 도 함께 잡는다.
 *
 * **⑶ 제외 사유(테스트 산출물·비-DTO)가 선언만 있고 검사받지 않음** (Claude R-5 ⓓ). →
 * 소스에 선언된 `data`/`value class` 를 전부 세어 적재 집합과 대조한다.
 *
 * ## 선언한 범위와 실제 도달
 *
 * - **대상**: 테스트 런타임 클래스패스의 `kr.easydoc.**` **main** 클래스 전부. 오늘 `api` 는
 *   다섯 모듈 중 넷을 싣고 `worker` 를 싣지 않는다 — 그 빈자리는 소스 대조가 잡는다
 *   (`worker` 에 첫 `data class` 가 생기는 커밋에서 빨개진다).
 * - **민감 판정**: 텍스트를 담을 수 있는 파라미터 중 ⑴ 이름이 [SENSITIVE_NAME_TOKENS] 를
 *   품거나 ⑵ 클래스에 [UserContent] 가 붙은 것. 「텍스트를 담을 수 있는가」는 타입을 따라
 *   들어가서 정한다 — `String`, 그것을 감싼 value class, 그 둘의 컬렉션, 그런 것을 든 제품 타입.
 * - **자격증명 토큰(`token`·`secret`·`key`)은 이름 규약에 넣지 않았다.** 그 범주는
 *   `Secret`·`PasswordHash` 래퍼 타입과 스캐너의 `SECRET-LITERAL` 규칙이 맡는 자리이고,
 *   여기 넣으면 `tokenType`("Bearer") 같은 무해한 필드까지 끌려와 범위가 근거를 넘는다.
 *   래퍼 쪽 절반은 이제 [`값을 감싸는 타입이 값을 찍지 않는다`] 가 실제로 검사한다.
 * - **막지 못하는 것**:
 *   ⑴ `data class`·value class 가 아닌 타입의 **손으로 쓴** `toString()`(→ `StoredUser`·
 *      `LlmPrompt` 처럼 개별 KDoc 규율, 그리고 파라미터 하나짜리 래퍼는 여기서 검사한다),
 *   ⑵ **이름 규약 밖의 `String` 파라미터**(예: `data class ExportEnvelope(payload: String)`) —
 *      [UserContent] 가 메우라고 있는 자리다. codex X24-3 이 「모든 `String` 을 fail-closed
 *      분류하라」로 넓히기를 권했고 채택하지 않았다. 사유와 실측은
 *      `docs/migration/_workspace/03_kotlin-implementer_phase4-preconditions.md`,
 *   ⑶ `.value` 를 직접 꺼내 로거에 넘기는 줄(→ 스캐너 `LOG-BODY`),
 *   ⑷ 이 파일 자체의 삭제(→ CI 가 테스트 태스크를 돌린다).
 */
class SensitiveToStringReachTest {
    @Test
    @DisplayName("탐지 범위가 비어 있지 않다 — 0개를 훑고 통과하는 상태를 막는다")
    fun `탐지 범위가 실재한다`() {
        val classes = ProductClasses.onTestRuntimeClasspath()

        assertThat(classes)
            .withFailMessage {
                "main 클래스를 ${classes.size} 개밖에 찾지 못했다(기대 $MIN_PRODUCTION_CLASSES 이상). " +
                    "클래스패스 필터가 제품 산출물을 걸러 버렸을 수 있다 — " +
                    "그러면 이 게이트는 통과가 아니라 **미검사**다."
            }.hasSizeGreaterThanOrEqualTo(MIN_PRODUCTION_CLASSES)

        val probes = probes(classes)
        assertThat(probes.dataClassProbes.map { it.type.simpleName })
            .withFailMessage {
                "민감 판정 기준이 아래 타입에 닿지 않는다: " +
                    "${KNOWN_SENSITIVE_TYPES - probes.dataClassProbes.map { it.type.simpleName }.toSet()}\n" +
                    "  이 목록은 **바닥**이지 천장이 아니다 — 새 타입이 늘 때 여기 적을 필요는 없고, " +
                    "기존 타입이 기준 밖으로 빠지는 것만 막는다(필드 이름을 바꿔 규약을 피하는 형태).\n" +
                    "  타입을 정말 지웠다면 이 목록에서도 지워라."
            }.containsAll(KNOWN_SENSITIVE_TYPES)

        assertThat(probes.wrapperProbes.map { it.type.simpleName })
            .withFailMessage {
                "값을 감싸는 타입 판정이 아래에 닿지 않는다: " +
                    "${KNOWN_TEXT_WRAPPERS - probes.wrapperProbes.map { it.type.simpleName }.toSet()}\n" +
                    "  `MaskingResult` 가 여기 없으면 A-3′(value-class-first 탈락)가 되살아난 것이다."
            }.containsAll(KNOWN_TEXT_WRAPPERS)
    }

    @Test
    @DisplayName("민감 필드를 든 data class 의 toString 이 그 값을 내지 않는다")
    fun `민감 data class 가 값을 찍지 않는다`() {
        val leaking = probes().dataClassProbes.filter { it.leaks() }

        assertThat(leaking.map { it.type.qualifiedName })
            .withFailMessage {
                "아래 data class 의 toString() 이 사용자 콘텐츠·개인정보를 그대로 찍는다:\n" +
                    leaking.joinToString("\n") { "  - $it" } +
                    "\n  `override fun toString()` 으로 값 대신 길이·표식을 내라 " +
                    "(`Workspace`·`User`·`PlaceholderRestoration` 가 예시다).\n" +
                    "  **직렬화는 가리지 않는다** — 계약이 required 로 둔 필드는 JSON 에 그대로 나가야 한다."
            }.isEmpty()
    }

    @Test
    @DisplayName("값을 감싸는 타입의 toString 이 감싼 값을 내지 않는다 — 제외 사유를 단언으로")
    fun `값을 감싸는 타입이 값을 찍지 않는다`() {
        val leaking = probes().wrapperProbes.filter { it.leaks() }

        assertThat(leaking.map { it.type.qualifiedName })
            .withFailMessage {
                "아래 타입이 감싼 값을 toString() 으로 그대로 내보낸다:\n" +
                    leaking.joinToString("\n") { "  - $it" } +
                    "\n  이 저장소는 본문·비밀을 래퍼 타입으로 감싸고(`MaskedText`·`ModelDraft`·" +
                    "`ReviewedBody`·`Secret`), **감싼 쪽이 가린다**는 전제로 그 필드를 든 DTO 를 안전하다고 본다.\n" +
                    "  `@JvmInline value class` 는 컴파일러가 `toString()` 을 만들어 주므로 재정의가 없으면 " +
                    "값이 그대로 나온다 — 길이만 남기는 재정의를 붙여라(`Masking.kt` 「value class 와 toString」 절)."
            }.isEmpty()
    }

    @Test
    @DisplayName("`toString()` 을 손으로 쓴 **일반 class** 도 값을 찍지 않는다 (R-10)")
    fun `일반 class 의 손으로 쓴 toString 이 값을 찍지 않는다`() {
        // ## 왜 이 갈래가 따로 있는가 (게이트 25 R-10)
        //
        // 위 두 케이스는 **컴파일러가 `toString()` 을 만들어 주는 선언**만 본다. 그 경계 밖의
        // 일반 class 는 재정의가 없으면 `Any.toString()`(클래스명@해시)이라 **샐 수 없고**,
        // 재정의가 있으면 그 안에서 무엇을 찍는지 아무도 안 보고 있었다. `EncryptedContent` 가
        // 첫 사례다. 그래서 후보를 「재정의를 **선언한** 일반 class」로 좁힌다 — 좁힘의 근거가
        // 사유가 아니라 구조라 면제 조항이 아니다.
        val probes = probes()

        // 분모가 0 이면 이 갈래는 아무 데도 도달하지 않는다. 오늘 실제로 훑는 수를 함께 남긴다.
        assertThat(probes.generalClassesWithCustomToString)
            .withFailMessage {
                "`toString()` 을 손으로 쓴 일반 class 를 하나도 찾지 못했다 — 이 케이스가 0건을 훑고 통과한다.\n" +
                    "  적재 필터나 후보 선정이 조용히 좁아졌는지 보라."
            }.isNotEmpty()

        assertThat(probes.undecidableGeneralClasses)
            .withFailMessage {
                "아래 일반 class 는 후보인데 표본을 만들지 못했다 — **판정 불가는 통과가 아니다**:\n" +
                    probes.undecidableGeneralClasses.joinToString("\n") { "  - $it" } +
                    "\n  `GeneratedToStringProbes.slotFor` 에 갈래를 더하거나, 그 타입의 생성자를 " +
                    "표본으로 만들 수 있는 형태로 바꿔라."
            }.isEmpty()

        val leaking = probes.generalClassProbes.filter { it.leaks() }
        assertThat(leaking.map { it.type.qualifiedName })
            .withFailMessage {
                "아래 일반 class 의 손으로 쓴 toString() 이 사용자 콘텐츠·개인정보를 그대로 찍는다:\n" +
                    leaking.joinToString("\n") { "  - $it" } +
                    "\n  값 대신 길이·표식을 내라(`Secret`·`EncryptedContent` 가 예시다)."
            }.isEmpty()

        // **오늘 민감 후보가 몇 건인지**를 기록으로 남긴다. 0 이어도 위 단언은 살아 있고,
        // 민감 필드를 든 일반 class 가 하나 생기는 순간 여기에 들어와 검사받는다.
        println(
            "R-10 일반 class 축 — 재정의 선언 ${probes.generalClassesWithCustomToString.size}건 중 " +
                "민감 후보 ${probes.generalClassProbes.size}건: " +
                probes.generalClassProbes.map { it.type.simpleName },
        )
    }

    @Test
    @DisplayName("소스에 선언된 data·value class 가 전부 탐지 범위에 든다 — 제외가 검사받는다")
    fun `소스에 선언된 타입이 전부 탐지 범위에 든다`() {
        val declared = ProductClasses.declaredInMainSources()
        // **바이너리 이름**으로 맞춘다. 단순 이름으로 맞추면 다른 모듈의 동명 타입이 대신
        // 맞아 미적재가 통과한다 — 게이트 25 가 고친 빈자리이고, 실측은 `worker` 에 `api` 와
        // 같은 이름의 DTO 를 넣어 확인했다(옛 판 초록 / 새 판 빨강).
        val loaded = ProductClasses.onTestRuntimeClasspath().map { it.java.name }.toSet()

        assertThat(declared.size)
            .withFailMessage {
                "`*/src/main/kotlin` 의 `data`/`value class` 선언 수가 기록과 다르다 " +
                    "(기대 $EXPECTED_SOURCE_DECLARATIONS / 실제 ${declared.size}).\n" +
                    "  **늘었다면** 새 타입을 더한 것이다 — 이 숫자를 함께 올려라. 그 한 줄이 " +
                    "「이번에 무엇이 검사 범위에 들어왔는가」를 리뷰에 드러낸다.\n" +
                    "  **줄었다면** 파서가 선언을 놓치기 시작했거나 모듈이 스캔에서 빠진 것이다. " +
                    "숫자를 내리기 전에 `SourceScanFormsProbe` 부터 보라.\n" +
                    "  현재 선언 목록:\n" +
                    declared.sortedBy { it.binaryName }.joinToString("\n") { "    ${it.binaryName}" }
            }.isEqualTo(EXPECTED_SOURCE_DECLARATIONS)

        // ── 선언 쪽을 **다중집합**으로 센다 (게이트 25 후속) ────────────────────────
        //
        // 적재 집합은 이름 하나당 한 건뿐이다. 그래서 **두 모듈이 같은 바이너리 이름**
        // (같은 `package` + 같은 이름)을 선언하면 아래 `missing` 대조에서 **둘 다** 그 한
        // 건에 매치돼, 실제로는 하나만 적재됐는데 통과한다. 키를 FQCN 으로 좁힌 것만으로는
        // 이 갈래가 남아 있었다 — 단순 이름 충돌을 막았을 뿐 **완전 동일 FQCN 충돌**은 그대로였다.
        //
        // 그래서 중복 선언 자체를 실패로 본다. 우회가 아니라 정면이다: JVM 에서 같은 FQCN 이
        // 둘이면 클래스패스 순서가 어느 쪽을 이기는지 정하는 **모호성 결함**이고, 이긴 쪽만
        // 적재되므로 진 쪽의 `toString()` 은 어떤 게이트도 보지 못한다.
        val duplicated = declared.groupBy { it.binaryName }.filterValues { it.size > 1 }

        assertThat(duplicated.keys)
            .withFailMessage {
                "같은 바이너리 이름이 두 곳 이상에서 선언됐다:\n" +
                    duplicated.entries.joinToString("\n") { (name, sites) ->
                        "  - $name\n" + sites.joinToString("\n") { "      ${it.path}" }
                    } +
                    "\n  JVM 은 이 중 **클래스패스에서 이긴 하나만** 적재한다. 진 쪽은 이 게이트가 볼 수 없고,\n" +
                    "  아래 「클래스패스에 없다」 대조도 이긴 쪽에 함께 매치돼 **조용히 통과한다**.\n" +
                    "  이름을 갈라라 — 모듈이 다르다고 같은 `package` 를 쓰는 것이 원인인 경우가 대부분이다."
            }.isEmpty()

        // 중복이 없음을 확인한 뒤라, 여기서는 선언 **건수**와 적재 **건수**가 1:1 로 대응한다.
        val missing = declared.filterNot { it.binaryName in loaded }

        assertThat(missing.map { "${it.kind} class ${it.binaryName} (${it.path})" })
            .withFailMessage {
                "아래 타입이 소스에는 선언돼 있는데 이 탐지기의 클래스패스에는 없다:\n" +
                    missing.joinToString("\n") { "  - ${it.kind} class ${it.sourceName} — ${it.path}" } +
                    "\n  탐지 범위가 **선언보다 좁다.** 원인은 셋 중 하나다 —\n" +
                    "  ⑴ 그 모듈이 `api` 테스트 런타임에 없다(오늘 `worker` 가 그렇다). 그러면 이 테스트를 " +
                    "그 모듈에서도 돌게 만들어라. 제외 목록에 적어 넘기지 않는다.\n" +
                    "  ⑵ 클래스패스 필터가 제품 산출물을 걸렀다. `ProductClasses` 의 표식을 확인하라.\n" +
                    "  ⑶ 소스 파서가 중첩 사슬을 잘못 이었다(함수 본문 안의 지역 `data class` 가 그렇다). " +
                    "`ProductClasses` KDoc 「못 잡는 것」 ⑷ 를 보라."
            }.isEmpty()
    }

    private fun probes(classes: List<KClass<*>> = ProductClasses.onTestRuntimeClasspath()) =
        GeneratedToStringProbes(classes, SENSITIVE_NAME_TOKENS)

    private companion object {
        /**
         * 민감 필드 이름 토큰(소문자 부분 문자열).
         *
         * 근거는 **계약이 사용자 콘텐츠·개인정보로 분류한 것**이다 —
         * `x-private-response-headers.applies_to`(작업 공간 이름·문서 제목·파일명),
         * `UserResponse.email`, 그리고 `Masking.kt` 의 「본문」 규율. `title`·`filename`·
         * `body`·`content` 는 **오늘 대상이 0건**이고 Phase 4 의 문서 DTO 를 겨냥해 미리 둔다 —
         * 이 게이트가 실제로 쓰이는 첫 자리가 거기다.
         */
        val SENSITIVE_NAME_TOKENS =
            listOf(
                "email",
                "password",
                "name",
                "text",
                "body",
                "content",
                "prompt",
                "sentence",
                "word",
                "title",
                "filename",
                "phone",
                "address",
            )

        /**
         * 클래스패스 필터가 제품 산출물을 통째로 걸러 버렸는지 보는 하한.
         *
         * 정확한 수를 못박지 않는 이유: 클래스가 느는 것은 정상이고 그 수를 여기 적으면
         * 무관한 커밋마다 이 파일을 고치게 된다. 막으려는 것은 **0 에 가까운 상태**다.
         */
        const val MIN_PRODUCTION_CLASSES = 60

        /**
         * 소스 쪽은 **하한이 아니라 정확 일치**다 (게이트 25 U-1).
         *
         * 종전 값은 하한 20 인데 실측이 44~46 이었다 — **24건까지 조용히 잃어도** 울리지 않는다는
         * 뜻이고, 그 여유가 정확히 파서 미탐(`fun interface`·비ASCII 이름·중첩 모듈)이
         * 겹쳐 쌓일 수 있는 크기였다. 하한은 「0 에 가까운 상태」만 막고, 이 게이트가 실제로
         * 겪은 실패는 **0 이 아니라 조금 줄어드는 것**이었다.
         *
         * 값 46 은 2026-08-19 게이트 25 조치 시점의 실측이다. 리뷰 산출물이 적은 44 는 crypto
         * 커밋(`9c7aa03`) 이전 시점의 수라 오늘 값과 다르다 — 그 차이 자체가 이 상수를 하한이
         * 아니라 정확 일치로 두는 이유다(그때는 「44 이상」이라 아무도 눈치채지 못했다).
         *
         * 정확 일치의 비용은 새 타입을 더할 때 이 숫자를 함께 고치는 것이다. 그 한 줄이
         * 리뷰에 「이번에 무엇이 검사 범위에 들어왔는가」를 드러내므로 비용이 아니라 값이다.
         *
         * 46 → 48 (2026-08-20, 문서 저장 경로 커밋): `core.document.MaskedItemView` 와
         * `application.document.AcceptedUpload` 둘이다. 나머지 문서 도메인 타입
         * (`Document`·`Conversion`·`DocumentListing`·`DocumentDraft`·`ConversionCiphertexts`·
         * `ConversionEnvelope`·`DocumentStorage`)은 **일반 class** 라 이 수에 들어오지 않고,
         * 그중 사용자 콘텐츠를 든 것은 손으로 쓴 `toString()` 을 갖는다 — 그쪽은 위
         * 「R-10 일반 class 축」이 잰다.
         *
         * 48 → 50 (2026-08-20, `POST /documents` 커밋): `api.document.DocumentTextRequest` 와
         * `api.document.DocumentCreatedResponse` 둘이다. 앞엣것이 이 게이트가 **처음으로
         * 실제 문서 본문을 든 요청 DTO** 를 잡는 자리다(`text`·`title` 두 토큰이 함께 걸린다) —
         * `SENSITIVE_NAME_TOKENS` 의 `text`·`title` 이 "Phase 4 의 문서 DTO 를 겨냥해 미리
         * 둔다" 고 적힌 채 대상 0건이던 상태가 여기서 닫힌다.
         */
        const val EXPECTED_SOURCE_DECLARATIONS = 50

        /**
         * 민감 판정이 반드시 닿아야 하는 타입 — **바닥**이다.
         *
         * 새 타입을 여기 적을 필요는 없다(`containsAll` 이지 정확 일치가 아니다). 이 목록이
         * 막는 것은 **기준이 조용히 좁아지는 방향**이다 — 필드 이름을 바꾸거나 토큰 목록을
         * 줄여 기존 타입이 검사 밖으로 나가면 여기서 빨개진다.
         *
         * `MaskingResult` 는 게이트 24 에서 들어왔다. 종전 판정에서는 1번 파라미터가
         * value class 라는 이유만으로 **통째로 빠져 있었다**(privacy-gate A-3′).
         */
        val KNOWN_SENSITIVE_TYPES =
            listOf(
                // core
                "User",
                "Workspace",
                "SentenceIssue",
                "RepairPrompt",
                "LlmCompletion",
                "PlaceholderRestoration",
                "MaskingResult",
                // application
                "Body",
                "Adoption",
                // api
                "SignupRequest",
                "LoginRequest",
                "UserResponse",
                "WorkspaceNameRequest",
                "WorkspaceResponse",
                "WorkspaceListItemResponse",
            )

        /**
         * 값을 감싸는 타입 판정이 반드시 닿아야 하는 것 — 역시 **바닥**이다.
         *
         * 앞의 셋은 `Masking.kt` 가 「value class 와 toString」 절에 열거한 본문 래퍼이고,
         * `Secret` 은 `data class` 필드(`MaskedItem.original`·`AnthropicSettings.apiKey`)로
         * **닿아서** 들어온다 — 열거가 아니라 도달이라는 뜻이다.
         */
        val KNOWN_TEXT_WRAPPERS =
            listOf(
                "MaskedText",
                "ModelDraft",
                "ReviewedBody",
                "Secret",
            )
    }
}

package kr.easydoc.api

import kr.easydoc.api.support.GeneratedToStringProbes
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.core.privacy.UserContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * 사용자 콘텐츠·개인정보를 든 타입은 `toString()` 이 그 값을 내지 않는다 —
 * 열거가 아니라 종류를 잡는다 (게이트 23: Claude F-2 · codex C-4 · privacy-gate 3a /
 * 게이트 24: privacy-gate A-3′ · Claude R-5 · codex X24-3).
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
        val probes = probes()

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
        /** 민감 필드 이름 토큰(소문자 부분 문자열). */
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

        /** 클래스패스 필터가 제품 산출물을 통째로 걸러 버렸는지 보는 하한. */
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
         *
         * 50 → 52 (2026-08-21, `GET /documents` 커밋): `api.document.DocumentListItemResponse` 와
         * `api.document.DocumentListResponse` 둘이다. 앞엣것이 **응답 쪽에서 처음으로 제목을
         * 든 data class** 이고(`title` 토큰), 목록은 한 번에 스무 건이 오므로 컴파일러가 만든
         * `toString()` 을 그대로 두면 한 줄이 제목 스무 개를 로그에 남긴다. 뒤엣것은 민감
         * 토큰이 없지만 이 수는 **선언 전수**라 함께 오른다.
         *
         * 52 → 53 (2026-08-21, `/health` 진단 커밋 — 게이트 28 P-8): `application.health.HealthReport`
         * 하나다(`api.health.HealthResponse` 는 이미 세어져 있었다 — 필드가 늘었을 뿐이다).
         * 민감 토큰은 없지만 **이 커밋이 이 탐지기의 갈래를 하나 늘렸다**: `checks` 가
         * 저장소 최초의 `Map` 필드라 `GeneratedToStringProbes.slotFor` 가 「모르는 타입」으로
         * 끊었고, 그래서 맵 갈래(`mapSlot` — 키와 값 **양쪽**에 표본을 심는다)를 더했다.
         * 그 끊김이 설계대로 동작한 것이다: 새 타입이 들어오면 조용히 검사 밖에 남는 대신
         * 게이트가 빨개진다.
         */
        const val EXPECTED_SOURCE_DECLARATIONS = 53

        /** 민감 판정이 반드시 닿아야 하는 타입 — 바닥이다. */
        val KNOWN_SENSITIVE_TYPES =
            listOf(
                "User",
                "Workspace",
                "SentenceIssue",
                "RepairPrompt",
                "LlmCompletion",
                "PlaceholderRestoration",
                "MaskingResult",
                "Body",
                "Adoption",
                "SignupRequest",
                "LoginRequest",
                "UserResponse",
                "WorkspaceNameRequest",
                "WorkspaceResponse",
                "WorkspaceListItemResponse",
            )

        /** 값을 감싸는 타입 판정이 반드시 닿아야 하는 것 — 역시 바닥이다. */
        val KNOWN_TEXT_WRAPPERS =
            listOf(
                "MaskedText",
                "ModelDraft",
                "ReviewedBody",
                "Secret",
            )
    }
}

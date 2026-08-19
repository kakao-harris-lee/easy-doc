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
    @DisplayName("소스에 선언된 data·value class 가 전부 탐지 범위에 든다 — 제외가 검사받는다")
    fun `소스에 선언된 타입이 전부 탐지 범위에 든다`() {
        val declared = ProductClasses.declaredInMainSources()
        val loaded = ProductClasses.onTestRuntimeClasspath().mapNotNull { it.simpleName }.toSet()
        val missing = declared.filterNot { it.simpleName in loaded }

        assertThat(declared)
            .withFailMessage { "`*/src/main/kotlin` 에서 선언을 하나도 찾지 못했다 — 소스 대조가 0건을 훑고 통과한다" }
            .hasSizeGreaterThanOrEqualTo(MIN_SOURCE_DECLARATIONS)

        assertThat(missing.map { "${it.kind} class ${it.simpleName} (${it.path})" })
            .withFailMessage {
                "아래 타입이 소스에는 선언돼 있는데 이 탐지기의 클래스패스에는 없다:\n" +
                    missing.joinToString("\n") { "  - ${it.kind} class ${it.simpleName} — ${it.path}" } +
                    "\n  탐지 범위가 **선언보다 좁다.** 원인은 둘 중 하나다 —\n" +
                    "  ⑴ 그 모듈이 `api` 테스트 런타임에 없다(오늘 `worker` 가 그렇다). 그러면 이 테스트를 " +
                    "그 모듈에서도 돌게 만들어라. 제외 목록에 적어 넘기지 않는다.\n" +
                    "  ⑵ 클래스패스 필터가 제품 산출물을 걸렀다. `ProductClasses` 의 표식을 확인하라."
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

        /** 같은 이유의 소스 쪽 하한. 실측 44 건(2026-08-19) 기준으로 절반쯤에 둔다. */
        const val MIN_SOURCE_DECLARATIONS = 20

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

package kr.easydoc.api

import kr.easydoc.core.privacy.UserContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.UUID

/**
 * **사용자 콘텐츠·개인정보를 든 `data class` 는 `toString()` 이 그 값을 내지 않는다** —
 * 열거가 아니라 **종류**를 잡는다 (게이트 23: Claude F-2 · codex C-4 · privacy-gate 3a).
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
 * 종류는 명확하다 — **사용자가 적은 문자열(또는 그 파생물)을 필드로 가진 `data class`**.
 * Phase 4 가 `DocumentResponse(title=…)`·`ExportRequest(filename=…)` 를 만들면 기본
 * `toString()` 이 그대로 돌아오는데, 그 커밋에서 이 테스트가 빨개진다.
 *
 * ## 판정 방식 — 「재정의가 있는가」가 아니라 **「값이 나오는가」**
 *
 * `data class` 는 컴파일러가 언제나 `toString()` 을 **선언**하므로 「재정의 유무」는 반사로
 * 구분되지 않는다. 그래서 표식 문자열을 민감 필드에 심어 실제로 인스턴스를 만들고,
 * `toString()` 산출에 그 표식이 없음을 단언한다. 재정의를 형식만 해 두고 값을 그대로 찍는
 * 구현은 이 판정을 통과하지 못한다.
 *
 * ## 선언한 범위와 실제 도달
 *
 * - **대상**: 테스트 런타임 클래스패스의 `kr.easydoc.**` **main** 클래스 전부
 *   (`api` 가 다섯 모듈 중 넷을 런타임에 싣는 유일한 모듈이라 여기 둔다). 테스트·
 *   testFixtures 산출물은 뺀다 — 제품이 아니고, 컨테이너 비밀번호 같은 값이 섞여 있다.
 * - **민감 판정**: 생성자 파라미터가 `String` 이고 ⑴ 이름이 [SENSITIVE_NAME_TOKENS] 를
 *   품거나 ⑵ 클래스에 [UserContent] 가 붙은 경우.
 * - **`String` 이 아닌 파라미터는 대상이 아니다.** `Secret`·`MaskedText` 처럼 값을 감싸는
 *   타입이 자기 `toString()` 에서 이미 가리기 때문이고, 숫자·enum·UUID 는 콘텐츠를 담지
 *   못한다(`StyleCheckResult.totalSentences: Int` 가 이름 규약에 걸리는데 위험이 없는 자리다).
 * - **자격증명 토큰(`token`·`secret`·`key`)은 이름 규약에 넣지 않았다.** 그 범주는
 *   `Secret`·`PasswordHash` 래퍼 타입과 스캐너의 `SECRET-LITERAL` 규칙이 맡는 자리이고,
 *   여기 넣으면 `tokenType`("Bearer") 같은 무해한 필드까지 끌려와 범위가 근거를 넘는다.
 * - **막지 못하는 것**: `data class` 가 아닌 타입(→ `StoredUser` 처럼 개별 KDoc 규율),
 *   `.value` 를 직접 꺼내 로거에 넘기는 줄(→ 스캐너 `LOG-BODY`), 이 파일 자체의 삭제
 *   (→ CI 가 테스트 태스크를 돌린다).
 */
class SensitiveToStringReachTest {
    @Test
    @DisplayName("탐지 범위가 비어 있지 않다 — 0개를 훑고 통과하는 상태를 막는다")
    fun `탐지 범위가 실재한다`() {
        val classes = productionClasses()
        val sensitive = sensitiveDataClasses(classes)

        assertThat(classes)
            .withFailMessage {
                "main 클래스를 ${classes.size} 개밖에 찾지 못했다(기대 $MIN_PRODUCTION_CLASSES 이상). " +
                    "클래스패스 필터가 제품 산출물을 걸러 버렸을 수 있다 — " +
                    "그러면 이 게이트는 통과가 아니라 **미검사**다."
            }.hasSizeGreaterThanOrEqualTo(MIN_PRODUCTION_CLASSES)

        assertThat(sensitive.map { it.type.simpleName })
            .withFailMessage {
                "민감 판정 기준이 아래 타입에 닿지 않는다: ${KNOWN_SENSITIVE_TYPES - sensitive.map { it.type.simpleName }.toSet()}\n" +
                    "  이 목록은 **바닥**이지 천장이 아니다 — 새 타입이 늘 때 여기 적을 필요는 없고, " +
                    "기존 타입이 기준 밖으로 빠지는 것만 막는다(필드 이름을 바꿔 규약을 피하는 형태).\n" +
                    "  타입을 정말 지웠다면 이 목록에서도 지워라."
            }.containsAll(KNOWN_SENSITIVE_TYPES)
    }

    @Test
    @DisplayName("민감 필드를 든 data class 의 toString 이 그 값을 내지 않는다")
    fun `민감 data class 가 값을 찍지 않는다`() {
        val leaking =
            sensitiveDataClasses(productionClasses()).filter { candidate ->
                instantiate(candidate).toString().contains(SENTINEL)
            }

        assertThat(leaking.map { it.type.name })
            .withFailMessage {
                "아래 data class 의 toString() 이 사용자 콘텐츠·개인정보를 그대로 찍는다:\n" +
                    leaking.joinToString("\n") { "  - ${it.type.name} (민감 필드: ${it.sensitiveNames})" } +
                    "\n  `override fun toString()` 으로 값 대신 길이·표식을 내라 " +
                    "(`Workspace`·`User`·`PlaceholderRestoration` 가 예시다).\n" +
                    "  **직렬화는 가리지 않는다** — 계약이 required 로 둔 필드는 JSON 에 그대로 나가야 한다."
            }.isEmpty()
    }

    // ---------------------------------------------------------------- 탐지

    /** 민감 필드를 든 `data class` 하나와, 표식을 심을 파라미터 위치. */
    private data class Candidate(
        val type: Class<*>,
        val constructor: Constructor<*>,
        val fields: List<Field>,
        val sensitiveIndices: Set<Int>,
    ) {
        val sensitiveNames: List<String> get() = sensitiveIndices.sorted().map { fields[it].name }
    }

    private fun sensitiveDataClasses(classes: List<Class<*>>): List<Candidate> =
        classes.mapNotNull { type ->
            val components =
                type.declaredMethods.count { COMPONENT_ACCESSOR.matches(it.name) && it.parameterCount == 0 }
            if (components == 0) return@mapNotNull null

            val fields =
                type.declaredFields
                    .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                    .take(components)
            if (fields.size != components) return@mapNotNull null

            val constructor =
                type.declaredConstructors.firstOrNull { candidate ->
                    !candidate.isSynthetic && candidate.parameterTypes.toList() == fields.map { it.type }
                } ?: return@mapNotNull null

            val annotated = type.isAnnotationPresent(UserContent::class.java)
            val sensitive =
                fields
                    .withIndex()
                    .filter { (_, field) ->
                        field.type == String::class.java && (annotated || isSensitiveName(field.name))
                    }.map { it.index }
                    .toSet()

            if (sensitive.isEmpty()) null else Candidate(type, constructor, fields, sensitive)
        }

    private fun isSensitiveName(name: String): Boolean {
        val lowered = name.lowercase()
        return SENSITIVE_NAME_TOKENS.any { it in lowered }
    }

    /**
     * 표식을 심어 인스턴스를 만든다.
     *
     * 지원하지 않는 파라미터 타입을 만나면 **끊는다**. 조용히 건너뛰면 그 타입은 영영
     * 검사 밖에 남는데, 새 필드 타입이 들어오는 자리가 곧 새 DTO 가 생기는 자리다.
     */
    private fun instantiate(candidate: Candidate): Any {
        val arguments =
            candidate.fields.mapIndexed { index, field ->
                valueFor(
                    type = field.type,
                    sensitive = index in candidate.sensitiveIndices,
                    owner = candidate.type,
                    field = field,
                )
            }
        candidate.constructor.isAccessible = true
        return candidate.constructor.newInstance(*arguments.toTypedArray())
    }

    private fun valueFor(
        type: Class<*>,
        sensitive: Boolean,
        owner: Class<*>,
        field: Field,
    ): Any =
        when {
            type == String::class.java -> {
                if (sensitive) SENTINEL else FILLER
            }

            type == UUID::class.java -> {
                FIXED_UUID
            }

            type == Instant::class.java -> {
                Instant.EPOCH
            }

            type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> {
                0
            }

            type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> {
                0L
            }

            type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> {
                false
            }

            List::class.java.isAssignableFrom(type) -> {
                listOf(FILLER)
            }

            type.isEnum -> {
                type.enumConstants.first()
            }

            else -> {
                error(
                    "${owner.name}.${field.name} 의 타입 ${type.name} 을 이 탐지기가 만들 줄 모른다. " +
                        "valueFor 에 갈래를 더하라 — 건너뛰면 그 타입을 쓰는 DTO 가 통째로 검사 밖에 남는다.",
                )
            }
        }

    // ---------------------------------------------------------------- 클래스패스

    /**
     * 테스트 런타임 클래스패스의 `kr.easydoc.**` **제품** 클래스.
     *
     * 적재는 초기화 없이 한다(`initialize = false`) — 이 탐지기가 클래스 초기화 부작용을
     * 일으킬 이유가 없다. 적재·연결 실패는 **건너뛰지 않고 끊는다**: 조용히 빼면 그 모듈이
     * 통째로 검사 밖에 남는다.
     */
    private fun productionClasses(): List<Class<*>> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val metadata = CachingMetadataReaderFactory(resolver)
        val resources = resolver.getResources("classpath*:kr/easydoc/**/*.class")

        return resources
            .filter { resource ->
                val location = resource.url.toString()
                TEST_OUTPUT_MARKERS.none { it in location }
            }.map { resource ->
                val name = metadata.getMetadataReader(resource).classMetadata.className
                try {
                    Class.forName(name, false, javaClass.classLoader)
                } catch (failure: LinkageError) {
                    error("제품 클래스 $name 을 적재하지 못했다(${failure::class.java.simpleName}) — 탐지 범위가 조용히 줄어든다")
                }
            }
    }

    private companion object {
        /** 민감 필드에 심는 표식. `toString()` 산출에 이 문자열이 있으면 값이 새는 것이다. */
        const val SENTINEL = "SENSITIVE-PROBE-8f31c2d4"

        /** 민감하지 않은 `String` 파라미터에 넣는 값. 표식과 섞이면 판정이 흐려진다. */
        const val FILLER = "filler"

        val FIXED_UUID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

        val COMPONENT_ACCESSOR = Regex("""component\d+""")

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

        /** 테스트·testFixtures 산출물을 가르는 표식. 제품이 아닌 것을 검사 대상에 넣지 않는다. */
        val TEST_OUTPUT_MARKERS =
            listOf(
                "/classes/kotlin/test/",
                "/classes/java/test/",
                "/classes/kotlin/testFixtures/",
                "/classes/java/testFixtures/",
                "test-fixtures.jar",
            )

        /**
         * 클래스패스 필터가 제품 산출물을 통째로 걸러 버렸는지 보는 하한.
         *
         * 정확한 수를 못박지 않는 이유: 클래스가 느는 것은 정상이고 그 수를 여기 적으면
         * 무관한 커밋마다 이 파일을 고치게 된다. 막으려는 것은 **0 에 가까운 상태**다.
         */
        const val MIN_PRODUCTION_CLASSES = 60

        /**
         * 민감 판정이 반드시 닿아야 하는 타입 — **바닥**이다.
         *
         * 새 타입을 여기 적을 필요는 없다(`containsAll` 이지 정확 일치가 아니다). 이 목록이
         * 막는 것은 **기준이 조용히 좁아지는 방향**이다 — 필드 이름을 바꾸거나 토큰 목록을
         * 줄여 기존 타입이 검사 밖으로 나가면 여기서 빨개진다.
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
    }
}

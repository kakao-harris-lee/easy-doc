package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * **F3 의 두 번째 강제자 — 「그 다섯 필드의 길이·형식 판정이 어느 층에서 일어나는가」를
 * 응답 `detail` 의 모양으로 잰다.**
 *
 * ## 왜 이것이 필요해졌는가
 *
 * 계약은 다섯 요청 필드에 길이·형식 Bean Validation 을 금지했다(F3). 이 커밋 전까지 그
 * 금지를 지킨 것은 둘이었다 — [RequestFieldConstraintLayerTest] 의 **애너테이션 부재 스캔**
 * 과, 「`spring-boot-starter-validation` 이 클래스패스에 없어서 **달 수조차 없다**」는 사실.
 *
 * **이 커밋이 두 번째를 없앤다**(`GET /documents` 의 `limit`·`offset` 이 그 의존성을
 * 요구한다). 그 순간 스캔의 **금지 애너테이션 열거가 처음으로 실제 방벽이 되고, 열거가 곧
 * 약점이다** — 게이트 28 의 조치 레인이 같은 자리를 이미 짚었다: *"F3 는 열거된 금지
 * 애너테이션 스캔이라 그 목록 밖의 앞단 가드는 보이지 않는다."*
 *
 * ## 왜 열거를 넓히지 않았나 (`CLAUDE.md` 규칙 4 분류)
 *
 * 금지 애너테이션 목록은 **범위 선언형**이고 규칙 ⑶ 이 걸린다 — 불완전한 선언에서 통과하면
 * 안 된다. 그리고 그 목록은 **닫히지 않는다**: `@Positive`·`@DecimalMin`·`@Range`·
 * Hibernate 확장·직접 만든 `ConstraintValidator`·중첩 빈에 붙은 `@Valid`·커스텀
 * `HandlerMethodArgumentResolver` 가 모두 같은 일을 한다. 열거를 넓히는 것은 다음 항목이
 * 생길 때까지만 참인 조치다.
 *
 * 그래서 **탐지형으로 갈아탄다.** 재는 것은 애너테이션이 아니라 **나간 바이트**다 —
 * 스키마·바인딩 층이 거절하면 `detail` 이 **배열**이고, 서비스·도메인 층이 거절하면
 * **문자열**이다(계약 `ValidationFailed` 가 그 경계를 명시했고, C3 이 그 축을 세웠다).
 * 이 축은 **무엇이 앞단에서 거절했는지를 묻지 않는다** — 열거에 의존하지 않는 이유가 그것이다.
 *
 * ## 두 강제자를 함께 둔다 (대체가 아니라 추가)
 *
 * 애너테이션 스캔은 **엔드포인트가 없어도** 클래스가 생기는 즉시 돈다(`edited_text` 가 그
 * 상태다). 이 테스트는 요청을 실제로 보내야 하므로 엔드포인트를 요구한다. 둘의 도달 범위가
 * 달라서 어느 쪽도 다른 쪽을 덮지 못한다.
 *
 * ## 케이스를 계약에서 **유도한다**
 *
 * 필드 목록도, 경계 값도, 기대 문구도 계약에서 읽는다. 프로브는 `limit` 하나로 만든다:
 * 길이 `limit` · `limit-1` · `limit+1` 세 값을 보내고
 *
 *   ⑴ 길이 `limit` 은 **통과한다**(다섯 필드 전부 경계 포함이다 — 상한이면 「이하」,
 *      하한이면 「이상」),
 *   ⑵ `limit-1` 과 `limit+1` 중 **정확히 하나가 거절된다**(상한 필드는 뒤가, 하한 필드는
 *      앞이 거절된다 — 방향을 코드에 적지 않아도 이 형태로 판정된다),
 *   ⑶ 그 거절은 422 이고 `detail` 이 **문자열**이며 값이 계약이 그 필드에 선언한 문구 중
 *      하나다,
 *   ⑷ 어떤 프로브도 **배열** `detail` 을 내지 않는다.
 *
 * ## 「위반 0건」이 공허하지 않다는 것도 함께 잰다
 *
 * 판정 함수가 배열을 볼 수 있는지를 **대조 프로브**로 확인한다 — 필수 필드를 뺀 요청은
 * 배열 `detail` 이 나가야 하고, 같은 판정이 그것을 위반으로 지목해야 한다. 지목하지 못하면
 * 위 ⑷ 의 초록은 아무 뜻이 없다.
 */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class RequestFieldRejectionLayerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    private val json = ObjectMapper()

    // ================================================================ 도달 범위

    @Test
    @DisplayName("계약의 요청 필드 전부가 프로브를 갖거나 **DTO 가 아직 없다고 드러난다** — 조용히 건너뛰지 않는다")
    fun `계약 필드 전부가 다뤄진다`() {
        val fields = contractFields()
        assertThat(fields)
            .withFailMessage("계약 x-request-field-constraints.fields 가 비었다 — 아래 대조가 전부 0건 검사가 된다")
            .isNotEmpty()

        val apiSimpleNames = apiSimpleNames()
        val absent = fields.filterNot { it.substringBefore('.') in apiSimpleNames }.toSet()

        // **정확 열거 핀**이다. 계약이 필드를 더하면 프로브가 없어 빨강, DTO 가 생기면
        // 이 집합이 줄어 빨강 — 어느 쪽이든 diff 가 리뷰에 올라온다.
        assertThat(absent)
            .withFailMessage(
                "「api 모듈에 DTO 가 없는 계약 필드」 집합이 핀과 다르다.\n" +
                    "  줄었다면 그 DTO 가 생긴 것이다 — **프로브를 배선하고 핀에서 지워라**(그 커밋이 F3 마감이다).\n" +
                    "  늘었다면 DTO 가 사라졌거나 계약에 필드가 늘었다.\n  실제: %s / 핀: %s",
                absent,
                PINNED_WITHOUT_DTO,
            ).isEqualTo(PINNED_WITHOUT_DTO)

        assertThat(probes().keys)
            .withFailMessage(
                "프로브 목록이 「계약 필드 − DTO 없는 필드」와 다르다 — 프로브 없는 필드는 이 축에서 **검사받지 않는다**",
            ).isEqualTo(fields.toSet() - absent)
    }

    // ================================================================ 본 축

    @Test
    @DisplayName("길이 경계 위반이 **서비스 층**에서 거절된다 — 422 · detail 문자열 · 계약이 선언한 문구 (F3)")
    fun `길이 판정이 스키마 층에서 일어나지 않는다`() {
        val findings = probes().map { (field, probe) -> measure(field, probe) }

        assertThat(findings).isNotEmpty()

        val schemaLayer = findings.filter { it.arrayShaped.isNotEmpty() }
        assertThat(schemaLayer.map { "${it.field} ${it.arrayShaped}" })
            .withFailMessage(
                "아래 필드의 거절이 **배열** detail 로 나갔다 — 스키마·바인딩 층이 판정했다는 뜻이고 계약 F3 위반이다.\n" +
                    "  금지 애너테이션 목록에 없는 가드(커스텀 제약·앞단 리졸버·중첩 @Valid)도 여기서 잡힌다.\n%s",
                schemaLayer.joinToString("\n") { "  - ${it.field}: ${it.arrayShaped}" },
            ).isEmpty()

        val misjudged = findings.filter { it.problems.isNotEmpty() }
        assertThat(misjudged.map { it.field })
            .withFailMessage(
                "아래 필드의 경계 판정이 계약과 다르다:\n%s",
                misjudged.joinToString("\n") { "  - ${it.field}\n      ${it.problems.joinToString("\n      ")}" },
            ).isEmpty()
    }

    @Test
    @DisplayName("형식 위반도 서비스 층이다 — 이메일이 형식 축의 유일한 대상이다 (@Email·@Pattern 금지의 관측면)")
    fun `형식 판정이 스키마 층에서 일어나지 않는다`() {
        val declared = declaredDetails(SIGNUP_EMAIL_FIELD)

        val response = signup(email = MALFORMED_EMAIL, password = FILLER_CHAR.repeat(validPasswordLength()))

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        assertThat(detailOf(response))
            .withFailMessage("형식 위반이 배열 detail 로 나갔다 — @Email·@Pattern 이 붙으면 이렇게 된다")
            .isInstanceOf(String::class.java)
        assertThat(detailOf(response).toString()).isIn(declared)
    }

    // ================================================================ 판정 함수가 실제로 지목한다

    @Test
    @DisplayName("판정 함수가 배열 detail 을 지목한다 — 대조 프로브로 확인한다(위 초록이 공허하지 않다)")
    fun `판정 함수가 배열을 지목한다`() {
        // 필수 필드를 뺀 요청은 계약이 **배열**로 정한 갈래다(`ValidationFailed.field_missing`).
        // 같은 판정 함수가 그것을 「배열」로 분류해야, 위 케이스의 「배열 0건」이 뜻을 갖는다.
        val control = postJson(SIGNUP_PATH, "{}")

        assertThat(control.status).isEqualTo(UNPROCESSABLE)
        assertThat(isArrayShaped(control))
            .withFailMessage("판정 함수가 명백한 배열 detail 을 배열로 보지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .isTrue()

        // 과잉 탐지 0 — 문자열 갈래를 배열로 오인하지 않는다.
        val validPassword = FILLER_CHAR.repeat(validPasswordLength())
        val stringShaped = postJson(SIGNUP_PATH, credentials(MALFORMED_EMAIL, validPassword))
        assertThat(isArrayShaped(stringShaped)).isFalse()
    }

    // ================================================================ 측정

    /** 한 필드의 세 프로브를 돌린 결과. [problems] 가 비면 그 필드는 계약대로 판정됐다. */
    private class Finding(
        val field: String,
        val arrayShaped: List<String>,
        val problems: List<String>,
    )

    private fun measure(
        field: String,
        probe: (String) -> MockHttpServletResponse,
    ): Finding {
        val constraint = ContractSpec.requestFieldConstraint(field)
        val declared = declaredDetails(field)
        val outcomes =
            LENGTH_OFFSETS.associateWith { offset ->
                val length = constraint.limit + offset
                Outcome(length, probe(valueOf(field, length)))
            }

        val arrayShaped = outcomes.values.filter { isArrayShaped(it.response) }.map { "길이 ${it.length}" }
        val problems = mutableListOf<String>()

        val atLimit = outcomes.getValue(0)
        if (!atLimit.accepted) {
            problems += "길이가 **정확히** 상한/하한(${atLimit.length})인데 거절됐다(${atLimit.response.status}) — 경계 포함이 아니다"
        }

        val neighbours = listOf(outcomes.getValue(-1), outcomes.getValue(+1))
        val rejected = neighbours.filterNot { it.accepted }
        if (rejected.size != 1) {
            val observed = neighbours.joinToString(", ") { "길이 ${it.length}→${it.response.status}" }
            problems += "경계 이웃 두 값 중 거절된 것이 ${rejected.size} 개다 — 정확히 하나여야 한다($observed)"
        }
        rejected.forEach { outcome ->
            if (outcome.response.status != UNPROCESSABLE) {
                problems += "길이 ${outcome.length} 의 거절이 422 가 아니다: ${outcome.response.status}"
            }
            val detail = detailOf(outcome.response)
            if (detail !is String) {
                problems += "길이 ${outcome.length} 의 detail 이 문자열이 아니다: $detail"
            } else if (detail !in declared) {
                problems += "길이 ${outcome.length} 의 detail 이 계약 선언 문구가 아니다: \"$detail\" (선언: $declared)"
            }
        }
        return Finding(field, arrayShaped, problems)
    }

    private class Outcome(
        val length: Int,
        val response: MockHttpServletResponse,
    ) {
        val accepted: Boolean get() = response.status in SUCCESS_RANGE
    }

    /** `detail` 이 배열인가. **이 함수 하나가 판정이다** — 위 대조 프로브가 이것을 시험한다. */
    private fun isArrayShaped(response: MockHttpServletResponse): Boolean = detailOf(response) is List<*>

    // ================================================================ 프로브

    /**
     * 계약 필드 → 「그 필드에 이 값을 넣은 요청을 보낸다」.
     *
     * 이 매핑은 열거지만 **도달을 보증하는 것은 열거가 아니다** — 위 [`계약 필드 전부가 다뤄진다`]
     * 가 계약에서 읽은 필드 집합과 이 키 집합을 정확 일치로 대조한다. 계약에 필드가 늘면
     * 여기 없는 것이 빨강이다.
     */
    private fun probes(): Map<String, (String) -> MockHttpServletResponse> =
        mapOf(
            SIGNUP_EMAIL_FIELD to ::probeEmail,
            SIGNUP_PASSWORD_FIELD to ::probePassword,
            TEXT_FIELD to ::probeText,
            NAME_FIELD to ::probeName,
        )

    private fun probeEmail(value: String): MockHttpServletResponse =
        signup(email = value, password = FILLER_CHAR.repeat(validPasswordLength()))

    private fun probePassword(value: String): MockHttpServletResponse = signup(email = uniqueEmail(), password = value)

    private fun probeText(value: String): MockHttpServletResponse =
        postJson(DOCUMENTS_PATH, json.writeValueAsString(mapOf(TEXT_PROPERTY to value)), newOwner())

    private fun probeName(value: String): MockHttpServletResponse =
        postJson(WORKSPACES_PATH, json.writeValueAsString(mapOf(NAME_PROPERTY to value)), newOwner())

    /**
     * 길이 [length] 짜리 프로브 값.
     *
     * 이메일만 형태가 다르다 — 길이만 맞추고 형식이 깨지면 **형식 규칙이 먼저 걸려** 길이 축을
     * 재지 못한다. 그래서 도메인부를 고정하고 앞부분으로 길이를 맞춘다.
     */
    private fun valueOf(
        field: String,
        length: Int,
    ): String =
        if (field == SIGNUP_EMAIL_FIELD) {
            val fillerLength = length - EMAIL_DOMAIN.length
            require(fillerLength > 0) { "이메일 프로브 길이가 도메인부보다 짧다: $length" }
            "u${counter++}".padEnd(fillerLength, 'a').take(fillerLength) + EMAIL_DOMAIN
        } else {
            FILLER_CHAR.repeat(length)
        }

    private fun signup(
        email: String,
        password: String,
    ): MockHttpServletResponse = postJson(SIGNUP_PATH, credentials(email, password))

    private fun credentials(
        email: String,
        password: String,
    ): String = json.writeValueAsString(mapOf("email" to email, "password" to password))

    private fun postJson(
        path: String,
        body: String,
        owner: UUID? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                if (owner != null) header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    /** 인증이 필요한 프로브가 쓸 계정. 기본 작업 공간까지 만든다(`DocumentContractTest` 와 같은 규칙). */
    private fun newOwner(): UUID {
        val id = users.create("probe-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    private fun uniqueEmail(): String = "probe${counter++}$EMAIL_DOMAIN"

    /** 계약이 정한 비밀번호 하한 길이. 다른 필드의 프로브가 그 규칙에 걸리지 않게 한다. */
    private fun validPasswordLength(): Int = ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD_FIELD).limit

    // ================================================================ 계약 읽기

    private fun contractFields(): List<String> =
        ContractSpec
            .list("x-request-field-constraints", "fields")
            .filterIsInstance<Map<*, *>>()
            .map { it["field"]?.toString() ?: error("fields[] 항목에 field 가 없다") }

    /** 그 필드에 계약이 선언한 문구들. 하나짜리도 목록으로 편다 — 소비자가 갈래를 몰라도 되게. */
    private fun declaredDetails(field: String): List<String> {
        val constraint = ContractSpec.requestFieldConstraint(field)
        return when (val detail = constraint.detail) {
            is String -> listOf(detail)
            is List<*> -> detail.map { it.toString() }
            else -> error("$field 의 detail 이 문자열도 목록도 아니다: $detail")
        }
    }

    private fun apiSimpleNames(): Set<String> =
        ProductClasses
            .onTestRuntimeClasspath()
            .filter { it.qualifiedName?.startsWith(API_PACKAGE) == true }
            .mapNotNull { it.simpleName }
            .toSet()

    private fun detailOf(response: MockHttpServletResponse): Any? =
        json
            .readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)["detail"]

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val API_PACKAGE = "kr.easydoc.api."

        const val SIGNUP_PATH = "/auth/signup"
        const val DOCUMENTS_PATH = "/documents"
        const val WORKSPACES_PATH = "/workspaces"
        const val UNPROCESSABLE = 422
        val SUCCESS_RANGE = 200..299

        const val TEXT_PROPERTY = "text"
        const val NAME_PROPERTY = "name"

        /** 계약이 필드를 지목하는 **경로 문자열**이다. 값이 아니라 이름이다. */
        const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"
        const val TEXT_FIELD = "DocumentTextRequest.text"
        const val NAME_FIELD = "WorkspaceNameRequest.name"

        /**
         * **계약 필드 중 api 모듈에 DTO 가 아직 없는 것** — 정확 열거 핀이다.
         *
         * `ConversionReviewRequest` 는 `PUT /conversions/{id}` 를 만드는 커밋(C7)이 만든다.
         * 그 커밋이 이 핀을 비우고 프로브를 배선하면 F3 다섯 필드가 이 축에서 마감된다.
         *
         * **비우는 방향으로만 고쳐라.** 늘리는 편집은 「DTO 를 지웠거나 계약에 필드가 늘었다」는
         * 뜻이고, 어느 쪽이든 근거가 커밋 메시지에 있어야 한다.
         */
        val PINNED_WITHOUT_DTO = setOf("ConversionReviewRequest.edited_text")

        /** 경계와 그 이웃. 방향(상한/하한)을 코드에 적지 않기 위해 **양쪽 이웃을 함께** 본다. */
        val LENGTH_OFFSETS = listOf(-1, 0, +1)

        /** 길이만 맞추는 채움 문자. BMP 라 코드 포인트 수와 길이가 같다. */
        const val FILLER_CHAR = "가"

        const val EMAIL_DOMAIN = "@example.test"

        /** 형식 축 프로브. 길이는 규칙 안이고 형식만 깨져 있다. */
        const val MALFORMED_EMAIL = "not-an-email"

        var counter = 0
    }
}

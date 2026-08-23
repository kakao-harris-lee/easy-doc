package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryConversionRepository
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.api.support.ProductClasses
import kr.easydoc.api.support.RequestFieldProbes
import kr.easydoc.api.support.RequestFieldProbes.Observed
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * F3 의 두 번째 강제자 (슬라이스 관측) — 「그 다섯 필드의 길이·형식 판정이 어느 층에서
 * 일어나는가」를 응답 `detail` 의 모양으로 잰다. 판정과 프로브 조립은 [RequestFieldProbes] 다.
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

    @Autowired
    private lateinit var conversions: InMemoryConversionRepository

    @Autowired
    private lateinit var cipher: ContentCipher

    private val json = ObjectMapper()

    @Test
    @DisplayName("계약의 요청 필드 전부가 프로브를 갖거나 **DTO 가 아직 없다고 드러난다** — 조용히 건너뛰지 않는다")
    fun `계약 필드 전부가 다뤄진다`() {
        val fields = RequestFieldProbes.contractFields()
        assertThat(fields)
            .withFailMessage("계약 x-request-field-constraints.fields 가 비었다 — 아래 대조가 전부 0건 검사가 된다")
            .isNotEmpty()

        val apiSimpleNames =
            ProductClasses
                .onTestRuntimeClasspath()
                .filter { it.qualifiedName?.startsWith(API_PACKAGE) == true }
                .mapNotNull { it.simpleName }
                .toSet()
        val absent = fields.filterNot { it.substringBefore('.') in apiSimpleNames }.toSet()

        assertThat(absent)
            .withFailMessage(
                "「api 모듈에 DTO 가 없는 계약 필드」 집합이 핀과 다르다.\n" +
                    "  줄었다면 그 DTO 가 생긴 것이다 — **프로브를 배선하고 핀에서 지워라**(그 커밋이 F3 마감이다).\n" +
                    "  늘었다면 DTO 가 사라졌거나 계약에 필드가 늘었다.\n  실제: %s / 핀: %s",
                absent,
                PINNED_WITHOUT_DTO,
            ).isEqualTo(PINNED_WITHOUT_DTO)

        val expected = fields.toSet() - absent
        assertThat(probes().keys)
            .withFailMessage("프로브 목록이 「계약 필드 − DTO 없는 필드」와 다르다 — 프로브 없는 필드는 이 축에서 **검사받지 않는다**")
            .isEqualTo(expected)

        assertThat(RequestFieldProbes.FIELD_SHAPES.keys)
            .withFailMessage("RequestFieldProbes.FIELD_SHAPES 가 계약 필드 집합을 덮지 않는다")
            .isEqualTo(expected)
    }

    @Test
    @DisplayName("정규화 축도 계약 필드 전부에 걸린다 — measured_on 이 NORMALIZED 인 필드가 실재한다")
    fun `정규화 축의 대상이 실재한다`() {
        val normalized =
            probes().keys.filter { ContractSpec.requestFieldConstraint(it).measuresNormalized }

        assertThat(normalized)
            .withFailMessage("정규화 후를 재는 계약 필드가 하나도 없다 — 이 축은 아무것도 재지 않는다")
            .isNotEmpty()
        println("F3 정규화 축 대상: $normalized / 원시 축 대상: ${probes().keys - normalized.toSet()}")
    }

    @Test
    @DisplayName("길이·정규화·문구 갈래가 **서비스 층**에서 판정된다 — 422 · detail 문자열 · 계약 선언 문구 (F3)")
    fun `길이 판정이 스키마 층에서 일어나지 않는다`() {
        val findings = probes().map { (field, probe) -> RequestFieldProbes.measure(field, probe) }

        assertThat(findings).isNotEmpty()

        val schemaLayer = findings.filter { it.arrayShaped.isNotEmpty() }
        assertThat(schemaLayer.map { "${it.field} ${it.arrayShaped}" })
            .withFailMessage(
                "아래 필드의 거절이 **배열** detail 로 나갔다 — 스키마·바인딩 층이 판정했다는 뜻이고 계약 F3 위반이다.\n" +
                    "  금지 애너테이션 목록에 없는 가드(커스텀 제약·중첩 @Valid)도 여기서 잡힌다.\n%s",
                schemaLayer.joinToString("\n") { "  - ${it.field}: ${it.arrayShaped}" },
            ).isEmpty()

        val misjudged = findings.filter { it.problems.isNotEmpty() }
        assertThat(misjudged.map { it.field })
            .withFailMessage(
                "아래 필드의 판정이 계약과 다르다:\n%s",
                misjudged.joinToString("\n") { "  - ${it.field}\n      ${it.problems.joinToString("\n      ")}" },
            ).isEmpty()
    }

    @Test
    @DisplayName("형식 위반도 서비스 층이다 — 이메일이 형식 축의 유일한 대상이다 (@Email·@Pattern 금지의 관측면)")
    fun `형식 판정이 스키마 층에서 일어나지 않는다`() {
        val declared = RequestFieldProbes.declaredDetails(SIGNUP_EMAIL_FIELD)

        val observed = signup(email = RequestFieldProbes.MALFORMED_EMAIL, password = validPassword())

        assertThat(observed.status).isEqualTo(RequestFieldProbes.UNPROCESSABLE)
        assertThat(observed.detail)
            .withFailMessage("형식 위반이 배열 detail 로 나갔다 — @Email·@Pattern 이 붙으면 이렇게 된다")
            .isInstanceOf(String::class.java)
        assertThat(observed.detail.toString()).isIn(declared)
    }

    @Test
    @DisplayName("판정 함수가 배열 detail 을 지목한다 — 대조 프로브로 확인한다(위 초록이 공허하지 않다)")
    fun `판정 함수가 배열을 지목한다`() {
        val control = postJson(SIGNUP_PATH, "{}")

        assertThat(control.status).isEqualTo(RequestFieldProbes.UNPROCESSABLE)
        assertThat(control.arrayShaped)
            .withFailMessage("판정 함수가 명백한 배열 detail 을 배열로 보지 못했다 — 위 케이스의 초록은 아무 뜻이 없다")
            .isTrue()

        assertThat(signup(RequestFieldProbes.MALFORMED_EMAIL, validPassword()).arrayShaped).isFalse()
    }

    /** 계약 필드 → 「그 필드에 이 값을 넣은 요청을 보낸다」. */
    private fun probes(): Map<String, (String) -> Observed> =
        mapOf(
            SIGNUP_EMAIL_FIELD to { value -> signup(email = value, password = validPassword()) },
            SIGNUP_PASSWORD_FIELD to { value -> signup(email = RequestFieldProbes.uniqueEmail(), password = value) },
            TEXT_FIELD to ::probeText,
            NAME_FIELD to ::probeName,
            EDITED_TEXT_FIELD to ::probeEditedText,
        )

    private fun probeText(value: String): Observed =
        postJson(DOCUMENTS_PATH, json.writeValueAsString(mapOf(TEXT_PROPERTY to value)), newOwner())

    private fun probeName(value: String): Observed =
        postJson(WORKSPACES_PATH, json.writeValueAsString(mapOf(NAME_PROPERTY to value)), newOwner())

    /** 검수 저장은 **완료된 내 변환**을 전제한다 — 아니면 409 라 길이 축에 닿지 못한다. */
    private fun probeEditedText(value: String): Observed {
        val owner = newOwner()
        val conversionId = acceptDocument(owner)
        conversions.complete(
            conversionId = conversionId,
            ciphertexts =
                ConversionCiphertexts(
                    easyText =
                        cipher.encrypt(
                            PlainBody(PROBE_DRAFT),
                            conversionId,
                            EncryptedField.CONVERSION_EASY_TEXT,
                        ),
                    maskedItems = null,
                    editedText = null,
                ),
            missingPlaceholders = emptyList(),
            model = PROBE_MODEL,
            providerName = PROBE_PROVIDER,
            inputTokens = 1,
            outputTokens = 1,
        )
        val response =
            mockMvc
                .put("$CONVERSION_PATH_PREFIX$conversionId") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = json.writeValueAsString(mapOf(EDITED_TEXT_PROPERTY to value))
                }.andReturn()
                .response
        return Observed(response.status, detailOf(response))
    }

    /** 문서를 접수한다. **행은 제품이 쓴다.** */
    private fun acceptDocument(owner: UUID): UUID {
        val response =
            mockMvc
                .post(DOCUMENTS_PATH) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = json.writeValueAsString(mapOf(TEXT_PROPERTY to PROBE_BODY))
                }.andReturn()
                .response
        val body = json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)
        return UUID.fromString(
            body[CONVERSION_ID_PROPERTY]?.toString() ?: error("프로브 문서 접수가 실패했다: ${response.status} $body"),
        )
    }

    private fun signup(
        email: String,
        password: String,
    ): Observed = postJson(SIGNUP_PATH, json.writeValueAsString(mapOf("email" to email, "password" to password)))

    private fun postJson(
        path: String,
        body: String,
        owner: UUID? = null,
    ): Observed {
        val response =
            mockMvc
                .post(path) {
                    if (owner != null) header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn()
                .response
        return Observed(response.status, detailOf(response))
    }

    /** 인증이 필요한 프로브가 쓸 계정. 기본 작업 공간까지 만든다(`DocumentContractTest` 와 같은 규칙). */
    private fun newOwner(): UUID {
        val id = users.create("probe-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    /** 다른 필드의 프로브가 비밀번호 규칙에 걸리지 않게 하는 값. 길이는 계약 하한에서 온다. */
    private fun validPassword(): String =
        RequestFieldProbes.FILLER_CHAR.repeat(ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD_FIELD).limit)

    private fun detailOf(response: MockHttpServletResponse): Any? {
        val body = response.getContentAsString(StandardCharsets.UTF_8)
        if (body.isEmpty()) return null
        return json.readValue(body, Map::class.java)["detail"]
    }

    private companion object {
        val STUB_HASH = PasswordHash("stub-hash")

        const val API_PACKAGE = "kr.easydoc.api."

        const val SIGNUP_PATH = "/auth/signup"
        const val DOCUMENTS_PATH = "/documents"
        const val WORKSPACES_PATH = "/workspaces"

        const val TEXT_PROPERTY = "text"
        const val NAME_PROPERTY = "name"
        const val EDITED_TEXT_PROPERTY = "edited_text"
        const val CONVERSION_ID_PROPERTY = "conversion_id"

        const val CONVERSION_PATH_PREFIX = "/conversions/"

        /** 검수 프로브의 배경 값 — 길이 축과 무관하다. */
        const val PROBE_BODY = "검수 프로브용 안내문 본문"
        const val PROBE_DRAFT = "검수 프로브용 초안입니다."
        const val PROBE_MODEL = "probe-model"
        const val PROBE_PROVIDER = "probe-provider"

        /** 계약이 필드를 지목하는 경로 문자열이다. 값이 아니라 이름이다. */
        const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"
        const val TEXT_FIELD = "DocumentTextRequest.text"
        const val NAME_FIELD = "WorkspaceNameRequest.name"
        const val EDITED_TEXT_FIELD = "ConversionReviewRequest.edited_text"

        /** DTO 가 없는 계약 필드 — 정확 열거 핀. **비어 있다**: F3 다섯이 전부 검사받는다. */
        val PINNED_WITHOUT_DTO = emptySet<String>()
    }
}

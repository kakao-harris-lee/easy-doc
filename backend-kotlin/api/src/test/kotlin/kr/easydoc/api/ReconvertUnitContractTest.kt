package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.ControllableLlmProvider
import kr.easydoc.api.support.InMemoryConversionRepository
import kr.easydoc.api.support.InMemoryDocumentRepository
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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

/** `POST /conversions/{conversion_id}/units/{source_unit_index}/reconvert` 의 계약 — P0-4 S4. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ReconvertUnitContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var users: InMemoryUserRepository

    @Autowired
    private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired
    private lateinit var documents: InMemoryDocumentRepository

    @Autowired
    private lateinit var conversions: InMemoryConversionRepository

    @Autowired
    private lateinit var cipher: ContentCipher

    @Autowired
    private lateinit var provider: ControllableLlmProvider

    private val json = ObjectMapper()

    @BeforeEach
    fun resetProvider() {
        // 각 케이스가 스스로 필요한 만큼만 응답을 채운다 — 준비 없이 부르면 대역이 던진다.
        provider.willReturn()
    }

    @Test
    @DisplayName("완료된 내 변환의 유효한 색인 → 200 · 사적 헤더 2종 · 응답 키 집합이 계약과 같다")
    fun `행복 경로가 200 이고 응답 키 집합이 계약과 같다`() {
        val (owner, conversionId) = doneConversion(text = "첫째 줄\n둘째 줄")
        provider.willReturn(FakeLlmTurn.Reply("깨끗한 결과"))

        val response = reconvert(owner, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(200)
        assertThat(bodyOf(response).keys)
            .withFailMessage("응답 키 집합이 계약 %s 와 다르다", RECONVERT_RESPONSE_SCHEMA)
            .isEqualTo(ContractSpec.schemaRequired(RECONVERT_RESPONSE_SCHEMA))
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo(NO_STORE)
        assertThat(response.getHeader(X_CONTENT_TYPE_OPTIONS)).isEqualTo(NOSNIFF)

        val body = bodyOf(response)
        assertThat(body[CANDIDATE_TEXT_PROPERTY]).isEqualTo("깨끗한 결과")
        assertThat(body[SOURCE_UNIT_INDEX_PROPERTY]).isEqualTo(0)
        assertThat(body[EASY_UNIT_INDEXES_PROPERTY]).isEqualTo(listOf(0))
        assertThat(body[FINGERPRINT_PROPERTY]).isEqualTo(FINGERPRINT)
        assertThat(body[LLM_CALLS_USED_PROPERTY]).isEqualTo(1)
    }

    @Test
    @DisplayName("보정이 필요한 1차 결과 → llm_calls_used 가 2다")
    fun `보정 경로는 llm_calls_used 가 2다`() {
        val (owner, conversionId) = doneConversion(text = "금일 서류를 제출하십시오.")
        provider.willReturn(FakeLlmTurn.Reply("금일 서류를 내세요."), FakeLlmTurn.Reply("금일 서류를 내세요."))

        val response = reconvert(owner, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(200)
        assertThat(bodyOf(response)[LLM_CALLS_USED_PROPERTY]).isEqualTo(2)
    }

    @Test
    @DisplayName("Authorization 이 없으면 401")
    fun `토큰이 없으면 401 이다`() {
        val (_, conversionId) = doneConversion()

        val response = reconvertRaw(null, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(UNAUTHORIZED)
    }

    @Test
    @DisplayName("없는 변환·남의 변환 모두 404 다 — 존재 은폐")
    fun `없는 변환과 남의 변환이 같은 404 다`() {
        val (_, mine) = doneConversion()
        val (_, theirs) = doneConversion()
        val other = newOwner()

        val absent = reconvert(other, UUID.randomUUID(), 0, VALID_BODY)
        val others = reconvert(other, theirs, 0, VALID_BODY)

        assertThat(absent.status).isEqualTo(NOT_FOUND)
        assertThat(others.status).isEqualTo(NOT_FOUND)
        assertThat(bodyOf(others)).isEqualTo(bodyOf(absent))
    }

    @Test
    @DisplayName("완료 전 변환은 409 다")
    fun `완료 전이면 409 다`() {
        val owner = newOwner()
        val (documentId) = createDocument(owner, "본문 한 줄")
        val conversionId = UUID.randomUUID()
        conversions.insertPending(conversionId, documentId, cipher.writeScheme, cipher.writeKeyVersion)

        val response = reconvert(owner, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(CONFLICT)
    }

    @Test
    @DisplayName("색인이 원본 단위 범위를 벗어나면 422 문자열이다")
    fun `범위 밖 색인은 422 다`() {
        val (owner, conversionId) = doneConversion(text = "한 줄뿐이다")

        val response = reconvert(owner, conversionId, 5, VALID_BODY)

        assertThat(response.status).isEqualTo(UNPROCESSABLE)
        assertThat(bodyOf(response)[DETAIL]).isInstanceOf(String::class.java)
    }

    @Test
    @DisplayName("예산이 소진되면 429 이고 X-Remaining-Call-Budget 헤더가 붙는다 — LLM 을 부르지 않는다")
    fun `예산 소진은 429 다`() {
        val (owner, conversionId) = doneConversion()
        conversions.forceReconversionCallsUsed(conversionId, used = SLICE_BUDGET)

        val response = reconvert(owner, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(TOO_MANY_REQUESTS)
        assertThat(response.getHeader(REMAINING_BUDGET_HEADER)).isEqualTo("0")
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
            .withFailMessage("재변환 429 에는 Retry-After 가 없어야 한다 — 쿨다운이 아니다")
            .isNull()
        assertThat(provider.calls).isEmpty()
    }

    @Test
    @DisplayName("provider 호출이 실패하면 502 다")
    fun `provider 실패는 502 다`() {
        val (owner, conversionId) = doneConversion()
        provider.willReturn(FakeLlmTurn.Fail(LlmProviderException("실패")))

        val response = reconvert(owner, conversionId, 0, VALID_BODY)

        assertThat(response.status).isEqualTo(BAD_GATEWAY)
    }

    // ================================================================ 요청 조립

    private fun newOwner(): UUID {
        val id = users.create("reconvert-${UUID.randomUUID()}@example.test", STUB_HASH).id
        workspaces.createDefault(id)
        return id
    }

    /** `(문서 id, 워크스페이스 id)`. */
    private fun createDocument(
        owner: UUID,
        text: String,
    ): Pair<UUID, UUID> {
        val workspaceId =
            workspaces
                .listOwned(owner)
                .first()
                .workspace.id
        val documentId = UUID.randomUUID()
        documents.insert(
            ownerId = owner,
            draft =
                DocumentDraft(
                    id = documentId,
                    workspaceId = workspaceId,
                    title = "안내문",
                    sourceFormat = SourceFormat.TEXT,
                    charCount = text.length,
                ),
            sourceText = cipher.encrypt(PlainBody(text), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT),
        )
        return documentId to workspaceId
    }

    /** `(소유자, 변환 id)` — 완료 상태로 심는다. */
    private fun doneConversion(text: String = "첫째 줄"): Pair<UUID, UUID> {
        val owner = newOwner()
        val (documentId) = createDocument(owner, text)
        val conversionId = UUID.randomUUID()
        conversions.insertPending(conversionId, documentId, cipher.writeScheme, cipher.writeKeyVersion)
        conversions.complete(
            conversionId = conversionId,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = cipher.encrypt(PlainBody("쉬운 글 초안"), conversionId, EncryptedField.CONVERSION_EASY_TEXT),
                    maskedItems = null,
                    editedText = null,
                ),
            missingPlaceholders = emptyList(),
            model = "fake-model",
            providerName = "fake",
            inputTokens = 0,
            outputTokens = 0,
        )
        return owner to conversionId
    }

    private fun reconvert(
        owner: UUID,
        conversionId: UUID,
        sourceUnitIndex: Int,
        body: String,
    ): MockHttpServletResponse = reconvertRaw("stub-token:$owner", conversionId, sourceUnitIndex, body)

    private fun reconvertRaw(
        bearerToken: String?,
        conversionId: UUID,
        sourceUnitIndex: Int,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post(reconvertPath(conversionId, sourceUnitIndex)) {
                bearerToken?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun reconvertPath(
        conversionId: UUID,
        sourceUnitIndex: Int,
    ): String =
        RECONVERT_PATH
            .replace("{conversion_id}", conversionId.toString())
            .replace("{source_unit_index}", sourceUnitIndex.toString())

    private fun bodyOf(response: MockHttpServletResponse): Map<*, *> =
        json.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map::class.java)

    private companion object {
        const val RECONVERT_PATH = "/conversions/{conversion_id}/units/{source_unit_index}/reconvert"
        const val RECONVERT_RESPONSE_SCHEMA = "ReconvertUnitResponse"

        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422
        const val TOO_MANY_REQUESTS = 429
        const val BAD_GATEWAY = 502

        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val REMAINING_BUDGET_HEADER = "X-Remaining-Call-Budget"

        const val CANDIDATE_TEXT_PROPERTY = "candidate_text"
        const val SOURCE_UNIT_INDEX_PROPERTY = "source_unit_index"
        const val EASY_UNIT_INDEXES_PROPERTY = "easy_unit_indexes"
        const val FINGERPRINT_PROPERTY = "easy_text_fingerprint"
        const val LLM_CALLS_USED_PROPERTY = "llm_calls_used"
        const val DETAIL = "detail"

        /** `AuthSliceBeans.SLICE_RECONVERSION_CALL_BUDGET` 과 같은 값. */
        const val SLICE_BUDGET = 20

        val FINGERPRINT = "a".repeat(64)
        val VALID_BODY =
            """{"easy_unit_indexes":[0],"easy_text_fingerprint":"$FINGERPRINT"}"""

        val STUB_HASH = PasswordHash("stub-hash")
    }
}

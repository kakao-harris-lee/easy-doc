package kr.easydoc.api

import kr.easydoc.api.config.JsonRequestStrictnessConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionAttemptLimitExceededException
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobCollectionView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobCreationView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobService
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobView
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultService
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultStatus
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultView
import kr.easydoc.application.illustration.suggestion.SUGGESTION_ATTEMPT_LIMIT_MESSAGE
import kr.easydoc.application.illustration.suggestion.SUGGESTION_CREDITS_UNCONFIGURED_MESSAGE
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** R7 ER-17 계약(명세 §6) — 응답 필드 집합·상태 코드·헤더를 HTTP 경계에서 고정한다. */
@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, JsonRequestStrictnessConfig::class, AuthSliceBeans::class)
class IllustrationSuggestionContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: IllustrationSuggestionJobService

    @Autowired private lateinit var results: IllustrationSuggestionResultService

    @Test
    @DisplayName("접수는 202와 Location·X-Credit-Balance 를 내고 응답 필드가 계약과 같다")
    fun `접수는 202와 조회 위치를 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val job = job(requestId = requestId)
        `when`(service.create(owner, conversionId, requestId, 7))
            .thenReturn(IllustrationSuggestionJobCreationView(job, availableCredits = BigDecimal("0.8")))

        val response = post(owner, conversionId, """{"request_id":"$requestId","expected_content_revision":7}""")

        assertThat(response.status).isEqualTo(202)
        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .isEqualTo("/conversions/$conversionId/illustration-suggestion-jobs/${job.jobId}")
        assertThat(response.getHeader("X-Credit-Balance")).isEqualTo("0.8")
        assertThat(json(response)["status"].asString()).isEqualTo("queued")
        assertThat(json(response).propertyNames())
            .containsExactlyInAnyOrder(
                "job_id",
                "request_id",
                "status",
                "based_on_content_revision",
                "reserved_credits",
                "failure_code",
                "created_at",
                "updated_at",
            )
    }

    @Test
    @DisplayName("본문 버전이 없거나 0이면 422다 — 행동 안내의 두 번째 축은 이 요청에 없다")
    fun `잘못된 요청 본문은 422다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        assertThat(post(owner, conversionId, """{"request_id":"$requestId"}""").status).isEqualTo(422)
        assertThat(
            post(owner, conversionId, """{"request_id":"$requestId","expected_content_revision":0}""").status,
        ).isEqualTo(422)
        assertThat(
            post(owner, conversionId, """{"request_id":"$requestId","expected_content_revision":-1}""").status,
        ).isEqualTo(422)
    }

    @Test
    @DisplayName("시도 상한 초과는 Retry-After 없는 429와 detail 본문이다")
    fun `시도 상한 초과는 429다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(service.create(owner, conversionId, requestId, 7))
            .thenThrow(IllustrationSuggestionAttemptLimitExceededException(SUGGESTION_ATTEMPT_LIMIT_MESSAGE))

        val response = post(owner, conversionId, """{"request_id":"$requestId","expected_content_revision":7}""")

        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull()
        assertThat(json(response).propertyNames()).containsExactly("detail")
        assertThat(json(response)["detail"].asString()).isEqualTo(SUGGESTION_ATTEMPT_LIMIT_MESSAGE)
    }

    @Test
    @DisplayName("이용량 단가 미설정은 503이다 — 사용자 잘못이 아니라 운영 설정이 빠진 상태다")
    fun `이용량 미설정은 503이다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(service.create(owner, conversionId, requestId, 7))
            .thenThrow(ConfigurationException(SUGGESTION_CREDITS_UNCONFIGURED_MESSAGE))

        val response = post(owner, conversionId, """{"request_id":"$requestId","expected_content_revision":7}""")

        assertThat(response.status).isEqualTo(503)
        assertThat(json(response).propertyNames()).containsExactly("detail")
    }

    @Test
    @DisplayName("작업 목록은 활성·최신 작업과 두 이용량 값을 내고, 단가 미설정이면 required_credits 가 null 이다")
    fun `작업 목록이 이용량을 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val job = job()
        `when`(service.list(owner, conversionId))
            .thenReturn(IllustrationSuggestionJobCollectionView(job, job, null, BigDecimal("0.8")))

        val response = get(owner, "/conversions/$conversionId/illustration-suggestion-jobs")

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["active_job"]["job_id"].asString()).isEqualTo(job.jobId.toString())
        assertThat(json(response)["required_credits"].isNull).isTrue()
        assertThat(json(response)["available_credits"].decimalValue().toPlainString()).isEqualTo("0.8")
    }

    @Test
    @DisplayName("제안 조회는 상태와 제안 목록·버린 수를 계약 필드 그대로 낸다")
    fun `제안 조회가 결과를 낸다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(results.get(owner, conversionId))
            .thenReturn(
                IllustrationSuggestionResultView(
                    status = IllustrationSuggestionResultStatus.READY,
                    contentRevision = 4,
                    basedOnContentRevision = 4,
                    requiredCredits = BigDecimal("0.2"),
                    suggestions = listOf(suggestion()),
                    droppedCount = 2,
                ),
            )

        val response = get(owner, "/conversions/$conversionId/illustration-suggestions")

        assertThat(response.status).isEqualTo(200)
        val body = json(response)
        assertThat(body.propertyNames())
            .containsExactlyInAnyOrder(
                "status",
                "content_revision",
                "based_on_content_revision",
                "required_credits",
                "suggestions",
                "dropped_count",
            )
        assertThat(body["status"].asString()).isEqualTo("ready")
        assertThat(body["dropped_count"].asInt()).isEqualTo(2)
        val suggestion = body["suggestions"][0]
        assertThat(suggestion.propertyNames())
            .containsExactlyInAnyOrder(
                "suggestion_id",
                "purpose",
                "reason",
                "body_range",
                "source_anchors",
                "scenes",
                "preserved_facts",
                "alt_text_draft",
            )
        assertThat(suggestion["purpose"].asString()).isEqualTo("procedure")
        assertThat(suggestion["body_range"].propertyNames()).containsExactlyInAnyOrder("start", "end")
        assertThat(suggestion["source_anchors"][0].propertyNames())
            .containsExactlyInAnyOrder("source_unit_indexes", "quote")
    }

    @Test
    @DisplayName("분석 전에는 not_analyzed 이고 based_on_content_revision 이 null 이다")
    fun `분석 전 결과는 not_analyzed 다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(results.get(owner, conversionId))
            .thenReturn(
                IllustrationSuggestionResultView(
                    status = IllustrationSuggestionResultStatus.NOT_ANALYZED,
                    contentRevision = 1,
                    basedOnContentRevision = null,
                    requiredCredits = BigDecimal("0.1"),
                    suggestions = emptyList(),
                    droppedCount = 0,
                ),
            )

        val body = json(get(owner, "/conversions/$conversionId/illustration-suggestions"))

        assertThat(body["status"].asString()).isEqualTo("not_analyzed")
        assertThat(body["based_on_content_revision"].isNull).isTrue()
        assertThat(body["suggestions"]).isEmpty()
    }

    private fun post(
        owner: UUID,
        conversionId: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/conversions/$conversionId/illustration-suggestion-jobs") {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun get(
        owner: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner") }
            .andReturn()
            .response

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun job(requestId: UUID = UUID.randomUUID()): IllustrationSuggestionJobView {
        val at = Instant.parse("2026-09-24T00:00:00Z")
        return IllustrationSuggestionJobView(
            jobId = UUID.randomUUID(),
            requestId = requestId,
            status = IllustrationSuggestionJobStatus.QUEUED,
            basedOnContentRevision = 7,
            reservedCredits = BigDecimal("0.2"),
            failureCode = null,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun suggestion() =
        IllustrationSuggestion(
            suggestionId = UUID.randomUUID(),
            purpose = IllustrationSuggestionPurpose.PROCEDURE,
            reason = "신청 순서를 그림으로 보면 이해하기 쉽다",
            bodyRange = IllustrationSuggestionBodyRange(0, 2),
            sourceAnchors = listOf(IllustrationSuggestionSourceAnchor(listOf(0), "신청서를 제출합니다")),
            scenes = listOf("신청서를 내는 장면"),
            preservedFacts = emptyList(),
            altTextDraft = "신청 순서를 보여 주는 그림",
        )

    private fun newOwner(): UUID {
        val id = users.create("suggestion-${UUID.randomUUID()}@example.test", PasswordHash("stub-hash")).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }
}

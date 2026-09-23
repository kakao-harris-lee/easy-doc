package kr.easydoc.api

import kr.easydoc.api.config.JsonRequestStrictnessConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.api.support.InMemoryWorkspaceRepository
import kr.easydoc.application.actionguide.ATTEMPT_LIMIT_MESSAGE
import kr.easydoc.application.actionguide.ActionGuideAttemptLimitExceededException
import kr.easydoc.application.actionguide.ActionGuideContentService
import kr.easydoc.application.actionguide.ActionGuideJobCollectionView
import kr.easydoc.application.actionguide.ActionGuideJobCreationView
import kr.easydoc.application.actionguide.ActionGuideJobService
import kr.easydoc.application.actionguide.ActionGuideJobView
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
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

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, JsonRequestStrictnessConfig::class, AuthSliceBeans::class)
class ActionGuideJobContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var workspaces: InMemoryWorkspaceRepository

    @Autowired private lateinit var service: ActionGuideJobService

    @Autowired private lateinit var contentService: ActionGuideContentService

    @Test
    fun `생성은 202와 조회 위치 및 예약 직후 잔액을 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val job = job(requestId = requestId)
        `when`(service.create(owner, conversionId, requestId, 7, null))
            .thenReturn(ActionGuideJobCreationView(job, availableCredits = BigDecimal("0.8")))

        val response =
            mockMvc
                .post("/conversions/$conversionId/action-guide-jobs") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"request_id":"$requestId","expected_content_revision":7,"expected_guide_revision":null}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(202)
        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .isEqualTo("/conversions/$conversionId/action-guide-jobs/${job.jobId}")
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
                "candidate_id",
                "candidate_state",
                "content",
            )
    }

    @Test
    fun `expected guide revision은 필수 nullable이고 content revision 0은 422다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        val missingGuide = post(owner, conversionId, requestId, """"expected_content_revision":1""")
        val zeroContent =
            post(
                owner,
                conversionId,
                requestId,
                """"expected_content_revision":0,"expected_guide_revision":null""",
            )

        assertThat(missingGuide).isEqualTo(422)
        assertThat(zeroContent).isEqualTo(422)
    }

    @Test
    fun `시도 상한 초과는 Retry-After 없는 429와 detail 본문이다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        `when`(service.create(owner, conversionId, requestId, 7, null))
            .thenThrow(ActionGuideAttemptLimitExceededException(ATTEMPT_LIMIT_MESSAGE))

        val response =
            mockMvc
                .post("/conversions/$conversionId/action-guide-jobs") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"request_id":"$requestId","expected_content_revision":7,"expected_guide_revision":null}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(429)
        // 쿨다운이 아니라 문서당 영구 상한이라 계약이 Retry-After 를 금지한다.
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull()
        assertThat(json(response).propertyNames()).containsExactly("detail")
        assertThat(json(response)["detail"].asString()).isEqualTo(ATTEMPT_LIMIT_MESSAGE)
    }

    @Test
    fun `목록은 active latest와 두 크레딧 값을 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val job = job()
        `when`(service.list(owner, conversionId))
            .thenReturn(
                ActionGuideJobCollectionView(
                    job,
                    job,
                    requiredCredits = BigDecimal("0.2"),
                    availableCredits = BigDecimal("0.8"),
                ),
            )

        val response =
            mockMvc
                .get("/conversions/$conversionId/action-guide-jobs") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["active_job"]["job_id"].asString()).isEqualTo(job.jobId.toString())
        assertThat(json(response)["required_credits"].decimalValue().toPlainString()).isEqualTo("0.2")
        assertThat(json(response)["available_credits"].decimalValue().toPlainString()).isEqualTo("0.8")
    }

    @Test
    fun `실행 중 상태에는 완료 후보를 섞어 반환하지 않는다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val job = job().copy(status = ActionGuideJobStatus.RUNNING)
        `when`(service.get(owner, conversionId, job.jobId)).thenReturn(job)
        clearInvocations(contentService)

        val response =
            mockMvc
                .get("/conversions/$conversionId/action-guide-jobs/${job.jobId}") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["status"].asString()).isEqualTo("running")
        assertThat(json(response)["candidate_id"].isNull).isTrue()
        verifyNoInteractions(contentService)
    }

    private fun post(
        owner: UUID,
        conversionId: UUID,
        requestId: UUID,
        rest: String,
    ): Int =
        mockMvc
            .post("/conversions/$conversionId/action-guide-jobs") {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content = """{"request_id":"$requestId",$rest}"""
            }.andReturn()
            .response.status

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun job(requestId: UUID = UUID.randomUUID()): ActionGuideJobView {
        val at = Instant.parse("2026-09-18T00:00:00Z")
        return ActionGuideJobView(
            jobId = UUID.randomUUID(),
            requestId = requestId,
            status = ActionGuideJobStatus.QUEUED,
            basedOnContentRevision = 7,
            reservedCredits = BigDecimal("0.2"),
            failureCode = null,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun newOwner(): UUID {
        val id = users.create("action-guide-${UUID.randomUUID()}@example.test", PasswordHash("stub-hash")).id
        workspaces.createDefault(id)
        users.markEmailVerified(id)
        return id
    }
}

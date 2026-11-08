package kr.easydoc.api

import kr.easydoc.api.config.JsonRequestStrictnessConfig
import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.ContractSpec
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetService
import kr.easydoc.application.illustration.generation.GeneratedIllustrationCollectionView
import kr.easydoc.application.illustration.generation.GeneratedIllustrationImage
import kr.easydoc.application.illustration.generation.GeneratedIllustrationView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobCollectionView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobCreationView
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobService
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobView
import kr.easydoc.core.illustration.generation.IllustrationGenerationConfiguration
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.illustration.generation.IllustrationGenerationMetadata
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, JsonRequestStrictnessConfig::class, AuthSliceBeans::class)
class IllustrationGenerationContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var users: InMemoryUserRepository

    @Autowired private lateinit var jobs: IllustrationGenerationJobService

    @Autowired private lateinit var assets: GeneratedIllustrationAssetService

    @Test
    fun `생성 접수는 202 Location 크레딧 헤더와 작업 필드를 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val job = job()
        val configuration = configuration()
        `when`(
            jobs.create(
                owner,
                conversionId,
                REQUEST_ID,
                3,
                SUGGESTION_ID,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                CONFIGURATION_REVISION,
                configuration,
            ),
        ).thenReturn(IllustrationGenerationJobCreationView(job, BigDecimal("4.0")))

        val response = post(owner, conversionId)

        assertThat(response.status).isEqualTo(202)
        assertThat(response.getHeader(HttpHeaders.LOCATION))
            .isEqualTo("/conversions/$conversionId/illustration-generation-jobs/${job.jobId}")
        assertThat(response.getHeader("X-Credit-Balance")).isEqualTo("4.0")
        assertThat(json(response).propertyNames()).containsExactlyInAnyOrder(
            "job_id",
            "request_id",
            "status",
            "suggestion_id",
            "analysis_version",
            "configuration_revision",
            "based_on_content_revision",
            "reserved_credits",
            "failure_code",
            "created_at",
            "updated_at",
        )
    }

    @Test
    fun `작업 컬렉션은 활성 최신 작업과 이용량을 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val job = job()
        `when`(jobs.list(owner, conversionId)).thenReturn(
            IllustrationGenerationJobCollectionView(
                job,
                job,
                BigDecimal("0.5"),
                BigDecimal("4.0"),
                true,
                requestCount = 10,
                requestLimit = 10,
                remainingRequestCount = 0,
            ),
        )

        val response = get(owner, "/conversions/$conversionId/illustration-generation-jobs")

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["active_job"]["job_id"].asString()).isEqualTo(job.jobId.toString())
        assertThat(json(response)["required_credits"].decimalValue()).isEqualTo(BigDecimal("0.5"))
        assertThat(json(response)["enabled"].asBoolean()).isTrue()
        assertThat(json(response)["request_count"].asInt()).isEqualTo(10)
        assertThat(json(response)["request_limit"].asInt()).isEqualTo(10)
        assertThat(json(response)["remaining_request_count"].asInt()).isZero()
        assertThat(json(response).propertyNames()).contains(
            "request_count",
            "request_limit",
            "remaining_request_count",
        )
        assertThat(ContractSpec.schemaRequired("IllustrationGenerationJobCollectionResponse")).contains(
            "request_count",
            "request_limit",
            "remaining_request_count",
        )
    }

    @Test
    fun `자산 목록은 근거 대체텍스트 stale revision과 비공개 URL을 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        `when`(assets.list(owner, conversionId)).thenReturn(
            GeneratedIllustrationCollectionView(4, listOf(asset(conversionId, stale = true))),
        )

        val response = get(owner, "/conversions/$conversionId/generated-illustrations")

        assertThat(response.status).isEqualTo(200)
        val asset = json(response)["assets"][0]
        assertThat(asset.propertyNames()).containsExactlyInAnyOrder(
            "asset_id",
            "job_id",
            "suggestion_id",
            "analysis_version",
            "configuration_revision",
            "based_on_content_revision",
            "stale",
            "applied",
            "discarded",
            "asset_revision",
            "purpose",
            "reason",
            "body_range",
            "source_anchors",
            "scenes",
            "preserved_facts",
            "alt_text",
            "image_url",
            "download_url",
            "created_at",
            "provenance",
        )
        assertThat(asset["stale"].asBoolean()).isTrue()
        assertThat(asset["provenance"].asString()).isEqualTo("fake")
        assertThat(asset["alt_text"].asString()).isEqualTo(ALT_TEXT)
    }

    @Test
    fun `이미지와 다운로드는 인증 소유자의 PNG와 사적 헤더를 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val assetId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4)
        `when`(assets.image(owner, conversionId, assetId)).thenReturn(GeneratedIllustrationImage(bytes))

        val image = get(owner, "/conversions/$conversionId/generated-illustrations/$assetId/image")
        val download = get(owner, "/conversions/$conversionId/generated-illustrations/$assetId/download")

        assertThat(image.status).isEqualTo(200)
        assertThat(image.contentType).isEqualTo("image/png")
        assertThat(image.contentAsByteArray).isEqualTo(bytes)
        assertThat(image.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
        assertThat(image.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(download.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment")
    }

    @Test
    fun `적용 변경은 두 revision을 서비스에 전달하고 자산을 반환한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val view = asset(conversionId, applied = true, assetRevision = 2)
        `when`(assets.updateApplication(owner, conversionId, ASSET_ID, 4, 1, true)).thenReturn(view)

        val response =
            mockMvc
                .put(
                    "/conversions/$conversionId/generated-illustrations/$ASSET_ID/application",
                ) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"expected_content_revision":4,"expected_asset_revision":1,"applied":true}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["applied"].asBoolean()).isTrue()
        assertThat(json(response)["asset_revision"].asLong()).isEqualTo(2)
    }

    @Test
    fun `자산 PATCH는 대체텍스트와 폐기를 CAS revision과 함께 전달한다`() {
        val owner = newOwner()
        val conversionId = UUID.randomUUID()
        val view = asset(conversionId, discarded = true, assetRevision = 2)
        `when`(assets.updateReview(owner, conversionId, ASSET_ID, 4, 1, ALT_TEXT, true)).thenReturn(view)

        val response =
            mockMvc
                .patch("/conversions/$conversionId/generated-illustrations/$ASSET_ID") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"expected_content_revision":4,"expected_asset_revision":1,
                        "alt_text":"$ALT_TEXT","discarded":true}"""
                }.andReturn()
                .response

        assertThat(response.status)
            .isEqualTo(
                ContractSpec.successStatus(
                    "/conversions/{conversion_id}/generated-illustrations/{asset_id}",
                    "patch",
                ),
            )
        assertThat(ContractSpec.schemaRequired("GeneratedIllustrationUpdateRequest"))
            .containsExactlyInAnyOrder("expected_content_revision", "expected_asset_revision")
        assertThat(json(response)["discarded"].asBoolean()).isTrue()
        assertThat(json(response)["asset_revision"].asLong()).isEqualTo(2)
        assertThat(json(response)["configuration_revision"].asString()).isEqualTo(CONFIGURATION_REVISION)
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store")
    }

    private fun post(
        owner: UUID,
        conversionId: UUID,
    ): MockHttpServletResponse =
        mockMvc
            .post("/conversions/$conversionId/illustration-generation-jobs") {
                header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                contentType = MediaType.APPLICATION_JSON
                content =
                    mapper.writeValueAsString(
                        mapOf(
                            "request_id" to REQUEST_ID,
                            "expected_content_revision" to 3,
                            "suggestion_id" to SUGGESTION_ID,
                            "analysis_version" to ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                            "configuration_revision" to CONFIGURATION_REVISION,
                            "configuration" to
                                mapOf(
                                    "scenes" to listOf(SCENE),
                                    "preserved_facts" to listOf(FACT),
                                    "alt_text" to ALT_TEXT,
                                ),
                        ),
                    )
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

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString)

    private fun newOwner(): UUID = users.create("generation-${UUID.randomUUID()}@example.test", PasswordHash("hash")).id

    private companion object {
        val REQUEST_ID: UUID = UUID.randomUUID()
        val SUGGESTION_ID: UUID = UUID.randomUUID()
        val ASSET_ID: UUID = UUID.randomUUID()
        const val SCENE = "신청서를 내는 장면"
        const val FACT = "신청서를 내야 한다"
        const val ALT_TEXT = "신청 순서를 보여 주는 그림"
        val CONFIGURATION_REVISION = illustrationConfigurationRevision(configuration())

        fun configuration() = IllustrationGenerationConfiguration(listOf(SCENE), listOf(FACT), ALT_TEXT)

        fun job() =
            IllustrationGenerationJobView(
                UUID.randomUUID(),
                REQUEST_ID,
                IllustrationGenerationJobStatus.QUEUED,
                SUGGESTION_ID,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                CONFIGURATION_REVISION,
                3,
                BigDecimal("0.5"),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
            )

        fun asset(
            conversionId: UUID,
            stale: Boolean = false,
            applied: Boolean = false,
            discarded: Boolean = false,
            assetRevision: Long = 1,
        ): GeneratedIllustrationView {
            val metadata =
                IllustrationGenerationMetadata(
                    SUGGESTION_ID,
                    ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                    CONFIGURATION_REVISION,
                    IllustrationSuggestionPurpose.PROCEDURE,
                    "신청 절차를 설명한다",
                    IllustrationSuggestionBodyRange(0, 1),
                    listOf(IllustrationSuggestionSourceAnchor(listOf(0), "신청서를 제출합니다")),
                    listOf(SCENE),
                    listOf(FACT),
                    ALT_TEXT,
                    discarded,
                )
            return GeneratedIllustrationView(
                ASSET_ID,
                UUID.randomUUID(),
                3,
                stale,
                applied,
                discarded,
                assetRevision,
                metadata,
                "/conversions/$conversionId/generated-illustrations/$ASSET_ID/image",
                "/conversions/$conversionId/generated-illustrations/$ASSET_ID/download",
                Instant.EPOCH,
                "fake",
            )
        }
    }
}

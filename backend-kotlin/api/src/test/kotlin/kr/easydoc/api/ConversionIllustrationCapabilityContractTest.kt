package kr.easydoc.api

import kr.easydoc.api.config.PrivateResponseHeadersConfig
import kr.easydoc.api.illustration.IllustrationAccessGuard
import kr.easydoc.api.support.AuthSliceBeans
import kr.easydoc.api.support.InMemoryUserRepository
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.ReviewCapabilities
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@WebMvcTest
@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)
class ConversionIllustrationCapabilityContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var conversions: ConversionQueryService

    @Autowired
    private lateinit var illustrationAccess: IllustrationAccessGuard

    @Autowired
    private lateinit var users: InMemoryUserRepository

    private val json = ObjectMapper()

    @Test
    fun `전역 read와 create 설정이 켜져 있어도 허용 목록 밖 사용자의 그림 capability는 모두 false다`() {
        // 인증 인터셉터가 `users.exists` 를 돌므로 토큰 주인은 실제로 있어야 한다.
        val owner = users.create("illustration-${UUID.randomUUID()}@example.test", PasswordHash("stub-hash")).id
        val conversionId = UUID.randomUUID()
        `when`(conversions.read(owner, conversionId)).thenReturn(view(conversionId))
        `when`(illustrationAccess.allows(owner)).thenReturn(false)

        val response =
            mockMvc
                .get("/conversions/$conversionId") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer stub-token:$owner")
                }.andReturn()
                .response
        val capabilities = json.readTree(response.contentAsString)["review_capabilities"]

        assertThat(response.status).isEqualTo(200)
        listOf(
            "illustrations",
            "illustration_suggestions",
            "illustration_generation",
            "illustration_suggestions_read",
            "illustration_suggestions_create",
            "illustration_generation_read",
            "illustration_generation_create",
        ).forEach { field ->
            assertThat(capabilities[field].asBoolean())
                .withFailMessage("%s 가 마스킹되지 않았다", field)
                .isFalse()
        }
    }

    private fun view(conversionId: UUID) =
        ConversionView(
            id = conversionId,
            documentId = UUID.randomUUID(),
            status = ConversionStatus.PENDING,
            sourceFormat = SourceFormat.TXT,
            exportFormat = ExportFormat.TXT,
            exportFormatChoices = ExportFormat.choicesFor(SourceFormat.TXT),
            formatPreservation = null,
            easyText = null,
            editedText = null,
            reviewedAt = null,
            feedbackSubmittedAt = null,
            segmentMap = null,
            model = null,
            providerName = null,
            inputTokens = null,
            outputTokens = null,
            failureCode = null,
            reviewCapabilities =
                ReviewCapabilities(
                    reviewSupport = false,
                    focusedReview = false,
                    actionGuide = false,
                    tableRelations = false,
                    reviewHistory = false,
                    explanations = false,
                    illustrations = true,
                    illustrationSuggestions = true,
                    illustrationGeneration = true,
                    illustrationSuggestionsRead = true,
                    illustrationSuggestionsCreate = true,
                    illustrationGenerationRead = true,
                    illustrationGenerationCreate = true,
                ),
        )

    @TestConfiguration(proxyBeanMethods = false)
    class QueryConfiguration {
        @Bean
        @Primary
        fun primaryConversionQueryService(): ConversionQueryService = mock(ConversionQueryService::class.java)
    }
}

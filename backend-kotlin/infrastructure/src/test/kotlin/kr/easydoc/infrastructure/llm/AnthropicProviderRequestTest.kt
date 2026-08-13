package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.DEFAULT_MAX_TOKENS
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.RRN_IN_SOURCE
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.TEST_API_KEY
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.conversionPrompt
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.parse
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.settings
import kr.easydoc.infrastructure.llm.AnthropicTestSupport.successBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **와이어에 무엇이 실리는가**를 확인한다. 어댑터가 만든 실제 HTTP 요청을 스텁 서버가
 * 받아 그대로 기록하므로, 직렬화·헤더 조립·문자 인코딩까지 전부 이 검사를 통과한다.
 */
class AnthropicProviderRequestTest {
    private lateinit var server: StubAnthropicServer

    @BeforeEach
    fun start() {
        server = StubAnthropicServer()
        server.replyWith(body = successBody())
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun provider(effort: AnthropicEffort? = null) =
        AnthropicProvider(settings(baseUrl = server.baseUrl, effort = effort))

    @Test
    @DisplayName("요청 본문에는 마스킹된 텍스트만 실린다")
    fun `원문 개인정보가 전송되지 않는다`() {
        provider().complete(conversionPrompt())

        val wire = server.singleRequest().wireDump()
        assertThat(wire)
            .withFailMessage("마스킹 전 주민등록번호가 그대로 전송됐다 — 보안 불변식(마스킹 선행) 위반")
            .doesNotContain(RRN_IN_SOURCE)
        assertThat(wire).contains("[[주민등록번호1]]")
    }

    @Test
    @DisplayName("한국어 본문이 UTF-8 로 왕복한다")
    fun `한국어가 깨지지 않는다`() {
        provider().complete(conversionPrompt("복지 급여 신청 안내문입니다."))

        val body = parse(server.singleRequest().body)
        val content =
            body
                .path("messages")
                .path(0)
                .path("content")
                .stringValue("")
        assertThat(content).contains("복지 급여 신청 안내문입니다.")
    }

    @Test
    @DisplayName("샘플링 파라미터와 thinking 은 보내지 않는다")
    fun `보내지 않기로 한 필드가 없다`() {
        provider().complete(conversionPrompt())

        val body = parse(server.singleRequest().body)
        // temperature/top_p/top_k: 현행 Claude 모델이 지원하지 않아 보내면 400 이다.
        assertThat(body.has("temperature")).isFalse()
        assertThat(body.has("top_p")).isFalse()
        assertThat(body.has("top_k")).isFalse()
        // thinking: 미지정이 '끄기'가 아니라 적응형 사고 기본 켜기다. budget_tokens 는 400.
        assertThat(body.has("thinking")).isFalse()
    }

    @Test
    @DisplayName("출력 상한 기본값은 16,000 이다")
    fun `max_tokens 기본값을 보낸다`() {
        provider().complete(conversionPrompt())

        val body = parse(server.singleRequest().body)
        assertThat(body.path("max_tokens").asInt(0)).isEqualTo(DEFAULT_MAX_TOKENS)
        assertThat(DEFAULT_MAX_TOKENS).isEqualTo(16_000)
    }

    @Test
    @DisplayName("호출별 출력 상한을 지정할 수 있다")
    fun `옵션의 max_tokens 를 보낸다`() {
        provider().complete(conversionPrompt(), LlmOptions(maxTokens = 4_096))

        assertThat(parse(server.singleRequest().body).path("max_tokens").asInt(0)).isEqualTo(4_096)
    }

    @Test
    @DisplayName("effort 미설정이면 output_config 를 아예 보내지 않는다")
    fun `effort 가 없으면 필드가 없다`() {
        provider(effort = null).complete(conversionPrompt())

        assertThat(parse(server.singleRequest().body).has("output_config")).isFalse()
    }

    @Test
    @DisplayName("effort 설정 시 output_config.effort 로 보낸다")
    fun `effort 를 보낸다`() {
        provider(effort = AnthropicEffort.XHIGH).complete(conversionPrompt())

        val body = parse(server.singleRequest().body)
        assertThat(body.path("output_config").path("effort").stringValue("")).isEqualTo("xhigh")
    }

    @Test
    @DisplayName("모델과 system·messages 를 Messages API 모양으로 보낸다")
    fun `요청 모양이 맞다`() {
        provider().complete(conversionPrompt())

        val request = server.singleRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/messages")

        val body = parse(request.body)
        assertThat(body.path("model").stringValue("")).isEqualTo(DEFAULT_ANTHROPIC_MODEL)
        assertThat(body.path("system").stringValue("")).contains("[변환 규칙]")
        assertThat(
            body
                .path("messages")
                .path(0)
                .path("role")
                .stringValue(""),
        ).isEqualTo("user")
    }

    @Test
    @DisplayName("인증·버전·콘텐츠 타입 헤더를 붙인다")
    fun `헤더를 붙인다`() {
        provider().complete(conversionPrompt())

        val request = server.singleRequest()
        assertThat(request.header("x-api-key")).isEqualTo(TEST_API_KEY)
        assertThat(request.header("anthropic-version")).isEqualTo(ANTHROPIC_API_VERSION)
        assertThat(request.header("content-type")).contains("application/json")
    }

    @Test
    @DisplayName("API 키는 x-api-key 헤더 밖으로 나가지 않는다")
    fun `키가 본문이나 경로에 실리지 않는다`() {
        provider().complete(conversionPrompt())

        val request = server.singleRequest()
        assertThat(request.body).doesNotContain(TEST_API_KEY)
        assertThat(request.path).doesNotContain(TEST_API_KEY)
        // 요청 전체(요청 라인 + 모든 헤더 + 본문)에서 키가 나타나는 횟수는 정확히 1이어야 한다 —
        // 그 하나가 x-api-key 헤더다. 본문만 검사하면 다른 헤더로 새는 경로를 놓친다.
        assertThat(request.wireDump().split(TEST_API_KEY)).hasSize(2)
    }

    @Test
    @DisplayName("API 키가 없으면 요청 자체를 보내지 않는다")
    fun `키 미설정은 설정 오류다`() {
        val provider = AnthropicProvider(settings(baseUrl = server.baseUrl).copy(apiKey = Secret.EMPTY))

        assertThatThrownBy { provider.complete(conversionPrompt()) }
            .isInstanceOf(ConfigurationException::class.java)

        assertThat(server.received)
            .withFailMessage("키가 없는데도 요청이 나갔다 — 문서 본문이 인증 없이 외부로 전송된 것이다")
            .isEmpty()
    }

    @Test
    @DisplayName("잘못된 effort 값은 호출 전에 막는다")
    fun `effort 오타를 생성 시점에 막는다`() {
        assertThatThrownBy { AnthropicEffort.from("hihg") }
            .isInstanceOf(ConfigurationException::class.java)

        assertThat(AnthropicEffort.from(null)).isNull()
        assertThat(AnthropicEffort.from("  ")).isNull()
        assertThat(AnthropicEffort.from("MAX")).isEqualTo(AnthropicEffort.MAX)
    }

    @Test
    @DisplayName("toString 에 API 키가 실리지 않는다")
    fun `toString 이 키를 감춘다`() {
        val rendered = provider().toString()

        assertThat(rendered).doesNotContain(TEST_API_KEY)
        assertThat(rendered).contains(DEFAULT_ANTHROPIC_MODEL)
    }
}

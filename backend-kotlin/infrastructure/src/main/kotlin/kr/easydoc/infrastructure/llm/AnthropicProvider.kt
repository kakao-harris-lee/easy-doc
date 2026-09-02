package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

// Anthropic Messages API provider 구현체.
//
// ## no-training 계약 (CLAUDE.md 보안·데이터 규칙)
//
// **상용 API 입력 데이터 학습 미사용(no-training) 조건을 전제로 한다.** 사용자 문서
// 본문이 이 파일을 통해 국외 사업자에게 전송되므로, 그 전제가 깨지면 개인정보 처리방침과
// B2G 계약이 함께 깨진다. 계약·약관이 바뀌면 이 주석과 함께 재확인할 것.
//
// ## 벤더 격리 (CLAUDE.md 아키텍처 규칙 1)
//
// 벤더 어휘("end_turn", "max_tokens", "x-api-key", JSON 필드 이름)가 등장해도 되는
// 파일은 여기뿐이다. 밖으로 나가는 것은 core 의 공통 타입([LlmCompletion])뿐이고,
// 벤더 오류 타입(RestClientException·JacksonException)도 여기서 도메인 예외로 바뀐다.
//
// **벤더 SDK 를 쓰지 않고 Spring RestClient 를 쓰는 이유**는 version catalog 주석에 있다.
// 요약하면 (1) SDK 전이 의존성이 BOM 밖에서 따라 들어오는 것을 막고, (2) SDK 내장
// 재시도가 워커 재시도와 겹쳐 호출 수를 늘리는 것을 막기 위해서다.

const val ANTHROPIC_PROVIDER_NAME: String = "anthropic"

/** Messages API 버전 헤더. 날짜 문자열이지만 릴리스 핀이지 오늘 날짜가 아니다. */
internal const val ANTHROPIC_API_VERSION: String = "2023-06-01"

internal const val ANTHROPIC_BASE_URL: String = "https://api.anthropic.com"

internal const val ANTHROPIC_MESSAGES_PATH: String = "/v1/messages"

/** 기본 모델. 운영에서는 `easydoc.llm.model` 설정으로 교체할 수 있다. */
const val DEFAULT_ANTHROPIC_MODEL: String = "claude-sonnet-5"

/**
 * [DEFAULT_ANTHROPIC_MODEL](`claude-sonnet-5`) 의 **동기 Messages API** 최대 출력 토큰.
 *
 * 출처: Anthropic 공식 모델 문서(2026-09-02 확인) — https://platform.claude.com/docs/en/models/overview
 * ("Max output: 128K tokens", Claude Sonnet 5). 같은 문서는 Message Batches API 가
 * `output-300k-2026-03-24` 베타 헤더로 300K 까지 지원한다고 밝히지만, 이 어댑터는
 * 동기 Messages API 만 호출하므로([AnthropicProvider.execute] 참고) 배치 한도는 쓰지 않는다.
 *
 * **기본 모델만 다룬다.** `easydoc.llm.model` 로 다른 모델을 지정하면 그 모델의 한도는
 * 신뢰성 있게 알 수 없다(`model` 은 자유 문자열이고, 모델별 한도 표는 조용히 낡는다) —
 * [LlmProviderConfiguration] 이 이 상수를 모델 미지정일 때만 검사에 쓰는 이유다.
 */
const val ANTHROPIC_DEFAULT_MODEL_MAX_OUTPUT_TOKENS: Int = 128_000

/** Anthropic 전용 읽기 타임아웃. */
val ANTHROPIC_READ_TIMEOUT: Duration = Duration.ofSeconds(120)

/** 연결 타임아웃. 연결 자체가 10초 넘게 안 되면 재시도가 답이지 기다림이 답이 아니다. */
val ANTHROPIC_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

/** `output_config.effort` 가 받는 값. 미설정 시 API 기본은 high 다. */
enum class AnthropicEffort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
    ;

    /** 전송 표기(소문자). */
    internal val wire: String get() = name.lowercase()

    companion object {
        /** 설정 문자열을 enum 으로 바꾼다. `null`·공백이면 `null`(= 파라미터를 보내지 않음). */
        fun from(value: String?): AnthropicEffort? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.wire == value.lowercase() }
                ?: throw ConfigurationException(
                    "지원하지 않는 effort 값입니다 (가능: ${entries.joinToString(", ") { it.wire }})",
                )
        }
    }
}

/** [AnthropicProvider] 설정. */
data class AnthropicSettings(
    val apiKey: Secret = Secret.EMPTY,
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    val effort: AnthropicEffort? = null,
    val baseUrl: String = ANTHROPIC_BASE_URL,
    val connectTimeout: Duration = ANTHROPIC_CONNECT_TIMEOUT,
    val readTimeout: Duration = ANTHROPIC_READ_TIMEOUT,
)

/** Anthropic Messages API 구현체. */
class AnthropicProvider(private val settings: AnthropicSettings) : LlmProvider {
    override val name: String = ANTHROPIC_PROVIDER_NAME

    private val json = JsonMapper.builder().build()

    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(settings.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build(),
                ).apply { setReadTimeout(settings.readTimeout) },
            ).build()

    /**
     * 설정을 그대로 노출하지 않는다. [Secret] 이 이미 막고 있지만, 이 클래스가 언젠가
     * `data class` 가 되거나 필드가 늘어날 때를 대비해 한 겹 더 좁혀 둔다.
     */
    override fun toString(): String = "AnthropicProvider(model=${settings.model}, effort=${settings.effort})"

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        // 키 검증을 생성자가 아니라 호출 시점에 한다: 키가 없어도 앱은 뜨고
        // (`EasyDocProperties` KDoc "기동은 막지 않는다"), 그 값이 필요한 요청만 거절한다.
        if (settings.apiKey.isBlank()) {
            throw ConfigurationException("anthropic API 키가 설정되지 않았습니다")
        }

        val payload = json.writeValueAsString(requestBody(prompt, options))
        val raw = post(payload)
        return parse(raw)
    }

    /** 본문을 **UTF-8 바이트로 직접** 실어 보낸다. */
    private fun post(payload: String): String =
        execute(payload)?.toString(StandardCharsets.UTF_8) ?: throw failure("응답 본문 없음")

    private fun execute(payload: String): ByteArray? =
        try {
            client
                .post()
                .uri(ANTHROPIC_MESSAGES_PATH)
                .header("x-api-key", settings.apiKey.reveal())
                .header("anthropic-version", ANTHROPIC_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toByteArray(StandardCharsets.UTF_8))
                .retrieve()
                .onStatus({ status -> status.isError }) { _, errorResponse ->
                    throw failure("HTTP ${errorResponse.statusCode.value()}")
                }.body(ByteArray::class.java)
        } catch (exc: RestClientException) {
            // 타임아웃·연결 실패·프로토콜 오류. 예외 메시지에는 URL·헤더가 실릴 수 있으므로
            // 그대로 쓰지 않고 **타입 이름만** 남긴다.
            //
            // 원인 예외를 cause로 매달지 않는다. Spring의 HttpClientErrorException은
            // **응답 본문을 메시지에 담는다.** 체인을 매달면 스택 트레이스를 한 번 찍는 것만으로
            // 벤더가 되비춘 프롬프트가 로그로 나간다. 도메인 예외 계층이 메시지 전용인 것도
            // 같은 근거다(`DomainExceptions.kt` 의 메시지 규약).
            throw failure(exc::class.java.simpleName)
        }

    private fun requestBody(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): ObjectNode {
        val root = json.createObjectNode()
        root.put("model", settings.model)
        root.put("max_tokens", options.maxTokens)
        root.put("system", prompt.system)
        root
            .putArray("messages")
            .addObject()
            .put("role", "user")
            .put("content", prompt.user)
        settings.effort?.let { effort ->
            root.putObject("output_config").put("effort", effort.wire)
        }
        return root
    }

    /** 응답을 공통 타입으로 옮긴다. **관측을 먼저 끝내고 그다음에 만든다.** */
    private fun parse(raw: String): LlmCompletion {
        val node = readTree(raw)
        // 관측 먼저. 이 셋이 확정된 뒤에야 조립한다.
        val inputTokens = requireTokenCount(node, "input_tokens")
        val outputTokens = requireTokenCount(node, "output_tokens")
        val finishReason = requireFinishReason(node)
        val model = requireModel(node)
        val text = extractText(node)

        return LlmCompletion(
            text = text,
            provider = name,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            finishReason = finishReason,
        )
    }

    private fun readTree(raw: String): JsonNode =
        try {
            json.readTree(raw)
        } catch (exc: JacksonException) {
            // 파싱 실패 메시지에는 본문 조각이 그대로 실린다(어느 위치의 어떤 토큰인지).
            // 그래서 메시지도 cause 도 버리고 예외 **타입 이름**만 남긴다.
            throw failure("응답 형식 오류: ${exc::class.java.simpleName}")
        }
}

// ── 순수 매핑 함수 ────────────────────────────────────────────────────────────────
// 인스턴스 상태를 쓰지 않는 것들은 파일 private 로 내린다. 클래스 밖에 있다는 사실 자체가
// "설정도 HTTP 클라이언트도 보지 않는다"는 선언이라, 응답 해석 규칙을 읽을 때 확인할 범위가
// 좁아진다.

/** 실패 사유를 도메인 예외로 만든다. */
private fun failure(reason: String) = LlmProviderException("anthropic 호출 실패 ($reason)")

/** 본문을 꺼낸다. **비어 있어도 던지지 않는다.** */
private fun extractText(node: JsonNode): String =
    node
        .path("content")
        .filter { block -> block.path("type").stringValue("") == "text" }
        .joinToString("") { block -> block.path("text").stringValue("") }

/**
 * 모델 이름은 **응답에서 관측한다.** 설정값으로 대신 채우지 않는다 — 별칭 해석과 폴백이
 * 있는 한 설정값은 주장이지 증거가 아니다([LlmCompletion.model] KDoc).
 */
private fun requireModel(node: JsonNode): String =
    node.path("model").stringValue("").ifEmpty { throw failure("응답에 모델 이름이 없습니다") }

/** 토큰 수를 **필수로** 읽는다. 누락·null·비정수·음수는 전부 실패다. */
private fun requireTokenCount(
    node: JsonNode,
    field: String,
): Int {
    val value = node.path("usage").path(field)
    if (!value.isIntegralNumber) throw failure("응답 usage.$field 가 정수가 아닙니다")
    val count = value.asInt()
    if (count < 0) throw failure("응답 usage.$field 가 음수입니다")
    return count
}

/** 종료 사유를 **필수로** 읽는다. */
private fun requireFinishReason(node: JsonNode): LlmFinishReason {
    val raw = node.path("stop_reason").stringValue("")
    if (raw.isEmpty()) throw failure("응답에 stop_reason 이 없습니다")
    return finishReason(raw)
}

/** 벤더 어휘를 우리 어휘로 정규화한다. 이 매핑이 이 파일 밖으로 나가지 않게 한다. */
private fun finishReason(raw: String): LlmFinishReason =
    when (raw) {
        "end_turn" -> LlmFinishReason.END_TURN

        "max_tokens" -> LlmFinishReason.MAX_TOKENS

        "stop_sequence" -> LlmFinishReason.STOP_SEQUENCE

        "refusal" -> LlmFinishReason.REFUSAL

        // 여기 오는 것은 **벤더가 우리가 모르는 값을 준** 경우뿐이다. 누락은 위에서 막았다.
        else -> LlmFinishReason.OTHER
    }

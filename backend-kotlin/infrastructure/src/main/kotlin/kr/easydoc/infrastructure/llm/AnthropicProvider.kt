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
// 원본: app/llm/anthropic_provider.py
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

/** 원본: `AnthropicProvider.name` ClassVar. */
const val ANTHROPIC_PROVIDER_NAME: String = "anthropic"

/** Messages API 버전 헤더. 날짜 문자열이지만 릴리스 핀이지 오늘 날짜가 아니다. */
internal const val ANTHROPIC_API_VERSION: String = "2023-06-01"

internal const val ANTHROPIC_BASE_URL: String = "https://api.anthropic.com"

internal const val ANTHROPIC_MESSAGES_PATH: String = "/v1/messages"

/**
 * 기본 모델. 원본: `app/llm/anthropic_provider.py` 의 `model: str = "claude-sonnet-5"`.
 *
 * **여기서 모델을 고르지 않는다.** 벤더·모델 선택은 골든셋 벤치마크가 정하는 품질
 * 결정이고(master-plan 3.1), 지금 값을 바꾸면 그것은 포팅이 아니라 품질 변경이라
 * 통과율 비교의 기준선이 흔들린다. 운영에서 바꿀 때는 코드가 아니라 설정
 * (`easydoc.llm.model`)으로 덮어쓴다.
 */
const val DEFAULT_ANTHROPIC_MODEL: String = "claude-sonnet-5"

/**
 * Anthropic 전용 읽기 타임아웃.
 *
 * 원본: `ANTHROPIC_TIMEOUT_SECONDS = 120.0`. 공용 기본값(Python `provider.py` 의 60초)은
 * 사고 토큰을 쓰는 현행 Claude 모델에 짧다 — 1차 벤치마크 실측 지연이 중앙값 33.5초·
 * 최대 61.1초였고, `max_tokens` 를 16,000 으로 올려 사고 여유가 늘면 더 길어질 수 있다.
 * OpenAI 는 같은 골든셋에서 중앙값 7.4초라 이 값은 **Anthropic 어댑터에만** 둔다.
 */
val ANTHROPIC_READ_TIMEOUT: Duration = Duration.ofSeconds(120)

/** 연결 타임아웃. 연결 자체가 10초 넘게 안 되면 재시도가 답이지 기다림이 답이 아니다. */
val ANTHROPIC_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * `output_config.effort` 가 받는 값. 미설정 시 API 기본은 high 다.
 *
 * 원본: `app/llm/anthropic_provider.py::Effort`. 낮출수록 사고 토큰·지연이 줄고,
 * 높일수록 는다.
 *
 * **effort 는 Anthropic 에만 있는 파라미터다** — OpenAI 구현체에는 인자 자체가 없어
 * 설정값이 모델에 닿지 않는다(`app/llm/factory.py::applied_effort`). 그래서 공통
 * [LlmOptions] 가 아니라 이 어댑터의 설정으로 둔다. 무엇으로 잰 수치인지를 기록하는
 * 쪽(골든셋 기준선 지문)도 같은 구분을 필요로 하므로, "적용되는 경우"의 정의가 이 타입의
 * 존재 자체와 일치한다.
 */
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
        /**
         * 설정 문자열을 enum 으로 바꾼다. `null`·공백이면 `null`(= 파라미터를 보내지 않음).
         *
         * 잘못된 값은 **호출 시점의 HTTP 400 이 아니라 여기서** 막는다 — 원본이 생성자에서
         * 막은 것과 같은 이유다. 워커·벤치마크가 전건 실패한 뒤에야 오타를 알게 되는
         * 상황을 피한다.
         */
        fun from(value: String?): AnthropicEffort? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.wire == value.lowercase() }
                ?: throw ConfigurationException(
                    "지원하지 않는 effort 값입니다 (가능: ${entries.joinToString(", ") { it.wire }})",
                )
        }
    }
}

/**
 * [AnthropicProvider] 설정.
 *
 * `data class` 인데도 [apiKey] 가 안전한 이유는 타입이 [Secret] 이기 때문이다 —
 * 기본 `toString()` 이 모든 필드를 찍어도 키 자리에는 마스킹 문자열만 나온다.
 */
data class AnthropicSettings(
    val apiKey: Secret = Secret.EMPTY,
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    val effort: AnthropicEffort? = null,
    val baseUrl: String = ANTHROPIC_BASE_URL,
    val connectTimeout: Duration = ANTHROPIC_CONNECT_TIMEOUT,
    val readTimeout: Duration = ANTHROPIC_READ_TIMEOUT,
)

/**
 * Anthropic Messages API 구현체.
 *
 * ## 보내지 않는 것들 (실측으로 확정된 결정)
 *
 * - **temperature 를 보내지 않는다.** 현행 Claude 모델은 샘플링 파라미터
 *   (temperature/top_p/top_k)를 지원하지 않아 기본값 외 값을 보내면 400 을 돌려준다.
 *   출력 성향은 프롬프트로 제어한다.
 * - **thinking 을 보내지 않는다.** 미지정은 '사고 끄기'가 아니라 **적응형 사고 기본
 *   켜기**를 뜻하고, 이 모델 계열은 `budget_tokens` 를 받으면 400 이다. 사고 깊이는
 *   `output_config.effort` 로 조절하고, 사고 토큰이 쓸 여유는 `max_tokens` 로 준다
 *   (그래서 기본 상한이 16,000 이다 — `DEFAULT_MAX_TOKENS` KDoc).
 * - **output_config 는 effort 가 설정됐을 때만 보낸다.** 미설정이면 필드 자체를 빼서
 *   API 기본값(high)을 그대로 쓴다.
 *
 * ## 재시도하지 않는다 — 책임은 한 계층만 갖는다
 *
 * 원본은 SDK 에 `max_retries=2` 를 넘겼고, 워커도 따로 재시도했다. 계획 §4.6 이 지적한
 * 겹침이 정확히 그 형태다: 두 계층이 각자 재시도하면 "문서당 최대 2회"라는 제품 계약
 * (`MAX_LLM_CALLS_PER_CONVERSION`)이 메트릭에서 사라지고, §5 Phase 7 의 즉시 중단 기준인
 * **중복 LLM 호출**에 그대로 걸린다. 그래서 이 어댑터는 **한 번 부르고 실패는 그대로
 * 올린다.** 재시도 정책은 작업 큐(worker)가 소유한다.
 *
 * ## 로그를 남기지 않는다
 *
 * 이 클래스에는 로거가 없다. 여기서 로그를 남기면 남길 수 있는 것이 프롬프트·응답·헤더뿐인데
 * 그것이 전부 금지 대상이다(CLAUDE.md: 로그에 문서 본문·개인정보 금지). 관측은 호출부가
 * conversion id·상태·시도 횟수·failure code 로 한다(계획 §4.4).
 */
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

    /**
     * 본문을 **UTF-8 바이트로 직접** 실어 보낸다.
     *
     * `String` 으로 넘기면 `StringHttpMessageConverter` 의 기본 charset 에 결과가 달려
     * 있는데, 그 값은 Spring 버전에 따라 달라진 이력이 있다. 한국어가 본문의 전부인
     * 페이로드에서 그 불확실성은 "어느 날 조용히 깨지는" 종류의 위험이다. JSON 은 규격상
     * UTF-8 이므로 여기서 못 박는다.
     */
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
            // 원인 예외를 cause 로 매달지 않는다 — 원본은 `from exc` 로 체인을 유지했지만
            // (`app/llm/anthropic_provider.py`), Spring 의 HttpClientErrorException 은
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

    private fun parse(raw: String): LlmCompletion {
        val node = readTree(raw)
        val finishReason = finishReason(node.path("stop_reason").stringValue(""))
        val usage = node.path("usage")
        return LlmCompletion(
            text = extractText(node),
            provider = name,
            model = requireModel(node),
            inputTokens = usage.path("input_tokens").asInt(0),
            outputTokens = usage.path("output_tokens").asInt(0),
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

/**
 * 실패 사유를 도메인 예외로 만든다.
 *
 * 메시지에 담기는 것은 **벤더명과 사유 요약까지**다. 응답 본문·헤더·URL 은 담지 않는다 —
 * 벤더가 오류 본문에 우리가 보낸 프롬프트를 되비추는 경우가 있어, 그것을 메시지에 실으면
 * 문서 본문이 예외를 타고 로그로 나간다.
 */
private fun failure(reason: String) = LlmProviderException("anthropic 호출 실패 ($reason)")

/**
 * 본문을 꺼낸다. **비어 있어도 던지지 않는다.**
 *
 * ## 던지던 것을 그만둔 이유 (교차 종합 C-08)
 *
 * 이전 판은 본문이 비면 여기서 [LlmEmptyResultException] 을 던졌다. 그런데 그 순간
 * **[LlmCompletion] 이 만들어지지 않아 `finishReason` 과 `usage` 가 함께 사라진다.**
 * 결과가 둘이었다.
 *
 * 1. **출력 상한에서 잘려 본문이 비어 온 응답**(`stop_reason=max_tokens` + 빈 content)이
 *    변환 계층에 `EMPTY_RESULT` 로 보고됐다. 요구는 `TRUNCATED` 다 — 사용자가 취할 조치가
 *    다르다(문서를 나눠 올리기 vs 다시 시도).
 * 2. 예외에는 토큰 수가 실리지 않아 **최종 사용량이 두 호출의 합보다 적게** 보고됐다.
 *    보정 호출이 그렇게 끝나면 그 비용이 원가에서 통째로 빠진다.
 *
 * 그래서 어댑터는 **사실만 보고한다** — 본문(빈 문자열일 수 있음)·종료 사유·사용량.
 * 그것을 실패로 볼지, 어떤 실패로 볼지는 변환 계층의 정책이다. `truncated` 를 두고 이미
 * 같은 규약을 적어 두었는데(*"provider 가 보고하는 것은 사실뿐이고 정책은 변환 쪽이
 * 정한다"*), 빈 본문만 그 규약 밖에 있었다.
 *
 * **거절(`refusal`)의 구분은 사라지지 않는다.** 어댑터가 던져서 가르던 것을
 * [LlmFinishReason.REFUSAL] 이 값으로 나른다 — 오히려 이쪽이 더 낫다. 예외 메시지는
 * 문자열이라 분기 조건으로 쓸 수 없었고, 실제로 변환 계층은 그것을 읽지 못했다.
 *
 * 사고(thinking) 블록만 온 경우도 같다 — 빈 본문 + `end_turn` 으로 나가고, 변환 계층이
 * 후처리 뒤 비었음을 보고 `EMPTY_RESULT` 로 판정한다.
 */
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

/** 벤더 어휘를 우리 어휘로 정규화한다. 이 매핑이 이 파일 밖으로 나가지 않게 한다. */
private fun finishReason(raw: String): LlmFinishReason =
    when (raw) {
        "end_turn" -> LlmFinishReason.END_TURN
        "max_tokens" -> LlmFinishReason.MAX_TOKENS
        "stop_sequence" -> LlmFinishReason.STOP_SEQUENCE
        "refusal" -> LlmFinishReason.REFUSAL
        else -> LlmFinishReason.OTHER
    }

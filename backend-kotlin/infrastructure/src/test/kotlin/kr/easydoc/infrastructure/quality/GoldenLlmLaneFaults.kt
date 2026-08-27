package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.LlmProviderException

/**
 * 레인이 붙잡은 실패 한 건의 **원인**.
 *
 * `ConversionFailureKind.PROVIDER_ERROR` 는 429·타임아웃·5xx 를 한 값으로 뭉갠다. 원인을 모르면
 * 「모델이 못한 것」과 「인프라가 흔들린 것」을 가를 수 없고, 그러면 통과율이 무엇을 뜻하는지
 * 말할 수 없다(2026-08-27 실측: 56건 중 17건이 이유 없는 `PROVIDER_ERROR`). 그래서 뭉개지기
 * 전에 여기서 한 번 붙잡는다.
 *
 * **담지 않는 것**: 문서 본문, 프롬프트, 응답 본문, API 키, 예외 스택 트레이스
 * (프로젝트 CLAUDE.md 관측 규칙). 담는 것은 어댑터가 이미 안전하게 좁혀 둔 한 조각 —
 * HTTP 상태 코드 또는 전송 예외의 클래스 이름 — 뿐이다.
 */
internal data class LaneFault(
    /** 사람이 읽는 원인 표시. `HTTP 429` 또는 전송 예외 클래스 이름. */
    val label: String,
    /** HTTP 상태. 상태로 실패한 것이 아니면 `null`. */
    val status: Int?,
    /** 다시 불러 볼 만한 일시적 실패인가. */
    val transient: Boolean,
)

/** 어댑터 예외에서 원인을 되살린다. */
internal object LaneFaults {
    /**
     * 어댑터는 실패를 `"anthropic 호출 실패 (HTTP 429)"` 처럼 **괄호 안 한 조각**으로 적고, 그
     * 조각에는 URL·헤더·본문·키가 들어가지 않는다(`AnthropicProvider.failure` 주석). 예외 타입에는
     * 상태가 없으므로 레인이 볼 수 있는 것은 이 문구뿐이다.
     *
     * 문구 모양에 기대는 것이 이 함수의 값이자 위험이다. 그래서 [GoldenLlmLaneProviderTest] 가
     * **실제 어댑터**를 스텁 서버에 물려 이 해석을 확인한다 — 어댑터가 문구를 바꾸면 그 테스트가
     * 깨지지, 유료 레인이 조용히 오분류하지 않는다.
     */
    fun of(exc: LlmProviderException): LaneFault {
        val label =
            REASON
                .find(exc.message.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
                .ifEmpty { UNKNOWN }
        val status =
            HTTP_STATUS
                .find(label)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return LaneFault(label = label, status = status, transient = transient(label, status))
    }

    private fun transient(
        label: String,
        status: Int?,
    ): Boolean =
        when {
            status != null -> status in RETRYABLE_STATUSES || status in SERVER_ERROR_FLOOR..SERVER_ERROR_CEILING

            // 어댑터가 **맨 예외 클래스 이름만** 남기는 자리는 전송 계층 catch 하나뿐이다
            // (`catch (exc: RestClientException) { throw failure(exc::class.java.simpleName) }`).
            // 연결 실패·읽기 타임아웃이 이 모양으로 오고, 그것들은 다시 불러 볼 값이 있다.
            // 응답 해석 실패는 `"응답 형식 오류: JacksonException"` 처럼 접두사가 붙어 여기 걸리지
            // 않는다 — 같은 입력에 같은 결과가 오므로 다시 부르지 않는 것이 맞다.
            else -> EXCEPTION_NAME.matches(label)
        }

    /** 원인을 읽어내지 못했다. 다시 부르지 않는다 — 모르는 실패를 반복하면 비용만 는다. */
    private const val UNKNOWN: String = "원인 미상"

    private const val SERVER_ERROR_FLOOR: Int = 500

    private const val SERVER_ERROR_CEILING: Int = 599

    /** 서버가 「지금 말고 나중에」라고 말한 상태들. */
    private val RETRYABLE_STATUSES: Set<Int> = setOf(408, 425, 429)

    private val REASON: Regex = Regex("""\(([^()]*)\)\s*$""")

    private val HTTP_STATUS: Regex = Regex("""^HTTP (\d{3})$""")

    private val EXCEPTION_NAME: Regex = Regex("""^[A-Za-z][A-Za-z0-9_]*Exception$""")
}

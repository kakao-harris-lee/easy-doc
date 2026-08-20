package kr.easydoc.api.support

/**
 * **F3 축의 프로브 조립과 판정** — 관측 지점(MockMvc / 실제 소켓)이 둘이라 여기 한 벌만 둔다.
 *
 * 계약은 다섯 요청 필드에 길이·형식 Bean Validation 을 금지했다(F3). 그 성질은
 * 「그 필드의 거절이 **스키마 층에서 일어나지 않는다**」이고, 관측면은 응답 `detail` 의
 * 모양이다 — 스키마·바인딩 층이 거절하면 **배열**, 서비스·도메인 층이 거절하면 **문자열**
 * (계약 `ValidationFailed` 가 그 경계를 명시했다).
 *
 * ## 왜 판정을 한 곳에 두는가
 *
 * 관측 지점이 둘인데(`RequestFieldRejectionLayerTest` = 슬라이스,
 * `RequestFieldRejectionReachTest` = 컨테이너) 판정을 두 벌로 두면 한쪽만 고쳐지는 날
 * **두 축이 서로 다른 것을 재면서 둘 다 초록**이 된다. 이 저장소가 반복해 겪은 형태다.
 *
 * ## 프로브를 계약에서 유도한다 — 방향도 **측정한다**
 *
 * 필드 목록은 `x-request-field-constraints.fields[].field`, 경계는 그 항목의 `limit`,
 * 기대 문구는 `detail`, 측정 축은 `measured_on` 에서 온다. 그리고 **상한 필드인지 하한
 * 필드인지를 코드에 적지 않는다** — 길이 `limit-1`·`limit+1` 중 어느 쪽이 거절되는지를
 * 보고 판정한다(다섯 중 `password` 만 하한이고 나머지 넷은 상한이다).
 *
 * 그 측정된 방향이 **정규화 프로브의 모양까지 정한다**(아래 [divergentValue]).
 */
object RequestFieldProbes {
    /** 한 프로브의 관측. 관측 지점이 무엇이든 이 두 값으로 환원된다. */
    data class Observed(
        val status: Int,
        val detail: Any?,
    ) {
        val accepted: Boolean get() = status in SUCCESS_RANGE

        /** `detail` 이 배열인가. **F3 판정의 전부**다. */
        val arrayShaped: Boolean get() = detail is List<*>
    }

    /** 한 필드를 재고 나온 것. [problems] 가 비면 그 필드는 계약대로 판정됐다. */
    class Finding(
        val field: String,
        val arrayShaped: List<String>,
        val problems: List<String>,
    )

    /** 값의 모양. 이메일만 길이를 맞추면서 형식을 지켜야 한다. */
    enum class ProbeShape { EMAIL, PLAIN }

    /** 정규화가 걷어내는 잡음의 종류. */
    enum class Noise { LEADING_SPACE, CONTROL_CHAR }

    /**
     * 필드마다 다른 두 가지 — **값의 모양**과 **정규화가 걷어내는 잡음**.
     *
     * 잡음의 출처는 그 필드의 계약 `measured_on` 산문이다. `email` 은 *"앞뒤 공백 제거 +
     * 소문자화"*, `name` 은 *"제어문자 제거 + 앞뒤 공백 제거"*, `edited_text` 는
     * *"제어문자 제거"* 다. 산문을 파싱하지 않고 여기 적는 이유는 파싱이 문면 한 글자에
     * 묶이기 때문이고, 그 대가로 **잡음 선택이 계약 문면과 갈릴 수 있다**는 잔여를 안는다.
     */
    data class FieldShape(
        val shape: ProbeShape,
        val noise: Noise,
    )

    /**
     * 계약 필드 → 값 조립 규칙.
     *
     * **이 열거의 완전성은 열거가 지키지 않는다** — 각 탐지기의 「계약 필드 전부가 다뤄진다」
     * 케이스가 계약에서 읽은 집합과 정확 일치로 대조하고, 모르는 필드가 오면 [valueOf] 가
     * `error()` 로 끊는다.
     */
    val FIELD_SHAPES: Map<String, FieldShape> =
        mapOf(
            "SignupRequest.email" to FieldShape(ProbeShape.EMAIL, Noise.LEADING_SPACE),
            "SignupRequest.password" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "DocumentTextRequest.text" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "WorkspaceNameRequest.name" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
        )

    /** 계약이 지목한 요청 필드 이름 전부. */
    fun contractFields(): List<String> =
        ContractSpec
            .list("x-request-field-constraints", "fields")
            .filterIsInstance<Map<*, *>>()
            .map { it["field"]?.toString() ?: error("fields[] 항목에 field 가 없다") }

    /** 그 필드에 계약이 선언한 문구들. 하나짜리도 목록으로 편다 — 소비자가 갈래를 몰라도 되게. */
    fun declaredDetails(field: String): List<String> =
        when (val detail = ContractSpec.requestFieldConstraint(field).detail) {
            is String -> listOf(detail)
            is List<*> -> detail.map { it.toString() }
            else -> error("$field 의 detail 이 문자열도 목록도 아니다: $detail")
        }

    /** 길이 [length] 짜리 값. 이메일만 형식을 지키며 길이를 맞춘다. */
    fun valueOf(
        field: String,
        length: Int,
    ): String =
        when (shapeOf(field).shape) {
            ProbeShape.EMAIL -> {
                val fillerLength = length - EMAIL_DOMAIN.length
                require(fillerLength > 0) { "이메일 프로브 길이가 도메인부보다 짧다: $length" }
                "u${counter++}".padEnd(fillerLength, 'a').take(fillerLength) + EMAIL_DOMAIN
            }

            // **길이를 지키면서 값은 매번 다르게** 만든다. 첫 글자만 회전시킨다.
            //
            // 왜 필요한가: `WorkspaceNameRequest.name` 은 같은 소유자 안에서 **이름이 유일**해야
            // 한다. 경계 프로브(길이 50)와 정규화 프로브(정규화 후 길이 50)가 같은 문자열이면
            // 두 번째가 409(이름 중복)로 거절되고, 그러면 이 축이 재려던 것 대신 **다른 계약
            // 조항**을 재게 된다(실측: 컨테이너 축에서 정확히 그 일이 났다).
            //
            // 프로브마다 계정을 새로 만드는 갈래는 고르지 않았다 — 실제 Argon2 가 도는 축에서
            // 비싸고, 무엇보다 「값이 겹치면 안 된다」를 계정 격리로 우회하면 그 전제가 조용히
            // 사라진다(슬라이스 축이 그 상태였다).
            ProbeShape.PLAIN -> {
                if (length <= 0) {
                    ""
                } else {
                    val marker = PLAIN_MARKERS[counter++ % PLAIN_MARKERS.length]
                    marker + FILLER_CHAR.repeat(length - 1)
                }
            }
        }

    /**
     * **원시 길이는 `baseLength + noise`, 정규화 후 길이는 `baseLength`** 인 값.
     *
     * 계약이 필드마다 다른 측정 축을 정했고(`measured_on`), 이 값이 그 축을 **행동으로**
     * 가른다 — 정규화 후를 재는 필드에서는 통과하고 원시 값을 재는 필드에서는 거절된다.
     * 구현이 정규화를 한 곳에 몰아 두면 이 프로브만 뒤집히고 나머지는 전부 통과한다
     * (계약 명세가 DC-11 을 「가장 조용히 깨지는 자리」로 부른 이유가 그것이다).
     */
    fun divergentValue(
        field: String,
        baseLength: Int,
        noise: Int,
    ): String {
        val base = valueOf(field, baseLength)
        // 이메일은 잡음을 **앞**에 붙인다 — 도메인부 뒤에 붙이면 형식이 깨져 길이 축이 아니라
        // 형식 규칙이 먼저 걸린다(S-5 가 쓰는 형태와 같다).
        return when (shapeOf(field).noise) {
            Noise.LEADING_SPACE -> " ".repeat(noise) + base
            Noise.CONTROL_CHAR -> base + CONTROL_CHAR.repeat(noise)
        }
    }

    /**
     * 한 필드를 재고 [Finding] 을 만든다. [probe] 는 값으로 요청을 보내고 관측을 돌려준다.
     *
     * 판정 다섯 갈래를 한 곳에서 한다.
     *
     * 1. 길이 **정확히 `limit`** 은 통과한다(다섯 필드 전부 경계 포함이다 — 상한이면 「이하」,
     *    하한이면 「이상」).
     * 2. 이웃 두 값(`limit-1`·`limit+1`) 중 **정확히 하나**가 거절된다. 그 결과가 이 필드가
     *    상한인지 하한인지를 알려 준다 — 방향을 코드에 적지 않는 이유다.
     * 3. 그 거절은 422 이고 `detail` 이 **문자열**이며 값이 계약 선언 문구 중 하나다.
     * 4. **정규화 프로브**가 계약 `measured_on` 축대로 갈린다(위 [divergentValue]).
     * 5. 계약이 문구를 **둘 이상** 선언한 필드에서는 그만큼의 **서로 다른** 거절 문구가
     *    실제로 관측된다 — 그러지 않으면 상한 위반에 빈 값 문구가 나가도 통과한다.
     *
     * 어느 프로브에서든 배열 `detail` 이 나오면 [Finding.arrayShaped] 에 남는다.
     */
    @Suppress("CyclomaticComplexMethod")
    fun measure(
        field: String,
        probe: (String) -> Observed,
    ): Finding {
        val constraint = ContractSpec.requestFieldConstraint(field)
        val declared = declaredDetails(field)
        val problems = mutableListOf<String>()
        val arrayShaped = mutableListOf<String>()
        val rejectionDetails = mutableListOf<String>()

        fun observe(
            label: String,
            value: String,
        ): Observed {
            val observed = probe(value)
            if (observed.arrayShaped) arrayShaped += label
            if (!observed.accepted) {
                if (observed.status != UNPROCESSABLE) {
                    problems += "$label 의 거절이 422 가 아니다: ${observed.status}"
                }
                val detail = observed.detail
                if (detail is String) {
                    rejectionDetails += detail
                    if (detail !in declared) {
                        problems += "$label 의 detail 이 계약 선언 문구가 아니다: \"$detail\" (선언: $declared)"
                    }
                } else {
                    problems += "$label 의 detail 이 문자열이 아니다: $detail"
                }
            }
            return observed
        }

        val atLimit = observe("길이 ${constraint.limit}(경계)", valueOf(field, constraint.limit))
        if (!atLimit.accepted) {
            problems += "길이가 **정확히** 경계(${constraint.limit})인데 거절됐다(${atLimit.status}) — 경계 포함이 아니다"
        }

        val below = observe("길이 ${constraint.limit - 1}", valueOf(field, constraint.limit - 1))
        val above = observe("길이 ${constraint.limit + 1}", valueOf(field, constraint.limit + 1))
        val rejected = listOf(BELOW to below, ABOVE to above).filterNot { it.second.accepted }
        if (rejected.size != 1) {
            problems +=
                "경계 이웃 두 값 중 거절된 것이 ${rejected.size} 개다 — " +
                "정확히 하나여야 한다(아래→${below.status}, 위→${above.status})"
        }

        // ── 정규화 축 ──────────────────────────────────────────────────────
        // 방향을 **측정 결과에서** 가져온다. 상한 필드는 「경계 길이 + 잡음」이 원시로는
        // 초과이고 정규화 후에는 경계라 축을 가른다. 하한 필드는 반대로 「경계-1 + 잡음 1」이
        // 원시로는 경계이고 정규화 후에는 미달이라 축을 가른다.
        if (rejected.size == 1) {
            val upperBound = rejected.single().first == ABOVE
            val baseLength = if (upperBound) constraint.limit else constraint.limit - 1
            val noise = if (upperBound) DIVERGENCE_NOISE else 1
            val label = "정규화 프로브(원시 ${baseLength + noise} / 정규화 후 $baseLength)"
            val divergent = observe(label, divergentValue(field, baseLength, noise))
            val shouldAccept = if (upperBound) constraint.measuresNormalized else constraint.measuresRaw
            if (divergent.accepted != shouldAccept) {
                problems +=
                    "$label 이 계약 measured_on(${constraint.axis})과 어긋난다 — " +
                    "기대 ${if (shouldAccept) "통과" else "거절"} / 실제 ${divergent.status}"
            }
        }

        // ── 문구 갈래 ──────────────────────────────────────────────────────
        // 계약이 문구를 둘 이상 선언했으면 그만큼의 **서로 다른** 거절이 관측돼야 한다.
        // 빈 값 갈래가 그 두 번째다(`WorkspaceNameRequest.name`).
        if (declared.size > 1) {
            observe("빈 값", BLANK_VALUE)
            val distinct = rejectionDetails.toSet()
            if (distinct != declared.toSet()) {
                problems +=
                    "계약이 문구 ${declared.size} 갈래를 선언했는데 관측된 서로 다른 거절 문구는 $distinct 다 — " +
                    "한 문구가 두 갈래에 쓰이면 상한 위반에 빈 값 문구가 나가도 통과한다"
            }
        }

        return Finding(field, arrayShaped.toList(), problems.toList())
    }

    private fun shapeOf(field: String): FieldShape =
        FIELD_SHAPES[field] ?: error("$field 의 프로브 값 조립 규칙이 없다 — 계약에 필드가 늘었다면 여기에 더해라")

    /** 이메일 유일성이 필요한 자리가 쓰는 값. 길이 축과 무관하다. */
    fun uniqueEmail(): String = "probe${counter++}$EMAIL_DOMAIN"

    /** 빈 값 갈래. 공백만인 값은 정규화하면 비어 있다. */
    const val BLANK_VALUE: String = "   "

    /** 길이만 맞추는 채움 문자. BMP 라 코드 포인트 수와 길이가 같다. */
    const val FILLER_CHAR: String = "가"

    /**
     * 값을 매번 다르게 만드는 첫 글자 후보. 전부 BMP 한 글자라 **길이를 바꾸지 않는다.**
     *
     * [FILLER_CHAR] 를 넣지 않는다 — 넣으면 그 회차의 값이 이전 회차와 같아진다.
     */
    private const val PLAIN_MARKERS: String = "나다라마바사아자차카타파하"

    /**
     * 정규화하면 사라지지만 원시 길이에는 세어지는 문자.
     *
     * 이스케이프로 적는다 — 소스에 원시 제어 바이트를 싣지 않는다(쓰기 도구가 실제로 그것을
     * 실어 이 저장소의 파일을 한 번 깨뜨렸다). `WorkspaceContractTest` 와 같은 값이다.
     */
    const val CONTROL_CHAR: String = "\u0001"

    const val EMAIL_DOMAIN: String = "@example.test"

    /** 형식 축 프로브. 길이는 규칙 안이고 형식만 깨져 있다. */
    const val MALFORMED_EMAIL: String = "not-an-email"

    const val UNPROCESSABLE: Int = 422

    private const val BELOW = "아래"
    private const val ABOVE = "위"

    /** 정규화 프로브가 상한 필드에서 원시 길이를 경계 위로 올리는 데 쓰는 잡음 수. */
    private const val DIVERGENCE_NOISE = 5

    private val SUCCESS_RANGE = 200..299

    private var counter = 0
}

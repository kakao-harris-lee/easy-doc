package kr.easydoc.api.support

/** F3 축의 프로브 조립과 판정 — 관측 지점(MockMvc / 실제 소켓)이 둘이라 여기 한 벌만 둔다. */
object RequestFieldProbes {
    /** 한 프로브의 관측. 관측 지점이 무엇이든 이 두 값으로 환원된다. */
    data class Observed(
        val status: Int,
        val detail: Any?,
    ) {
        val accepted: Boolean get() = status in SUCCESS_RANGE

        /** `detail` 이 배열인가. F3 판정의 전부다. */
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

    /** 필드마다 다른 두 가지 — 값의 모양과 정규화가 걷어내는 잡음. */
    data class FieldShape(
        val shape: ProbeShape,
        val noise: Noise,
    )

    /** 계약 필드 → 값 조립 규칙. */
    val FIELD_SHAPES: Map<String, FieldShape> =
        mapOf(
            "SignupRequest.email" to FieldShape(ProbeShape.EMAIL, Noise.LEADING_SPACE),
            "SignupRequest.password" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "DocumentTextRequest.text" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "WorkspaceNameRequest.name" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "ConversionReviewRequest.edited_text" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
            "DictionaryLookupRequest.text" to FieldShape(ProbeShape.PLAIN, Noise.CONTROL_CHAR),
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

            ProbeShape.PLAIN -> {
                if (length <= 0) {
                    ""
                } else {
                    val marker = PLAIN_MARKERS[counter++ % PLAIN_MARKERS.length]
                    marker + FILLER_CHAR.repeat(length - 1)
                }
            }
        }

    /** 원시 길이는 `baseLength + noise`, 정규화 후 길이는 `baseLength` 인 값. */
    fun divergentValue(
        field: String,
        baseLength: Int,
        noise: Int,
    ): String {
        val base = valueOf(field, baseLength)

        return when (shapeOf(field).noise) {
            Noise.LEADING_SPACE -> " ".repeat(noise) + base
            Noise.CONTROL_CHAR -> base + CONTROL_CHAR.repeat(noise)
        }
    }

    /** 한 필드를 재고 [Finding] 을 만든다. [probe] 는 값으로 요청을 보내고 관측을 돌려준다. */
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

        val expectedRejected = if (constraint.upperBound) ABOVE else BELOW
        if (rejected.size == 1 && rejected.single().first != expectedRejected) {
            problems +=
                "경계 방향이 계약과 반대다 — 계약 x-service-constraint 는 " +
                "${if (constraint.upperBound) "상한(max_length)" else "하한(min_length)"} 이라 " +
                "$expectedRejected 쪽이 거절돼야 하는데 ${rejected.single().first} 쪽이 거절됐다"
        }

        if (rejected.size == 1) {
            val upperBound = constraint.upperBound
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

    /** 값을 매번 다르게 만드는 첫 글자 후보. 전부 BMP 한 글자라 길이를 바꾸지 않는다. */
    private const val PLAIN_MARKERS: String = "나다라마바사아자차카타파하"

    /** 정규화하면 사라지지만 원시 길이에는 세어지는 문자. */
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

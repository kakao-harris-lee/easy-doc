package kr.easydoc.api.support

import kr.easydoc.core.privacy.UserContent
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * **컴파일러가 `toString()` 을 만들어 주는 타입에 표식을 심고, 그 표식이 나오는지 본다.**
 *
 * ## 왜 Java 반사가 아니라 Kotlin 반사인가 (게이트 24 privacy-gate A-3′)
 *
 * 종전 판은 `componentN()` 접근자를 `Regex("component\d+")` 로 세어 필드 목록을 만들었다.
 * `@JvmInline value class` 파라미터의 `componentN()` 은 JVM 에서 **이름이 맹글링**되므로
 * 그 정규식에 걸리지 않고, 계수가 어긋나면 생성자 타입 비교가 실패해 클래스가 통째로
 * `?: return null` 로 빠졌다. 실례가 `core.privacy.MaskingResult` 였다 — **마스킹 대응표를
 * 든 타입이 탐지 밖**이었고, 그때 초록이던 이유는 게이트가 아니라 그 타입이 스스로
 * 재정의를 갖고 있었기 때문이다.
 *
 * 이 저장소의 규약이 **본문·비밀을 value class 로 감싸는 것**(`MaskedText`·`ModelDraft`·
 * `ReviewedBody`·`Secret`)이라, 가장 위험한 DTO 가 정확히 그 구멍에 놓였다. 그래서 판정
 * 근거를 JVM 시그니처에서 **주 생성자 파라미터**(`primaryConstructor.parameters`)로 옮겼다 —
 * 맹글링·박싱과 무관하게 소스에 적힌 그대로를 읽는다.
 *
 * ## 판정 불가는 통과가 아니다
 *
 * 파라미터 타입을 만들 줄 모르면 **끊는다**(`error`). 종전의 두 `return null` 갈래는
 * 없앴다 — 조용히 빠진 클래스는 검사받은 것과 구분되지 않는다(`CLAUDE.md` 규칙 4 ⑶).
 *
 * ## 무엇을 어디에 심는가
 *
 * - **표식을 심는 자리**: 사용자 텍스트를 담을 수 있는 파라미터 중, 이름이
 *   민감 토큰을 품거나 클래스에 [UserContent] 가 붙은 것.
 * - **[FILLER] 를 넣는 자리**: 나머지. 표식과 섞이면 판정이 흐려진다.
 * - **텍스트를 담을 수 있는가**는 타입을 **따라 들어가서** 정한다 — `String`, 그것을 감싼
 *   value class, 그 둘을 담은 컬렉션, 그리고 그런 것을 든 제품 타입까지. 종전에는
 *   `String` 이 아니면 그 자리에서 「대상 아님」으로 끝났고, 그것이 R-5 가 지적한
 *   **강제되지 않는 제외 사유**였다.
 */
class GeneratedToStringProbes(
    private val classes: List<KClass<*>>,
    private val sensitiveNameTokens: List<String>,
) {
    /**
     * 「값을 감싸는 타입」으로 닿은 것 — 파라미터 하나짜리이고 그 하나가 텍스트를 담는다.
     *
     * [dataClassProbes] 를 계산하는 동안 채워진다. 열거가 아니라 **도달 기록**이라, 새
     * 래퍼를 만들어 DTO 에 넣으면 목록에 손대지 않아도 검사 대상이 된다.
     */
    private val reachedWrappers = linkedSetOf<KClass<*>>()

    /** 민감 파라미터를 든 제품 `data class` 마다 하나. */
    val dataClassProbes: List<ToStringProbe> by lazy {
        classes.filter { it.isData }.sortedBy { it.qualifiedName }.mapNotNull(::dataClassProbe)
    }

    /**
     * **값을 감싸는 타입** — `@JvmInline value class` 전부와, `data class` 필드로 닿은
     * 1-파라미터 래퍼.
     *
     * 종전 KDoc 은 *"`Secret`·`MaskedText` 처럼 값을 감싸는 타입이 자기 `toString()` 에서
     * 이미 가리기 때문"* 을 제외 사유로 적었다. 참이었지만 **그 성질을 강제하는 장치가
     * 없었다** — 새 래퍼가 재정의를 빠뜨리면 래퍼도 그것을 든 DTO 도 둘 다 검사 밖이다
     * (Claude R-5 ⓐⓑ, 음성 대조로 실측). 사유를 주석에서 **단언**으로 옮긴 자리다.
     */
    val wrapperProbes: List<ToStringProbe> by lazy {
        // 도달 기록은 `data class` 해석 과정에서 채워진다 — 먼저 훑고, 훑은 결과가 비면 끊는다.
        check(dataClassProbes.isNotEmpty()) { "제품 data class 후보가 0건이라 래퍼 도달 기록을 신뢰할 수 없다" }
        (classes.filter { it.isValue } + reachedWrappers)
            .distinct()
            .sortedBy { it.qualifiedName }
            .mapNotNull(::wrapperProbe)
    }

    /**
     * **`data`/`value` 가 아닌 일반 class 중 `toString()` 을 손으로 쓴 것** (게이트 25 R-10).
     *
     * ## 왜 이 갈래가 따로 있는가
     *
     * 위 두 목록은 **컴파일러가 `toString()` 을 만들어 주는 선언**만 본다. 그 경계 밖에도
     * 사용자 콘텐츠를 든 타입이 있고(`EncryptedContent` 가 첫 사례다), 그쪽은 재정의가
     * 있느냐 없느냐로 위험이 갈린다.
     *
     * - **재정의가 없으면 샐 수 없다** — `Any.toString()` 은 `클래스명@해시` 만 낸다.
     * - **재정의가 있으면 그 안에서 무엇을 찍는지는 아무도 안 보고 있었다.**
     *
     * 그래서 후보를 「재정의를 **선언한** 일반 class」로 좁힌다. 좁힘이 사유가 아니라 성질에
     * 근거하므로 면제 조항이 아니다 — 재정의 없는 클래스는 **구조적으로** 값을 낼 수 없다.
     *
     * ## 판정 불가를 통과로 세지 않는다
     *
     * 표본을 만들 수 없는 후보는 [undecidableGeneralClasses] 에 남고, 호출자가 그 목록이
     * 비어 있는지 함께 단언한다. 조용히 건너뛰면 그 타입은 검사받은 것과 구분되지 않는다.
     */
    val generalClassProbes: List<ToStringProbe> by lazy {
        generalClassCandidates.mapNotNull { it.probe }
    }

    /** 후보이긴 한데 표본을 만들지 못한 일반 class 와 그 사유. **비어 있어야 한다.** */
    val undecidableGeneralClasses: List<String> by lazy {
        generalClassCandidates.mapNotNull { it.failure }
    }

    /**
     * 「`toString()` 을 손으로 쓴 일반 class」 전부. 후보 선정이 실제로 무언가를 훑는지
     * 확인하는 분모다 — 0 이면 이 갈래는 아무 데도 도달하지 않는다.
     */
    val generalClassesWithCustomToString: List<KClass<*>> by lazy {
        classes
            .filter { !it.isData && !it.isValue }
            // 자바 반사로 거른다. `KClass.objectInstance` 는 접근 제한 필드에서 예외를 던진다.
            .filter { !it.java.isEnum && !it.java.isInterface && !Modifier.isAbstract(it.java.modifiers) }
            .filter { declaresToString(it) }
            .sortedBy { it.qualifiedName }
    }

    private val generalClassCandidates: List<GeneralCandidate> by lazy {
        generalClassesWithCustomToString.mapNotNull(::generalCandidate)
    }

    // ---------------------------------------------------------------- 후보

    private fun dataClassProbe(type: KClass<*>): ToStringProbe? {
        val constructor = primaryConstructorOf(type)
        val parameters = constructor.parameters
        val slots = parameters.map { slotFor(it.type, label(type, it), listOf(type)) }
        val widened = type.annotations.any { it is UserContent }
        val planted =
            parameters.indices.filter { index ->
                slots[index].carriesText && (widened || isSensitiveName(nameOf(type, parameters[index])))
            }
        return if (planted.isEmpty()) {
            null
        } else {
            ToStringProbe(type, planted.map { nameOf(type, parameters[it]) }) {
                construct(constructor, type, slots.mapIndexed { index, slot -> slot.value(index in planted) })
            }
        }
    }

    private fun wrapperProbe(type: KClass<*>): ToStringProbe? {
        val slot = productSlot(type, type.simpleName ?: type.toString(), emptyList())
        return if (!slot.carriesText) {
            null
        } else {
            ToStringProbe(type, primaryConstructorOf(type).parameters.map { nameOf(type, it) }) {
                requireNotNull(slot.value(planting = true)) { "${type.qualifiedName} 인스턴스를 만들지 못했다" }
            }
        }
    }

    /**
     * 일반 class 하나를 후보로 만든다. 민감 자리가 없으면 null(대상 아님), 표본을 만들 수
     * 없으면 [GeneralCandidate.failure] 로 남긴다 — **통과로 세지 않는다.**
     */
    private fun generalCandidate(type: KClass<*>): GeneralCandidate? {
        val parameters = type.primaryConstructor?.parameters
        return when {
            // 주 생성자가 없으면 파라미터를 알 수 없다 — 판정 불가는 통과가 아니다.
            parameters == null -> {
                GeneralCandidate.undecidable(type, "주 생성자가 없다")
            }

            // 파라미터가 없으면 담을 값이 없다(`object` 나 무인자 클래스). 대상 아님.
            parameters.isEmpty() -> {
                null
            }

            else -> {
                runCatching { dataClassProbe(type) }
                    .fold(
                        onSuccess = { probe -> probe?.let { GeneralCandidate(it, null) } },
                        onFailure = { failure ->
                            GeneralCandidate.undecidable(type, failure.message ?: "표본 생성 실패")
                        },
                    )
            }
        }
    }

    /** `toString()` 을 스스로 선언했는가. 물려받기만 했으면 `Any.toString()` 이라 값이 나올 수 없다. */
    private fun declaresToString(type: KClass<*>): Boolean =
        type.java.declaredMethods.any { it.name == "toString" && it.parameterCount == 0 }

    private fun isSensitiveName(name: String): Boolean {
        val lowered = name.lowercase()
        return sensitiveNameTokens.any { it in lowered }
    }

    // ---------------------------------------------------------------- 값 만들기

    /**
     * 파라미터 한 자리를 어떻게 채울지 정한다. **모르는 타입은 끊는다.**
     *
     * 갈래를 늘려야 할 상황이 곧 새 DTO 가 생기는 상황이므로, 조용히 건너뛰면 그 타입을
     * 쓰는 DTO 가 통째로 검사 밖에 남는다.
     */
    private fun slotFor(
        type: KType,
        where: String,
        visiting: List<KClass<*>>,
    ): ProbeSlot {
        val classifier =
            type.classifier as? KClass<*>
                ?: error("$where — 타입 파라미터라 값을 만들 수 없다($type). 이 자리를 구체 타입으로 바꿔라")
        val inert = INERT_VALUES[classifier]
        return when {
            inert != null -> {
                ProbeSlot(carriesText = false) { inert }
            }

            classifier == String::class -> {
                ProbeSlot(carriesText = true) { planting -> if (planting) SENTINEL else FILLER }
            }

            classifier.java.isEnum -> {
                ProbeSlot(carriesText = false) { firstEnumConstant(classifier, where) }
            }

            classifier in COLLECTION_FACTORIES -> {
                collectionSlot(classifier, type, where, visiting)
            }

            classifier.qualifiedName?.startsWith(PRODUCT_PACKAGE) == true -> {
                productSlot(classifier, where, visiting)
            }

            else -> {
                error(
                    "$where — 타입 ${classifier.qualifiedName} 을 이 탐지기가 만들 줄 모른다. " +
                        "slotFor 에 갈래를 더하라 — 건너뛰면 그 타입을 쓰는 DTO 가 통째로 검사 밖에 남는다.",
                )
            }
        }
    }

    private fun collectionSlot(
        classifier: KClass<*>,
        type: KType,
        where: String,
        visiting: List<KClass<*>>,
    ): ProbeSlot {
        val argument =
            type.arguments.singleOrNull()?.type
                ?: error("$where — 원소 타입을 읽을 수 없는 컬렉션이다($type). star projection 은 판정 불가라 끊는다")
        val element = slotFor(argument, "$where[]", visiting)
        val factory = COLLECTION_FACTORIES.getValue(classifier)
        return ProbeSlot(element.carriesText) { planting -> factory(element.value(planting)) }
    }

    /**
     * 제품 타입 하나를 만든다. 파라미터가 하나뿐이고 그 하나가 텍스트를 담으면
     * **값을 감싸는 타입**으로 기록해 [wrapperProbes] 가 따로 검사한다.
     */
    private fun productSlot(
        type: KClass<*>,
        where: String,
        visiting: List<KClass<*>>,
    ): ProbeSlot {
        require(type !in visiting) {
            "$where — 타입이 순환한다(${(visiting + type).joinToString(" → ") { it.simpleName ?: "?" }}). " +
                "유한한 표본을 만들 수 없어 끊는다"
        }
        val constructor = primaryConstructorOf(type)
        val slots =
            constructor.parameters.map { parameter ->
                slotFor(parameter.type, label(type, parameter), visiting + type)
            }
        val carriesText = slots.any { it.carriesText }
        if (carriesText && slots.size == 1) {
            reachedWrappers += type
        }
        return ProbeSlot(carriesText) { planting ->
            construct(constructor, type, slots.map { it.value(planting) })
        }
    }

    private fun construct(
        constructor: KFunction<*>,
        type: KClass<*>,
        arguments: List<Any?>,
    ): Any {
        constructor.isAccessible = true
        return constructor.call(*arguments.toTypedArray())
            ?: error("${type.qualifiedName} 의 주 생성자가 null 을 돌려줬다 — 표식을 심을 인스턴스가 없다")
    }

    private fun primaryConstructorOf(type: KClass<*>): KFunction<*> =
        type.primaryConstructor
            ?: error(
                "${type.qualifiedName} 의 주 생성자를 읽지 못했다 — 파라미터를 알 수 없으면 판정 불가이고, " +
                    "판정 불가는 통과가 아니다",
            )

    private fun nameOf(
        type: KClass<*>,
        parameter: KParameter,
    ): String = parameter.name ?: error("${type.qualifiedName} 의 ${parameter.index} 번 파라미터에 이름이 없다")

    private fun label(
        type: KClass<*>,
        parameter: KParameter,
    ): String = "${type.simpleName}.${parameter.name ?: parameter.index}"

    private fun firstEnumConstant(
        type: KClass<*>,
        where: String,
    ): Any = type.java.enumConstants.firstOrNull() ?: error("$where — 상수가 없는 enum 이라 표본을 만들 수 없다")

    companion object {
        /** 민감 자리에 심는 표식. `toString()` 산출에 이 문자열이 있으면 값이 새는 것이다. */
        const val SENTINEL: String = "SENSITIVE-PROBE-8f31c2d4"

        /** 민감하지 않은 텍스트 자리에 넣는 값. 표식과 섞이면 판정이 흐려진다. */
        const val FILLER: String = "filler"

        /** 이 접두사로 시작하는 타입만 「제품 타입」으로 보고 따라 들어간다. */
        private const val PRODUCT_PACKAGE = "kr.easydoc."

        private val FIXED_UUID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

        /**
         * **구조적으로 사용자 텍스트를 담지 못하는 타입**과 그 표본값.
         *
         * 「담지 못한다」가 이 목록의 유일한 입장권이다. 문자열을 담을 수 있는 타입을 여기
         * 넣으면 그것이 곧 면제 조항이 된다.
         */
        private val INERT_VALUES: Map<KClass<*>, Any> =
            mapOf(
                Int::class to 0,
                Long::class to 0L,
                Short::class to 0.toShort(),
                Byte::class to 0.toByte(),
                Double::class to 0.0,
                Float::class to 0.0f,
                Boolean::class to false,
                Char::class to ' ',
                UUID::class to FIXED_UUID,
                Instant::class to Instant.EPOCH,
                Duration::class to Duration.ZERO,
                BigDecimal::class to BigDecimal.ZERO,
                ByteArray::class to ByteArray(0),
            )

        /** 원소 하나짜리 표본을 만드는 컬렉션 갈래. 모르는 컬렉션은 [slotFor] 가 끊는다. */
        private val COLLECTION_FACTORIES: Map<KClass<*>, (Any?) -> Any> =
            mapOf(
                List::class to { element: Any? -> listOf(element) },
                Collection::class to { element: Any? -> listOf(element) },
                Iterable::class to { element: Any? -> listOf(element) },
                Set::class to { element: Any? -> setOf(element) },
            )
    }
}

/** 일반 class 후보 하나 — 표본을 만들었거나([probe]), 만들지 못했거나([failure]) 둘 중 하나다. */
class GeneralCandidate(
    val probe: ToStringProbe?,
    val failure: String?,
) {
    companion object {
        fun undecidable(
            type: KClass<*>,
            reason: String,
        ) = GeneralCandidate(null, "${type.qualifiedName}: $reason")
    }
}

/** 파라미터 한 자리 — 텍스트를 담을 수 있는지와, 표식을 심을지에 따른 표본값. */
class ProbeSlot(
    val carriesText: Boolean,
    private val build: (Boolean) -> Any?,
) {
    fun value(planting: Boolean): Any? = build(planting)
}

/** 표식을 심은 인스턴스 하나와, 그 표식이 어느 파라미터에 들어갔는지. */
class ToStringProbe(
    val type: KClass<*>,
    val plantedParameters: List<String>,
    private val build: () -> Any,
) {
    /** 표식이 `toString()` 산출에 나타나는가 — 나타나면 값이 그대로 찍힌 것이다. */
    fun leaks(): Boolean = build().toString().contains(GeneratedToStringProbes.SENTINEL)

    override fun toString(): String = "${type.qualifiedName}(표식=$plantedParameters)"
}

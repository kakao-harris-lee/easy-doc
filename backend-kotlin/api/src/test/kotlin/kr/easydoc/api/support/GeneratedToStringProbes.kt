package kr.easydoc.api.support

import kr.easydoc.core.privacy.UserContent
import org.w3c.dom.Element
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/** 컴파일러가 `toString()` 을 만들어 주는 타입에 표식을 심고, 그 표식이 나오는지 본다. */
class GeneratedToStringProbes(
    private val classes: List<KClass<*>>,
    private val sensitiveNameTokens: List<String>,
) {
    /** 「값을 감싸는 타입」으로 닿은 것 — 파라미터 하나짜리이고 그 하나가 텍스트를 담는다. */
    private val reachedWrappers = linkedSetOf<KClass<*>>()

    /** 민감 파라미터를 든 제품 `data class` 마다 하나. */
    val dataClassProbes: List<ToStringProbe> by lazy {
        classes.filter { it.isData }.sortedBy { it.qualifiedName }.mapNotNull(::dataClassProbe)
    }

    /**
     * 값을 감싸는 타입 — `@JvmInline value class` 전부와, `data class` 필드로 닿은
     * 1-파라미터 래퍼.
     */
    val wrapperProbes: List<ToStringProbe> by lazy {

        check(dataClassProbes.isNotEmpty()) { "제품 data class 후보가 0건이라 래퍼 도달 기록을 신뢰할 수 없다" }
        (classes.filter { it.isValue } + reachedWrappers)
            .distinct()
            .sortedBy { it.qualifiedName }
            .mapNotNull(::wrapperProbe)
    }

    /** `data`/`value` 가 아닌 일반 class 중 `toString()` 을 손으로 쓴 것 (게이트 25 R-10). */
    val generalClassProbes: List<ToStringProbe> by lazy {
        generalClassCandidates.mapNotNull { it.probe }
    }

    /** 후보이긴 한데 표본을 만들지 못한 일반 class 와 그 사유. 비어 있어야 한다. */
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
            .filter { !it.java.isEnum && !it.java.isInterface && !Modifier.isAbstract(it.java.modifiers) }
            .filter { declaresToString(it) }
            .sortedBy { it.qualifiedName }
    }

    private val generalClassCandidates: List<GeneralCandidate> by lazy {
        generalClassesWithCustomToString.mapNotNull(::generalCandidate)
    }

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
     * 없으면 [GeneralCandidate.failure] 로 남긴다 — 통과로 세지 않는다.
     */
    private fun generalCandidate(type: KClass<*>): GeneralCandidate? {
        val parameters = type.primaryConstructor?.parameters
        return when {
            parameters == null -> {
                GeneralCandidate.undecidable(type, "주 생성자가 없다")
            }

            parameters.isEmpty() -> {
                null
            }

            else -> {
                val widened = type.annotations.any { it is UserContent }
                val anySensitive =
                    widened || parameters.any { isSensitiveName(nameOf(type, it)) }
                if (!anySensitive) {
                    null
                } else {
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
    }

    /** `toString()` 을 스스로 선언했는가. 물려받기만 했으면 `Any.toString()` 이라 값이 나올 수 없다. */
    private fun declaresToString(type: KClass<*>): Boolean =
        type.java.declaredMethods.any { it.name == "toString" && it.parameterCount == 0 }

    private fun isSensitiveName(name: String): Boolean {
        val lowered = name.lowercase()
        return sensitiveNameTokens.any { it in lowered }
    }

    /** 파라미터 한 자리를 어떻게 채울지 정한다. 모르는 타입은 끊는다. */
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

            classifier == Map::class -> {
                mapSlot(type, where, visiting)
            }

            classifier == Element::class -> {
                // DOM 요소는 **텍스트를 담는다** — 문서 파서를 지나는 타입이 검사 밖에 남지
                // 않도록 표식을 요소 안에 심는다. 심는 자리는 시작 태그 뒤 텍스트이고,
                // 그것이 추출·반영이 실제로 읽는 자리다(`ingest/OoxmlDom.leadingText`).
                ProbeSlot(carriesText = true) { planting -> elementWith(if (planting) SENTINEL else FILLER) }
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

    /** 표식을 담은 DOM 요소 하나. */
    private fun elementWith(text: String): Element {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        return document.createElement("t").apply { appendChild(document.createTextNode(text)) }
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

    /** 맵 한 자리. 키와 값 양쪽에 표본을 심는다. */
    private fun mapSlot(
        type: KType,
        where: String,
        visiting: List<KClass<*>>,
    ): ProbeSlot {
        val arguments = type.arguments.map { it.type }
        require(arguments.size == 2 && arguments.all { it != null }) {
            "$where — 키·값 타입을 읽을 수 없는 맵이다($type). star projection 은 판정 불가라 끊는다"
        }
        val key = slotFor(requireNotNull(arguments[0]), "$where{key}", visiting)
        val value = slotFor(requireNotNull(arguments[1]), "$where{value}", visiting)
        return ProbeSlot(key.carriesText || value.carriesText) { planting ->
            mapOf(key.value(planting) to value.value(planting))
        }
    }

    /**
     * 제품 타입 하나를 만든다. 파라미터가 하나뿐이고 그 하나가 텍스트를 담으면
     * 값을 감싸는 타입으로 기록해 [wrapperProbes] 가 따로 검사한다.
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

        /** 구조적으로 사용자 텍스트를 담지 못하는 타입과 그 표본값. */
        private val INERT_VALUES: Map<KClass<*>, Any> =
            mapOf(
                Int::class to 1,
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

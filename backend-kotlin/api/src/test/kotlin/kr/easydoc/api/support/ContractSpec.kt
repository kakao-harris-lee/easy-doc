package kr.easydoc.api.support

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * **계약 파일을 직접 읽는다** — OQ-3 / X-J2 의 구현.
 *
 * ## 왜 필요한가
 *
 * 지금까지 Kotlin 계약 테스트는 기대값(상태 코드·헤더 값·상한·문구)을 코드에 **복제**했다.
 * 복제본은 계약이 바뀌어도 따라가지 않으므로, 계약에서 조항이 지워져도 테스트가 옛 값으로
 * 통과한다 — 그때 계약이 되는 것은 계약 파일이 아니라 테스트다.
 *
 * ## 지키는 성질 (명세 §4-2)
 *
 * 1. **없으면 실패한다.** 파일이 없거나 노드가 없으면 예외로 끝난다. 기본값·`null` 반환·
 *    조건부 스킵이 없다 — 그것을 두면 계약에서 조항이 삭제돼도 테스트가 통과한다.
 * 2. **작업 디렉터리에 의존하지 않는다.** Gradle 테스트의 작업 디렉터리는 모듈
 *    디렉터리이고 계약 파일은 저장소 루트에 있다. 빌드가 주입하는
 *    `easydoc.kotlin.source.root`(= `backend-kotlin/`)에서 한 단계 올라가 찾는다.
 *    상대 경로를 손으로 조립하면 「실패한다」가 개발자 기계에서만 참이 된다.
 * 3. **`fields[]` 는 인덱스가 아니라 `field` 이름으로 찾는다.** 항목이 추가·재배열되면
 *    인덱스는 **조용히 다른 필드를 읽는다.**
 *
 * 성질 4(읽은 값을 실제 단언에 쓴다)는 이 파일이 보장할 수 없다 — 음성 대조가 그것을 잰다.
 */
object ContractSpec {
    /** 저장소 루트 기준 계약 파일 경로. */
    private const val CONTRACT_RELATIVE_PATH = "contracts/easy-doc-v1.yaml"

    /** 빌드가 모든 테스트 태스크에 주입하는 Gradle 루트(= `backend-kotlin/`). */
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    val file: File by lazy {
        val sourceRoot =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 계약 파일을 찾을 기준점이 없다. " +
                        "build.gradle.kts 의 테스트 태스크 설정을 확인한다.",
                )
        val candidate = File(sourceRoot).parentFile?.resolve(CONTRACT_RELATIVE_PATH)
        require(candidate != null && candidate.isFile) {
            "계약 파일을 찾지 못했다: $candidate — 이 테스트의 기대값은 전부 그 파일에서 온다."
        }
        candidate
    }

    private val root: Map<*, *> by lazy {
        file.inputStream().use { stream ->
            Yaml().load<Any>(stream) as? Map<*, *>
                ?: error("계약 파일 최상위가 매핑이 아니다: ${file.path}")
        }
    }

    // ------------------------------------------------------------------ 공통 탐색

    /**
     * 키 경로를 따라 내려간다. 중간에 없으면 **실패한다**.
     *
     * 실패 메시지에 경로 전체를 담는다 — 계약이 움직였을 때 어디가 사라졌는지 바로 보여야
     * 다음 사람이 "테스트가 낡았다"와 "계약이 바뀌었다"를 가릴 수 있다.
     */
    fun at(vararg path: String): Any {
        var current: Any = root
        val walked = mutableListOf<String>()
        path.forEach { key ->
            walked += key
            val map = current as? Map<*, *> ?: error("계약 경로가 매핑이 아니다: ${walked.joinToString(".")}")
            current = map[key] ?: error("계약에 없는 경로다: ${walked.joinToString(".")} (${file.name})")
        }
        return current
    }

    fun text(vararg path: String): String = at(*path).toString()

    fun number(vararg path: String): Int =
        (at(*path) as? Number)?.toInt() ?: error("정수가 아니다: ${path.joinToString(".")}")

    fun list(vararg path: String): List<Any?> = at(*path) as? List<Any?> ?: error("목록이 아니다: ${path.joinToString(".")}")

    fun map(vararg path: String): Map<*, *> = at(*path) as? Map<*, *> ?: error("매핑이 아니다: ${path.joinToString(".")}")

    fun strings(vararg path: String): List<String> = list(*path).map { it.toString() }

    // ------------------------------------------------------------------ P-1 · P-2

    /** P-1. 해당 오퍼레이션이 선언한 응답 상태 코드 집합. */
    fun responseStatuses(
        path: String,
        method: String,
    ): Set<String> = map("paths", path, method, "responses").keys.map { it.toString() }.toSet()

    /**
     * P-1. 성공 상태 — 선언된 2xx 가 **정확히 하나**일 때만 성립한다.
     *
     * 둘 이상이면 실패한다. 계약에 2xx 가 늘어난 것은 이 테스트가 무엇을 기대해야 하는지가
     * 바뀐 것이므로, 조용히 첫 번째를 고르면 안 된다.
     */
    fun successStatus(
        path: String,
        method: String,
    ): Int {
        val successes = responseStatuses(path, method).filter { it.startsWith("2") }
        require(successes.size == 1) { "성공 상태가 하나가 아니다: $path $method → $successes" }
        return successes.first().toInt()
    }

    /** P-2. 그 응답에 선언된 헤더 이름 집합. */
    fun responseHeaderNames(
        path: String,
        method: String,
        status: Int,
    ): Set<String> =
        map("paths", path, method, "responses", status.toString(), "headers")
            .keys
            .map {
                it.toString()
            }.toSet()

    /** 오퍼레이션의 `security` 선언. 인증이 필요 없으면 빈 목록이다. */
    fun security(
        path: String,
        method: String,
    ): List<Any?> = list("paths", path, method, "security")

    /** `paths` 아래 모든 (경로, 메서드) 짝. HTTP 메서드가 아닌 키(`parameters` 등)는 뺀다. */
    fun operations(): List<Pair<String, String>> =
        map("paths").entries.flatMap { (path, operations) ->
            (operations as Map<*, *>)
                .keys
                .map { it.toString() }
                .filter { it in HTTP_METHODS }
                .map { path.toString() to it }
        }

    // ------------------------------------------------------------------ P-3 · P-4 · P-5

    /** P-3. 헤더 컴포넌트가 `const` 로 못박은 값. */
    fun headerConst(component: String): String = text("components", "headers", component, "schema", "const")

    /** P-4. 전역 부착 대상 헤더(이름 → 값). */
    fun globalResponseHeaders(): Map<String, String> =
        map("x-global-response-headers", "headers").entries.associate { (k, v) -> k.toString() to v.toString() }

    /**
     * P-3b. **헤더 이름 → 컴포넌트 이름**을 계약의 `$ref` 에서 유도한다.
     *
     * 이 표를 테스트마다 손으로 적으면(종전에 두 곳에 있었다) 계약이 컴포넌트 이름을
     * 바꿔도 따라가지 않고, 새 헤더가 생겨도 검사 범위가 늘지 않는다. `paths` 아래
     * 응답 선언이 이미 `$ref: '#/components/headers/…'` 로 둘을 잇고 있으므로 그것을 읽는다.
     *
     * 같은 이름이 서로 다른 컴포넌트를 가리키면 실패한다 — 그 상태에서는 「이 헤더의
     * 계약 값」이 하나로 정해지지 않는다.
     */
    fun headerComponentsByName(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        map("paths").values.filterIsInstance<Map<*, *>>().forEach { operations ->
            operations
                .filterKeys { it.toString() in HTTP_METHODS }
                .values
                .filterIsInstance<Map<*, *>>()
                .forEach { operation ->
                    (operation["responses"] as? Map<*, *>)
                        ?.values
                        ?.filterIsInstance<Map<*, *>>()
                        ?.forEach { response -> collectHeaderRefs(response, found) }
                }
        }
        require(found.isNotEmpty()) { "계약의 응답 선언에서 헤더 \$ref 를 하나도 찾지 못했다" }
        return found
    }

    /**
     * P-4b. 전역 부착 헤더의 **이름 → 계약이 `const` 로 못박은 값**.
     *
     * 값을 `x-global-response-headers.headers` 가 아니라 **컴포넌트 `const`** 에서 읽는다.
     * 전역 절의 값만 읽으면 컴포넌트 `const` 를 바꿔도 테스트가 반응하지 않는다 —
     * 음성 대조 N-3 이 실측으로 드러낸 자리다.
     */
    fun globalHeaderValues(): Map<String, String> {
        val components = headerComponentsByName()
        return globalResponseHeaders().keys.associateWith { header ->
            headerConst(
                components[header]
                    ?: error("전역 헤더 $header 를 `\$ref` 로 가리키는 응답 선언이 없다 — 값의 정본을 찾을 수 없다"),
            )
        }
    }

    private fun collectHeaderRefs(
        response: Map<*, *>,
        into: MutableMap<String, String>,
    ) {
        (response["headers"] as? Map<*, *>)?.forEach { (name, declaration) ->
            val ref = (declaration as? Map<*, *>)?.get("\$ref")?.toString() ?: return@forEach
            val component = ref.substringAfterLast('/')
            val previous = into.put(name.toString(), component)
            require(previous == null || previous == component) {
                "헤더 $name 이 서로 다른 컴포넌트를 가리킨다: $previous / $component"
            }
        }
    }

    /** P-5. 고위험 하한선 목록. `"POST /auth/signup"` 형태의 문자열이다. */
    fun privateResponseHeaderTargets(): List<String> = strings("x-private-response-headers", "applies_to")

    // ------------------------------------------------------------------ P-6 · P-7

    /**
     * P-6. `x-request-field-constraints.fields[]` 에서 **이름으로** 찾는다.
     *
     * 인덱스로 찾지 않는 이유는 §4-2 3번이 적었다 — 항목이 재배열되면 조용히 다른 필드를
     * 읽는다. 못 찾으면 실패다.
     */
    fun requestFieldConstraint(field: String): RequestFieldConstraint {
        val entry =
            list("x-request-field-constraints", "fields")
                .filterIsInstance<Map<*, *>>()
                .firstOrNull { it["field"] == field }
                ?: error("계약의 x-request-field-constraints.fields 에 없는 필드다: $field")
        return RequestFieldConstraint(
            field = field,
            limit = (entry["limit"] as? Number)?.toInt() ?: error("$field 의 limit 이 정수가 아니다"),
            measuredOn = entry["measured_on"]?.toString() ?: error("$field 의 measured_on 이 없다"),
            detail = entry["detail"] ?: error("$field 의 detail 이 없다"),
        )
    }

    /** P-7. `x-input-limits` 쪽 값. 같은 상한이 계약 안에 두 벌 있다. */
    fun inputLimit(name: String): Int = number("x-input-limits", name)

    // ------------------------------------------------------------------ P-8 ~ P-11

    /** 스키마의 `required` 키 목록. */
    fun schemaRequired(schema: String): Set<String> = strings("components", "schemas", schema, "required").toSet()

    /** 스키마의 `additionalProperties`. 「정확히 이 키들뿐」 단언의 근거다. */
    fun schemaAllowsAdditionalProperties(schema: String): Boolean =
        at("components", "schemas", schema, "additionalProperties") as? Boolean
            ?: error("$schema 의 additionalProperties 가 불리언이 아니다")

    /** P-10. `ErrorResponse.detail` union 의 두 갈래 `type`. */
    fun errorDetailUnionTypes(): List<String> =
        list("components", "schemas", "ErrorResponse", "properties", "detail", "oneOf")
            .filterIsInstance<Map<*, *>>()
            .map { it["type"]?.toString() ?: error("oneOf 갈래에 type 이 없다") }

    /** P-11. 스키마 속성의 `const`. */
    fun schemaPropertyConst(
        schema: String,
        property: String,
    ): String = text("components", "schemas", schema, "properties", property, "const")

    // ------------------------------------------------------------------ P-12

    /** P-12. `x-auth` 아래 스칼라 값. */
    fun authText(key: String): String = text("x-auth", key)

    fun authNumber(key: String): Int = number("x-auth", key)

    fun authStrings(key: String): List<String> = strings("x-auth", key)

    /**
     * 계약이 오퍼레이션 키로 쓰는 HTTP 메서드 어휘(소문자).
     *
     * 밖으로 여는 이유는 하나다 — 구현 쪽 매핑을 계약과 **같은 단위**로 투영하려면 그
     * 어휘가 한 곳에서 와야 한다. 두 벌이 되면 계약에 메서드가 늘어난 날 구현 쪽 투영이
     * 조용히 옛 어휘로 남는다(codex C-1 과 같은 형태의 결함이다).
     */
    val HTTP_METHODS: Set<String> = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
}

/** 계약이 요청 필드 하나에 정한 것 — 어느 층이 어느 축으로 무엇을 재고 무슨 문구를 내는가. */
data class RequestFieldConstraint(
    val field: String,
    val limit: Int,
    val measuredOn: String,
    /** 문자열 하나이거나 문자열 목록이다(`WorkspaceNameRequest.name` 은 둘을 든다). */
    val detail: Any,
) {
    /**
     * 단일 문구를 기대하는 자리. 목록이면 실패한다 — 어느 것을 고를지는 계약이 정하지 않았다.
     *
     * `${this.field}` 로 쓴다: 접근자 안에서 맨 `field` 는 **backing field** 를 가리키는
     * 소프트 키워드라 이 프로퍼티에 backing field 를 요구하게 되고 컴파일이 깨진다.
     */
    val singleDetail: String
        get() = detail as? String ?: error("${this.field} 의 detail 이 문자열 하나가 아니다: $detail")

    /** `measured_on` 이 정규화 후를 가리키는가. 계약은 산문으로 적으므로 앞머리로 가른다. */
    val measuresNormalized: Boolean get() = measuredOn.startsWith("정규화 후")

    val measuresRaw: Boolean get() = measuredOn.startsWith("원시")
}

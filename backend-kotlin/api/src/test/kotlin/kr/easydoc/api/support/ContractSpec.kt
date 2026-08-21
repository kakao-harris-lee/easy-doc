package kr.easydoc.api.support

import org.yaml.snakeyaml.Yaml
import java.io.File

/** **계약 파일을 직접 읽는다** — OQ-3 / X-J2 의 구현. */
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

    /** 키 경로를 따라 내려간다. 중간에 없으면 **실패한다**. */
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

    /** P-1. 성공 상태 — 선언된 2xx 가 **정확히 하나**일 때만 성립한다. */
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

    /** P-3b. **헤더 이름 → 컴포넌트 이름**을 계약의 `$ref` 에서 유도한다. */
    fun headerComponentsByName(): Map<String, String> {
        val components =
            headerDeclarations()
                .mapNotNull { (name, declaration) ->
                    (declaration as? ContractHeaderDeclaration.Component)?.let { name to it.component }
                }.toMap()
        require(components.isNotEmpty()) { "계약의 응답 선언에서 헤더 \$ref 를 하나도 찾지 못했다" }
        return components
    }

    /** **계약이 응답에 선언한 헤더 전부** — 이름 → 선언 갈래(`$ref` 컴포넌트 / 인라인). */
    fun headerDeclarations(): Map<String, ContractHeaderDeclaration> {
        val found = linkedMapOf<String, ContractHeaderDeclaration>()
        operations().forEach { (path, method) ->
            val responses = map("paths", path, method, "responses")
            responses.forEach { (status, response) ->
                val where = "$method $path $status"
                collectHeaders(resolveResponse(response, where), where, found)
            }
        }
        return found
    }

    /** `$ref` 컴포넌트가 아니라 **경로에 직접 적힌** 헤더 선언의 이름. */
    fun inlineHeaderNames(): Set<String> =
        headerDeclarations()
            .filterValues { it is ContractHeaderDeclaration.Inline }
            .keys

    /** P-4b. 전역 부착 헤더의 **이름 → 계약이 `const` 로 못박은 값**. */
    fun globalHeaderValues(): Map<String, String> {
        val declarations = headerDeclarations()
        return globalResponseHeaders().keys.associateWith { header ->
            when (val declaration = declarations[header]) {
                is ContractHeaderDeclaration.Component -> {
                    headerConst(declaration.component)
                }

                is ContractHeaderDeclaration.Inline -> {
                    error("전역 헤더 $header 가 인라인으로 선언돼 있다($declaration) — 값의 정본이 컴포넌트 `const` 가 아니다")
                }

                null -> {
                    error("전역 헤더 $header 를 선언한 응답이 계약에 하나도 없다 — 값의 정본을 찾을 수 없다")
                }
            }
        }
    }

    /** 응답이 `$ref` 면 `components/responses` 를 따라간다. 매핑이 아니면 끊는다. */
    private fun resolveResponse(
        response: Any?,
        where: String,
    ): Map<*, *> {
        val node =
            response as? Map<*, *>
                ?: error("$where 의 응답이 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $response")
        val ref = node["\$ref"]?.toString() ?: return node
        return map("components", "responses", ref.substringAfterLast('/'))
    }

    private fun collectHeaders(
        response: Map<*, *>,
        where: String,
        into: MutableMap<String, ContractHeaderDeclaration>,
    ) {
        val declared = response["headers"] ?: return
        val headers =
            declared as? Map<*, *>
                ?: error("$where 의 headers 가 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $declared")
        headers.forEach { (name, declaration) ->
            val parsed = parseHeader(declaration, "$where 의 헤더 $name")
            val previous = into.put(name.toString(), parsed)
            require(previous == null || previous == parsed) {
                "헤더 $name 이 계약 안에서 서로 다르게 선언됐다: $previous / $parsed ($where)"
            }
        }
    }

    private fun parseHeader(
        declaration: Any?,
        where: String,
    ): ContractHeaderDeclaration {
        val node =
            declaration as? Map<*, *>
                ?: error("$where 선언이 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $declaration")
        val ref = node["\$ref"]?.toString()
        if (ref != null) {
            return ContractHeaderDeclaration.Component(ref.substringAfterLast('/'))
        }
        val schema =
            node["schema"]
                ?: error("$where 가 인라인 선언인데 schema 가 없다 — 값의 계약이 없는 헤더는 검사할 수 없다")
        return ContractHeaderDeclaration.Inline(schema.toString())
    }

    /** 응답 컴포넌트가 예시로 못박은 `detail` 문구. */
    fun responseExampleDetail(
        component: String,
        example: String,
    ): String {
        val json = map("components", "responses", component, "content", "application/json")
        val examples = json["examples"] as? Map<*, *> ?: error("$component 에 examples 가 없다")
        val value =
            (examples[example] as? Map<*, *>)?.get("value") as? Map<*, *>
                ?: error("$component.examples.$example.value 가 없다")
        return value["detail"]?.toString() ?: error("$component.examples.$example 에 detail 이 없다")
    }

    /** P-5. 고위험 하한선 목록. `"POST /auth/signup"` 형태의 문자열이다. */
    fun privateResponseHeaderTargets(): List<String> = strings("x-private-response-headers", "applies_to")

    // ------------------------------------------------------------------ P-6 · P-7

    /** P-6. `x-request-field-constraints.fields[]` 에서 **이름으로** 찾는다. */
    fun requestFieldConstraint(field: String): RequestFieldConstraint {
        val entry =
            list("x-request-field-constraints", "fields")
                .filterIsInstance<Map<*, *>>()
                .firstOrNull { it["field"] == field }
                ?: error("계약의 x-request-field-constraints.fields 에 없는 필드다: $field")
        val limit = (entry["limit"] as? Number)?.toInt() ?: error("$field 의 limit 이 정수가 아니다")
        return RequestFieldConstraint(
            field = field,
            limit = limit,
            measuredOn = entry["measured_on"]?.toString() ?: error("$field 의 measured_on 이 없다"),
            detail = entry["detail"] ?: error("$field 의 detail 이 없다"),
            bound = constraintBound(field, limit),
        )
    }

    /** **P-6b — 경계의 방향을 계약에서 읽는다** (β-21). */
    private fun constraintBound(
        field: String,
        limit: Int,
    ): ConstraintBound {
        val schema = field.substringBefore('.')
        val property = field.substringAfter('.')
        require(schema.isNotEmpty() && property.isNotEmpty() && !property.contains('.')) {
            "x-request-field-constraints.fields[].field 가 `스키마.속성` 모양이 아니다: $field"
        }
        val node = serviceConstraint(schema, property)
        val max = (node[MAX_LENGTH_KEY] as? Number)?.toInt()
        val min = (node[MIN_LENGTH_KEY] as? Number)?.toInt()
        val bound =
            when {
                max != null && min != null -> {
                    error(
                        "$field 의 x-service-constraint 가 $MAX_LENGTH_KEY 와 $MIN_LENGTH_KEY 를 함께 적었다 — 방향이 하나로 정해지지 않는다",
                    )
                }

                max != null -> {
                    ConstraintBound.UPPER
                }

                min != null -> {
                    ConstraintBound.LOWER
                }

                else -> {
                    error(
                        "$field 의 x-service-constraint 에 $MAX_LENGTH_KEY 도 $MIN_LENGTH_KEY 도 없다 — " +
                            "경계 방향을 읽을 수 없다: $node",
                    )
                }
            }
        val declared = max ?: min
        require(declared == limit) {
            "$field 의 경계가 계약 안에서 갈렸다 — x-service-constraint $declared 대 fields[].limit $limit"
        }
        return bound
    }

    /** P-7. `x-input-limits` 쪽 값. 같은 상한이 계약 안에 두 벌 있다. */
    fun inputLimit(name: String): Int = number("x-input-limits", name)

    /** **P-25 — `x-input-limits` 아래의 `{min, max, default}` 매핑 노드.** */
    fun inputLimitRange(name: String): InputLimitRange {
        val node = map("x-input-limits", name)

        fun intAt(key: String): Int? = (node[key] as? Number)?.toInt()

        return InputLimitRange(
            name = name,
            min = intAt("min") ?: error("x-input-limits.$name 에 min 이 없다"),
            max = intAt("max"),
            default = intAt("default") ?: error("x-input-limits.$name 에 default 가 없다"),
        )
    }

    /** 오퍼레이션에 인라인으로 선언된 쿼리 파라미터들 — **`in: query` 만** 골라 온다. */
    fun queryParameters(
        path: String,
        method: String,
    ): List<ContractQueryParameter> =
        list("paths", path, method, "parameters")
            .mapIndexed { index, entry ->
                val declaration =
                    entry as? Map<*, *>
                        ?: error("$method $path 의 parameters[$index] 가 매핑이 아니다: $entry")
                ContractQueryParameter(
                    name = declaration["name"]?.toString() ?: error("$method $path 의 parameters[$index] 에 name 이 없다"),
                    location = declaration["in"]?.toString() ?: error("$method $path 의 parameters[$index] 에 in 이 없다"),
                    required = declaration["required"] as? Boolean ?: false,
                    schema =
                        declaration["schema"] as? Map<*, *>
                            ?: error("$method $path 의 parameters[$index] 에 schema 가 없다"),
                )
            }.filter { it.location == QUERY_LOCATION }

    // ------------------------------------------------------------------ P-8 ~ P-11

    /** 스키마의 `required` 키 목록. */
    fun schemaRequired(schema: String): Set<String> = strings("components", "schemas", schema, "required").toSet()

    /** **P-16 — `allOf` 합성 스키마의 `required` 를 합쳐 읽는다.** */
    fun schemaRequiredComposed(schema: String): Set<String> {
        val composed = requiredOf(map("components", "schemas", schema), schema)
        require(composed.isNotEmpty()) { "$schema 에서 required 를 하나도 찾지 못했다 — allOf 합성이 서지 않았다" }
        return composed
    }

    private fun requiredOf(
        node: Map<*, *>,
        label: String,
    ): Set<String> {
        val own = (node["required"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()
        val branches = node["allOf"] as? List<*> ?: return own
        require(branches.isNotEmpty()) { "$label 의 allOf 가 비었다" }
        // **버리지 않고 끊는다.** 종전에는 `filterIsInstance<Map<*, *>>()` 로 걸렀는데,
        // 그러면 이 파서가 읽을 줄 모르는 갈래(스칼라 등)가 조용히 사라진다. 남은 갈래가
        // `required` 를 하나라도 주면 아래 non-empty 방어도 통과해, **계약이 손상돼도
        // 게이트가 초록**이다. "아무 갈래도 무시하지 않는다"는 주장의 반대였다.
        val fromBranches =
            branches.flatMapIndexed { index, branch ->
                val mapping =
                    branch as? Map<*, *>
                        ?: error("$label 의 allOf[$index] 가 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $branch")
                val ref = mapping["\$ref"]?.toString()
                if (ref == null) {
                    requiredOf(mapping, label)
                } else {
                    val referenced = ref.substringAfterLast('/')
                    requiredOf(map("components", "schemas", referenced), referenced)
                }
            }
        return own + fromBranches
    }

    /** **P-17 — 경로에 인라인으로 적힌 응답 예시의 `detail`.** */
    fun pathExampleDetail(
        path: String,
        method: String,
        status: Int,
        example: String,
    ): String {
        val label = "$method $path $status.examples.$example"
        val json = map("paths", path, method, "responses", status.toString(), "content", "application/json")
        val examples = json["examples"] as? Map<*, *> ?: error("$label — examples 가 없다")
        val value = (examples[example] as? Map<*, *>)?.get("value") as? Map<*, *> ?: error("$label — value 가 없다")
        return value["detail"]?.toString() ?: error("$label — detail 이 없다")
    }

    /** **P-22 — D-2. 삭제 거절 두 갈래가 **동시에** 해당할 때 계약이 어느 쪽을 내라고 했는가.** */
    fun deletionRefusalPrecedenceExample(): String {
        val description = text("paths", WORKSPACE_ITEM_PATH, "delete", "description")
        val matches = PRECEDENCE_PATTERN.findAll(description).toList()
        require(matches.size == 1) {
            "delete.description 의 「둘 다 해당하면 N」 조항이 ${matches.size} 건이다 — D-2 가 사라졌거나 둘로 갈렸다"
        }
        return when (val choice = matches.single().groupValues[1]) {
            "1" -> HAS_DOCUMENTS_EXAMPLE
            "2" -> LAST_ONE_EXAMPLE
            else -> error("D-2 조항이 가리키는 갈래 번호가 1·2 가 아니다: $choice")
        }
    }

    /** **P-20 — 스키마 속성에 붙은 `x-service-constraint` 표식.** */
    fun serviceConstraint(
        schema: String,
        property: String,
    ): Map<*, *> = map("components", "schemas", schema, "properties", property, "x-service-constraint")

    /** **P-21 — 경로 수준 `parameters` 선언.** */
    fun pathParameters(path: String): List<ContractPathParameter> =
        parametersOf(path, list("paths", path, "parameters"))

    /** **그 오퍼레이션의 경로 변수 하나** — 경로 수준과 오퍼레이션 수준을 **둘 다** 본다. */
    fun pathVariable(
        path: String,
        method: String,
    ): ContractPathParameter {
        val atPath = (at("paths", path) as? Map<*, *>)?.get("parameters") as? List<*> ?: emptyList<Any?>()
        val atOperation = (at("paths", path, method) as? Map<*, *>)?.get("parameters") as? List<*> ?: emptyList<Any?>()
        val declared = parametersOf(path, atPath + atOperation).filter { it.location == "path" }
        require(declared.size == 1) {
            "$method $path 의 경로 변수가 하나가 아니다: ${declared.map { it.name }} — " +
                "0개면 URL 을 조립할 재료가 없고, 둘 이상이면 어느 것을 치환할지 정할 수 없다"
        }
        return declared.single()
    }

    private fun parametersOf(
        path: String,
        entries: List<Any?>,
    ): List<ContractPathParameter> =
        entries.mapIndexed { index, entry ->
            // 개별 필드는 `error()` 로 끊으면서 **항목 자체를 버리던** 자리다(같은 fail-open).
            val declaration =
                entry as? Map<*, *>
                    ?: error("$path 의 parameters[$index] 가 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $entry")
            ContractPathParameter(
                name = declaration["name"]?.toString() ?: error("$path 의 parameters 에 name 이 없다"),
                location = declaration["in"]?.toString() ?: error("$path 의 parameters 에 in 이 없다"),
                format =
                    (declaration["schema"] as? Map<*, *>)?.get("format")?.toString()
                        ?: error("$path 의 parameters 에 schema.format 이 없다"),
            )
        }

    /** **계약이 선언한 값 자리 전수** — 경로 수준과 오퍼레이션 수준을 **둘 다** 훑는다 (β-05). */
    fun valueSlots(): List<ContractValueSlot> =
        map("paths").entries.flatMap { (rawPath, node) ->
            val path = rawPath.toString()
            val operations =
                node as? Map<*, *>
                    ?: error("계약 paths.$path 가 매핑이 아니다: $node")
            operations.entries.flatMap { (rawKey, child) ->
                val key = rawKey.toString()
                when {
                    key == PARAMETERS_KEY -> {
                        slotsIn(path, method = null, entries = child)
                    }

                    key in HTTP_METHODS -> {
                        (child as? Map<*, *>)?.get(PARAMETERS_KEY)?.let { slotsIn(path, key, it) } ?: emptyList()
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        }

    private fun slotsIn(
        path: String,
        method: String?,
        entries: Any?,
    ): List<ContractValueSlot> {
        val label = if (method == null) path else "$method $path"
        val list = entries as? List<*> ?: error("계약 $label 의 parameters 가 목록이 아니다: $entries")
        return list.mapIndexed { index, entry ->
            val declaration =
                entry as? Map<*, *>
                    ?: error("$label 의 parameters[$index] 가 매핑이 아니다 — 이 파서가 읽을 수 있는 형태가 아니다: $entry")
            ContractValueSlot(
                path = path,
                method = method,
                name = declaration["name"]?.toString() ?: error("$label 의 parameters[$index] 에 name 이 없다"),
                location = declaration["in"]?.toString() ?: error("$label 의 parameters[$index] 에 in 이 없다"),
                schema =
                    declaration["schema"] as? Map<*, *>
                        ?: error("$label 의 parameters[$index] 에 schema 가 없다"),
            )
        }
    }

    /** 그 오퍼레이션의 요청 본문 스키마 이름. 본문을 선언하지 않으면 `null`. */
    fun requestBodySchemaName(
        path: String,
        method: String,
    ): String? {
        val operation = map("paths", path, method)
        val body = operation["requestBody"] as? Map<*, *> ?: return null
        val content = body["content"] as? Map<*, *> ?: error("$method $path 의 requestBody 에 content 가 없다")
        val first =
            content.values.firstOrNull() as? Map<*, *>
                ?: error("$method $path 의 requestBody.content 가 비었다")
        val schema = first["schema"] as? Map<*, *> ?: error("$method $path 의 requestBody 에 schema 가 없다")
        val ref =
            schema["\$ref"]?.toString()
                ?: error("$method $path 의 requestBody 스키마가 \$ref 가 아니다 — 이름으로 부를 수 없다: $schema")
        return ref.substringAfterLast("/")
    }

    /** 스키마의 `additionalProperties`. 「정확히 이 키들뿐」 단언의 근거다. */
    fun schemaAllowsAdditionalProperties(schema: String): Boolean =
        at("components", "schemas", schema, "additionalProperties") as? Boolean
            ?: error("$schema 의 additionalProperties 가 불리언이 아니다")

    /** P-10. `ErrorResponse.detail` union 이 선언한 갈래의 `type` — **선언된 순서 그대로, 전부**. */
    fun errorDetailUnionTypes(): List<String> =
        list("components", "schemas", "ErrorResponse", "properties", "detail", "oneOf")
            .mapIndexed { index, branch ->
                val declaration =
                    branch as? Map<*, *>
                        ?: error(
                            "ErrorResponse.detail 의 oneOf[$index] 가 매핑이 아니다 — " +
                                "이 파서가 읽을 수 있는 형태가 아니다: $branch",
                        )
                declaration["type"]?.toString()
                    ?: error("ErrorResponse.detail 의 oneOf[$index] 에 type 이 없다: $declaration")
            }

    /** JSON 값 하나가 **어느 OpenAPI 타입 갈래로 관측됐는지**. */
    fun observedDetailType(value: Any?): String =
        when (value) {
            is String -> {
                "string"
            }

            is List<*> -> {
                "array"
            }

            else -> {
                error(
                    "detail 이 계약의 어느 갈래로도 읽히지 않는다: ${value?.let { "${it::class.java.name}" } ?: "null"}. " +
                        "계약에 갈래가 늘었다면 이 대응도 함께 늘려라.",
                )
            }
        }

    // ------------------------------------------------------------------ P-36 · P-38 · P-39

    /** **P-36 — 요청 본문의 미디어 타입 키 집합.** */
    fun requestBodyMediaTypes(
        path: String,
        method: String,
    ): Set<String> = map("paths", path, method, "requestBody", "content").keys.map { it.toString() }.toSet()

    /** **P-36 — 스키마가 선언한 속성 이름 집합.** */
    fun schemaPropertyNames(schema: String): Set<String> =
        map("components", "schemas", schema, "properties").keys.map { it.toString() }.toSet()

    /** **P-38 — `x-stored-text-domain`**(2026-08-20 신설). */
    fun storedTextDomain(): StoredTextDomain {
        val node = map("x-stored-text-domain")
        val appliesTo =
            (node["applies_to"] as? List<*> ?: error("x-stored-text-domain.applies_to 가 목록이 아니다"))
                .mapIndexed { index, entry ->
                    val arm =
                        entry as? Map<*, *>
                            ?: error("x-stored-text-domain.applies_to[$index] 가 매핑이 아니다: $entry")
                    StoredTextArm(
                        field = arm["field"]?.toString() ?: error("applies_to[$index] 에 field 가 없다"),
                        measurementStatus =
                            arm["status"]?.toString()
                                ?: error("applies_to[$index] 에 status(측정 상태 표식)가 없다"),
                    )
                }
        require(appliesTo.isNotEmpty()) { "x-stored-text-domain.applies_to 가 비었다 — 잴 팔이 없다" }
        return StoredTextDomain(
            detail = node["detail"]?.toString() ?: error("x-stored-text-domain 에 detail 이 없다"),
            detailShape = node["detail_shape"]?.toString() ?: error("x-stored-text-domain 에 detail_shape 가 없다"),
            status = (node["status"] as? Number)?.toInt() ?: error("x-stored-text-domain.status 가 정수가 아니다"),
            appliesTo = appliesTo,
        )
    }

    /** **P-39 — `x-retired-responses[].status`**(2026-08-20 신설). */
    fun retiredResponseStatuses(): List<String> {
        val entries =
            (at("x-retired-responses") as? List<*>) ?: error("x-retired-responses 가 목록이 아니다")
        require(entries.isNotEmpty()) { "x-retired-responses 가 비었다 — 폐기 목록이 없으면 이 대조는 공허하다" }
        return entries.mapIndexed { index, entry ->
            val retired =
                entry as? Map<*, *> ?: error("x-retired-responses[$index] 가 매핑이 아니다: $entry")
            retired["status"]?.toString() ?: error("x-retired-responses[$index] 에 status 가 없다")
        }
    }

    /** **P-39 — `paths` 전체가 선언한 응답 상태 코드**를 `(경로, 메서드, 상태)` 로 편다. */
    fun declaredResponseStatuses(): List<Triple<String, String, String>> =
        operations().flatMap { (path, method) ->
            responseStatuses(path, method).map { Triple(path, method, it) }
        }

    /** 계약 파일 **어디에든** 있는 확장 노드 이름(`x-…`) 전수. */
    fun extensionNodeNames(): Set<String> {
        val names = keyChains().filterTo(mutableSetOf()) { !it.contains('.') && it.startsWith("x-") }
        require(names.isNotEmpty()) { "계약에서 `x-` 확장 노드를 하나도 찾지 못했다 — 대조가 공허하다" }
        return names
    }

    /** 계약의 **연속된 키 경로** 전수 — `"a"`·`"a.b"`·`"b.c"` 처럼 **어느 깊이에서 시작해도** 된다. */
    fun keyChains(): Set<String> {
        val chains = mutableSetOf<String>()
        collectKeyChains(root, emptyList(), chains)
        require(chains.isNotEmpty()) { "계약에서 키 경로를 하나도 찾지 못했다 — 대조가 공허하다" }
        return chains
    }

    private fun collectKeyChains(
        node: Any?,
        prefix: List<String>,
        into: MutableSet<String>,
    ) {
        when (node) {
            is Map<*, *> -> {
                node.forEach { (key, value) ->
                    val path = prefix + (key?.toString() ?: return@forEach)
                    path.indices.forEach { start -> into += path.subList(start, path.size).joinToString(".") }
                    collectKeyChains(value, path, into)
                }
            }

            is List<*> -> {
                // 경로 조각을 더하지 않는다 — 배열은 이름 있는 층이 아니다(KDoc).
                node.forEach { collectKeyChains(it, prefix, into) }
            }

            else -> {
                Unit
            }
        }
    }

    /** `HealthResponse.checks` 가 정의한 **의존 서비스 키 집합** — 예시 노드에서 파생한다. */
    fun healthCheckKeys(): Set<String> {
        val examples =
            list("components", "schemas", "HealthResponse", "properties", "checks", "examples")
        val keys =
            examples
                .flatMapIndexed { index, example ->
                    val entry = example as? Map<*, *> ?: error("HealthResponse.checks.examples[$index] 가 매핑이 아니다")
                    entry.keys.map { it.toString() }
                }.toSet()
        require(keys.isNotEmpty()) { "HealthResponse.checks.examples 에서 키를 하나도 얻지 못했다 — 대조가 공허하다" }
        return keys
    }

    /** P-11. 스키마 속성의 `const`. */
    fun schemaPropertyConst(
        schema: String,
        property: String,
    ): String = text("components", "schemas", schema, "properties", property, "const")

    // ------------------------------------------------------------------ P-12

    /** **파싱 단계에서 거절돼 필터에 닿지 않는 응답들** — 계약이 열거한 갈래 이름 집합. */
    fun containerRejectedCases(): Set<String> =
        strings(
            "x-global-response-headers",
            "x-phase3-measurement",
            "unreachable_by_filter",
            "cases",
        ).toSet()

    /** 가입이 함께 만드는 작업 공간의 이름. */
    fun defaultWorkspaceName(): String {
        val description = text("paths", "/auth/signup", "post", "description")
        return DEFAULT_WORKSPACE_NAME_PATTERN.find(description)?.groupValues?.get(1)
            ?: error("계약 `/auth/signup` 설명에서 기본 작업 공간 이름을 찾지 못했다 — 앵커 문구가 바뀌었다")
    }

    /** 위 산문에서 값을 집어내는 앵커. 값이 아니라 **찾는 방법**이라 여기 있어도 된다. */
    private val DEFAULT_WORKSPACE_NAME_PATTERN = Regex("이름은 `([^`]+)`")

    /** D-2 조항의 앵커. 마찬가지로 값이 아니라 찾는 방법이다. */
    private val PRECEDENCE_PATTERN = Regex("둘 다 해당하면\\s*\\**\\s*(\\d)")

    /** 계약이 삭제 409 예시에 붙인 **이름**들. 값은 [pathExampleDetail] 이 읽는다. */
    private const val HAS_DOCUMENTS_EXAMPLE = "has_documents"
    private const val LAST_ONE_EXAMPLE = "last_one"

    /** D-2 조항이 사는 경로. 이 파일 안에서만 쓰는 키다. */
    private const val WORKSPACE_ITEM_PATH = "/workspaces/{workspace_id}"

    /** P-12. `x-auth` 아래 스칼라 값. */
    fun authText(key: String): String = text("x-auth", key)

    fun authNumber(key: String): Int = number("x-auth", key)

    fun authStrings(key: String): List<String> = strings("x-auth", key)

    /** 계약이 오퍼레이션 키로 쓰는 HTTP 메서드 어휘(소문자). */
    val HTTP_METHODS: Set<String> = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")

    /** OpenAPI 파라미터 `in` 값 중 쿼리. [queryParameters] 가 이것으로 고른다. */
    private const val QUERY_LOCATION = "query"

    /** OpenAPI 의 파라미터 선언 키. 경로 수준과 오퍼레이션 수준에서 같은 이름이다. */
    private const val PARAMETERS_KEY = "parameters"

    /** `x-service-constraint` 의 방향 키 둘. **이 이름이 곧 경계 방향이다.** */
    private const val MAX_LENGTH_KEY = "max_length"
    private const val MIN_LENGTH_KEY = "min_length"
}

/** 요청 필드 경계의 방향. 계약 `x-service-constraint` 의 키 이름에서 온다. */
enum class ConstraintBound {
    /** 「이하」 — `limit` 을 넘으면 거절된다. */
    UPPER,

    /** 「이상」 — `limit` 미만이면 거절된다. */
    LOWER,
}

/** 계약이 선언한 **값 자리** 하나 — 요청이 값을 실을 수 있는 이름. */
data class ContractValueSlot(
    val path: String,
    val method: String?,
    val name: String,
    val location: String,
    val schema: Map<*, *>,
) {
    /** 실패 메시지용 표기. */
    val label: String get() = "${method?.uppercase() ?: "(경로 수준)"} $path?$name [$location]"
}

/** 계약 `x-input-limits` 의 범위 노드 하나 — 하한·상한·기본값. */
data class InputLimitRange(
    val name: String,
    val min: Int,
    val max: Int?,
    val default: Int,
) {
    /** 하한 미만의 값 하나. 「거절돼야 하는 쪽」의 케이스를 이 값으로 유도한다. */
    val belowMin: Int get() = min - 1

    /** 상한 초과의 값 하나. 상한이 없으면 만들 수 없으므로 `null` 이다. */
    val aboveMax: Int? get() = max?.plus(1)
}

/** 오퍼레이션에 인라인 선언된 쿼리 파라미터 하나. */
data class ContractQueryParameter(
    val name: String,
    val location: String,
    val required: Boolean,
    val schema: Map<*, *>,
) {
    /** 스키마 키워드 하나를 정수로 읽는다. 없으면 `null` — 없는 것과 0 을 섞지 않는다. */
    fun intKeyword(keyword: String): Int? = (schema[keyword] as? Number)?.toInt()
}

/** 계약이 요청 필드 하나에 정한 것 — 어느 층이 어느 축으로 무엇을 재고 무슨 문구를 내는가. */
data class RequestFieldConstraint(
    val field: String,
    val limit: Int,
    val measuredOn: String,
    /** 문자열 하나이거나 문자열 목록이다(`WorkspaceNameRequest.name` 은 둘을 든다). */
    val detail: Any,
    /** 계약이 `x-service-constraint` 의 **키 이름**으로 적은 경계 방향 (β-21). */
    val bound: ConstraintBound,
) {
    /** 상한 필드인가. 관측에서 추론하지 않고 계약에서 읽은 값이다. */
    val upperBound: Boolean get() = bound == ConstraintBound.UPPER

    /** 단일 문구를 기대하는 자리. 목록이면 실패한다 — 어느 것을 고를지는 계약이 정하지 않았다. */
    val singleDetail: String
        get() = detail as? String ?: error("${this.field} 의 detail 이 문자열 하나가 아니다: $detail")

    /** **P-18 — 문구가 여럿인 필드**(`WorkspaceNameRequest.name` 은 빈 값·상한 초과 둘을 든다). */
    val detailList: List<String>
        get() = (detail as? List<*>)?.map { it.toString() } ?: error("${this.field} 의 detail 이 목록이 아니다: $detail")

    /** `measured_on` 이 가리키는 측정 축. 어휘 해석은 [MeasurementAxis] 한 곳에서 한다. */
    val axis: MeasurementAxis get() = MeasurementAxis.ofProse(measuredOn, this.field)

    val measuresNormalized: Boolean get() = axis == MeasurementAxis.NORMALIZED

    val measuresRaw: Boolean get() = axis == MeasurementAxis.RAW
}

/** 계약이 응답 헤더 하나를 선언한 **방식**. */
sealed interface ContractHeaderDeclaration {
    /** `$ref: '#/components/headers/<X>'`. 값의 정본은 그 컴포넌트의 `schema.const`. */
    data class Component(val component: String) : ContractHeaderDeclaration

    /** 경로에 직접 적힌 선언. 값이 계산되는 헤더라 `const` 정본이 없고 [schema] 만 있다. */
    data class Inline(val schema: String) : ContractHeaderDeclaration
}

/** 계약 `x-stored-text-domain` — 저장되는 본문의 **정의역** 조항. */
data class StoredTextDomain(
    val detail: String,
    /** `string` 또는 `array`. [ContractSpec.observedDetailType] 과 **같은 어휘**다. */
    val detailShape: String,
    val status: Int,
    val appliesTo: List<StoredTextArm>,
) {
    /** 오늘 실제로 재야 하는 팔 — `status: measured`. */
    fun measuredArms(): List<StoredTextArm> = appliesTo.filter { it.measurementStatus == MEASURED }

    /** 아직 재지 않은 팔. **비어 있지 않은 것 자체가 마감 목록**이라 지우지 않고 드러낸다. */
    fun pendingArms(): List<StoredTextArm> = appliesTo.filterNot { it.measurementStatus == MEASURED }

    private companion object {
        const val MEASURED = "measured"
    }
}

/** `x-stored-text-domain.applies_to` 한 항목. */
data class StoredTextArm(
    val field: String,
    val measurementStatus: String,
)

/** 계약 경로 수준 `parameters` 한 항목. */
data class ContractPathParameter(
    val name: String,
    val location: String,
    val format: String,
)

/** 무엇을 재는가 — 정규화한 뒤인가 원시 값인가. */
enum class MeasurementAxis {
    NORMALIZED,
    RAW,
    ;

    companion object {
        /** 한국어 산문 쪽(`fields[].measured_on`). 앞머리로 가른다 — 뒤는 근거 주석이다. */
        fun ofProse(
            prose: String,
            label: String,
        ): MeasurementAxis =
            when {
                prose.startsWith("정규화 후") -> NORMALIZED
                prose.startsWith("원시") -> RAW
                else -> error("$label 의 measured_on 산문이 매핑에 없다: $prose")
            }

        /** 토큰 쪽(`x-service-constraint.measured_on`). */
        fun ofToken(
            token: String,
            label: String,
        ): MeasurementAxis =
            when (token) {
                "normalized" -> NORMALIZED
                "raw" -> RAW
                else -> error("$label 의 measured_on 토큰이 매핑에 없다: $token")
            }
    }
}

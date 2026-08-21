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
     * 컴포넌트 갈래만 돌려준다. **인라인 갈래를 버리지는 않는다** — [headerDeclarations] 가
     * 둘을 함께 세고, 인라인은 [inlineHeaderNames] 로 나온다. 사유는 그쪽 KDoc.
     */
    fun headerComponentsByName(): Map<String, String> {
        val components =
            headerDeclarations()
                .mapNotNull { (name, declaration) ->
                    (declaration as? ContractHeaderDeclaration.Component)?.let { name to it.component }
                }.toMap()
        require(components.isNotEmpty()) { "계약의 응답 선언에서 헤더 \$ref 를 하나도 찾지 못했다" }
        return components
    }

    /**
     * **계약이 응답에 선언한 헤더 전부** — 이름 → 선언 갈래(`$ref` 컴포넌트 / 인라인).
     *
     * ## 왜 인라인을 함께 세는가 (게이트 24 codex X24-5)
     *
     * 종전 판은 `$ref` 가 없는 헤더 선언을 `?: return@forEach` 로 **조용히 버렸다.** 그래서
     * "계약의 `$ref` 에서 유도하므로 새 헤더가 생겨도 검사 범위가 저절로 는다"는 이 접근자의
     * 선언이 인라인 헤더에 대해서는 거짓이었다 — **선언한 범위와 실제 도달이 어긋난다.**
     *
     * **오늘 계약에 인라인 헤더는 0건이 아니라 2건이다**(실측 2026-08-19):
     * `POST /documents` 202 의 `Location`, `GET /conversions/{id}/export` 200 의
     * `Content-Disposition`. 둘 다 값이 **계산되는** 헤더라 `const` 로 못박을 수 없어 경로에
     * 직접 적혀 있다. 그러므로 "`$ref` 가 없으면 무조건 실패"는 오늘 바로 빨간불이 되고,
     * 그것은 계약이 잘못돼서가 아니라 이 파서가 갈래를 하나만 알기 때문이다.
     *
     * 그래서 **버리는 대신 갈래로 나눠 센다.** fail-closed 는 다음 네 자리에 건다.
     *
     * 1. 응답·오퍼레이션·헤더 노드가 매핑이 아니면 끊는다(파서가 읽을 줄 모르는 모양).
     * 2. 응답이 `$ref` 면 `components/responses/…` 를 **따라 들어간다.** 종전에는 따라가지
     *    않아 `Unauthorized` 가 든 `WWW-Authenticate` 선언이 이 표에 한 번도 오르지 못했다.
     * 3. 인라인 선언에 `schema` 가 없으면 끊는다 — 값의 계약이 없는 헤더는 검사할 수 없다.
     * 4. 같은 이름이 계약 안에서 서로 다르게 선언되면 끊는다(컴포넌트 ↔ 인라인 혼재 포함).
     *    그 상태에서는 「이 헤더의 계약 값」이 하나로 정해지지 않는다.
     *
     * 그리고 인라인 **집합 자체**를 [inlineHeaderNames] 로 열어, 새 인라인 헤더가 들어오는
     * 커밋이 실패하도록 계약 테스트가 그 집합을 고정한다 — codex 가 지적한 「마감의 강제자」다.
     */
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

    /**
     * `$ref` 컴포넌트가 아니라 **경로에 직접 적힌** 헤더 선언의 이름.
     *
     * 계약 테스트가 이 집합을 고정한다. 새 인라인 헤더가 생기면 그 커밋이 실패하고,
     * 그때 정해야 하는 것은 둘 중 하나다 — 값이 고정이면 `components/headers` 로 옮겨
     * `const` 를 주고, 계산되는 값이면 그 형식을 재는 테스트를 함께 넣는다.
     */
    fun inlineHeaderNames(): Set<String> =
        headerDeclarations()
            .filterValues { it is ContractHeaderDeclaration.Inline }
            .keys

    /**
     * P-4b. 전역 부착 헤더의 **이름 → 계약이 `const` 로 못박은 값**.
     *
     * 값을 `x-global-response-headers.headers` 가 아니라 **컴포넌트 `const`** 에서 읽는다.
     * 전역 절의 값만 읽으면 컴포넌트 `const` 를 바꿔도 테스트가 반응하지 않는다 —
     * 음성 대조 N-3 이 실측으로 드러낸 자리다.
     *
     * 전역 헤더가 인라인으로 선언돼 있으면 **끊는다.** 전역 부착 헤더는 값이 고정이라는 것이
     * 계약의 요지이므로, 그 값이 `const` 밖에 있으면 정본이 사라진 것이다.
     */
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

    /**
     * 응답 컴포넌트가 예시로 못박은 `detail` 문구.
     *
     * 고정 문구를 테스트에 복제하지 않기 위한 자리다 — 복제하면 계약에서 문구가 바뀌어도
     * 옛 값으로 통과한다.
     */
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
        val limit = (entry["limit"] as? Number)?.toInt() ?: error("$field 의 limit 이 정수가 아니다")
        return RequestFieldConstraint(
            field = field,
            limit = limit,
            measuredOn = entry["measured_on"]?.toString() ?: error("$field 의 measured_on 이 없다"),
            detail = entry["detail"] ?: error("$field 의 detail 이 없다"),
            bound = constraintBound(field, limit),
        )
    }

    /**
     * **P-6b — 경계의 방향을 계약에서 읽는다** (β-21).
     *
     * 종전에 이 방향은 **검사 대상 구현에서 추론**됐다: `limit-1`·`limit+1` 중 거절된 쪽으로
     * 상한·하한을 정했다. 그러면 구현이 최대↔최소를 뒤집어도 그 강제자는 초록이다 —
     * 기준이 계약이 아니라 관측 자신이기 때문이다.
     *
     * 계약은 그 방향을 **이미 기계가독으로** 적었다. 스키마 속성의 `x-service-constraint` 의
     * 키 이름이 `max_length` 인가 `min_length` 인가가 방향이다(P-20 이 읽는 같은 노드).
     * `fields[].limit` 옆의 YAML 주석(*"하한. 상한 없음"*)은 사람만 읽을 수 있어 쓰지 않는다.
     *
     * fail-closed 세 자리: 둘 다 없으면 실패, 둘 다 있으면 실패(방향이 하나로 정해지지 않는다),
     * 값이 `fields[].limit` 과 다르면 실패(계약 안의 두 벌이 갈렸다).
     */
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

    /**
     * **P-25 — `x-input-limits` 아래의 `{min, max, default}` 매핑 노드.**
     *
     * `list_limit`·`list_offset` 은 스칼라가 아니라 매핑이라 [inputLimit] 으로 못 읽는다.
     * **기본값까지 읽어야** DL-7(파라미터를 아예 주지 않은 요청)이 성립한다 — 기본값을
     * 코드에 적으면 계약이 그것을 바꿔도 테스트가 옛 값을 요구한다.
     *
     * `max` 는 **없을 수 있다**(`list_offset` 에 상한이 없다). 없는 것을 0 이나
     * `Int.MAX_VALUE` 로 메우지 않고 `null` 로 남긴다 — 메우면 「상한이 사라진 것」과
     * 「상한이 원래 없는 것」이 구분되지 않는다.
     */
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

    /**
     * 오퍼레이션에 인라인으로 선언된 쿼리 파라미터들 — **`in: query` 만** 골라 온다.
     *
     * [pathParameters] 와 별개인 이유가 둘이다. ⑴ 그쪽은 **경로 수준**
     * `paths.<path>.parameters` 를 읽고 이쪽은 **오퍼레이션 수준**이다. ⑵ 그쪽은
     * `schema.format` 을 필수로 요구하는데 정수 파라미터에는 `format` 이 없다 — 그 접근자로
     * 읽으면 「계약이 형식을 안 적었다」로 끊긴다.
     *
     * 스키마를 매핑째로 돌려준다. 여기서 `minimum`·`maximum`·`default` 만 꺼내 두면
     * 계약이 그 자리에 다른 키(`multipleOf` 등)를 더했을 때 이 파서가 조용히 무시한다.
     */
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

    /**
     * **P-16 — `allOf` 합성 스키마의 `required` 를 합쳐 읽는다.**
     *
     * [schemaRequired] 는 `components.schemas.X.required` 하나만 읽으므로 `allOf` 로 조립된
     * 스키마(`WorkspaceListItem`)에서는 **실패한다**. 손으로 합치면 키 집합이 코드에 복제되고,
     * 계약이 갈래를 옮기거나 지워도 테스트가 옛 집합을 요구한다.
     *
     * 갈래 하나만 읽고 나머지를 무시하는 배선은 **하드코딩보다 나쁘다** — "계약에서 읽었다"는
     * 외양이 붙기 때문이다. 그래서 `$ref` 해석까지가 이 접근자의 범위이고, 아무 갈래에서도
     * `required` 를 얻지 못하면 실패한다.
     */
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

    /**
     * **P-17 — 경로에 인라인으로 적힌 응답 예시의 `detail`.**
     *
     * [responseExampleDetail] 은 `components/responses/<컴포넌트>` 만 읽는다. 작업 공간의
     * 문구 예시는 **전부 경로 인라인**이라 그 접근자로는 한 건도 못 읽고, 그러면 문구가
     * 코드에 복제된다 — 규약이 막으려는 바로 그것이다.
     */
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

    /**
     * **P-22 — D-2. 삭제 거절 두 갈래가 **동시에** 해당할 때 계약이 어느 쪽을 내라고 했는가.**
     *
     * 계약이 2026-08-19 에 신설한 조항(`paths./workspaces/{workspace_id}.delete.description`)이다.
     * 종전에는 계약이 침묵해 두 순서가 다 허용됐고, 그중 하나가 조항이 지키겠다고 적은
     * 것을 정확히 깨뜨렸다.
     *
     * 조항은 산문 안에 있으므로 앵커로 집어낸다. **조항이 사라지면 실패한다** — 기본값을
     * 돌려주면 계약에서 순서가 지워져도 테스트가 옛 순서로 통과한다. 매치가 둘 이상이어도
     * 실패한다(`defaultWorkspaceName` 이 `find` 첫 매치를 쓰는 자리에서 관찰된 위험 —
     * 같은 모양의 문장이 앞에 하나 더 생기면 조용히 다른 값을 읽는다).
     *
     * 돌려주는 것은 **예시 이름**이다(값이 아니라 이름 — 규약 §0). 문구 자체는 그 이름으로
     * [pathExampleDetail] 이 읽는다.
     */
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

    /**
     * **P-20 — 스키마 속성에 붙은 `x-service-constraint` 표식.**
     *
     * 같은 상한이 계약 안에 **세 벌** 있다(`x-input-limits` · `fields[].limit` · 이 표식).
     * 셋을 대조하지 않으면 한쪽만 고쳐도 아무 데서도 걸리지 않는다.
     */
    fun serviceConstraint(
        schema: String,
        property: String,
    ): Map<*, *> = map("components", "schemas", schema, "properties", property, "x-service-constraint")

    /**
     * **P-21 — 경로 수준 `parameters` 선언.**
     *
     * 경로 변수 이름을 코드에 복제하면 계약이 이름이나 형식을 바꿔도 테스트가 옛 이름으로
     * URL 을 만든다. 그러면 **엉뚱한 매핑을 재거나 404 를 「소유권 은닉」으로 오독한다.**
     */
    fun pathParameters(path: String): List<ContractPathParameter> =
        parametersOf(path, list("paths", path, "parameters"))

    /**
     * **그 오퍼레이션의 경로 변수 하나** — 경로 수준과 오퍼레이션 수준을 **둘 다** 본다.
     *
     * [pathParameters] 는 경로 수준(`paths.<경로>.parameters`)만 읽는다. 계약이 두 관용을
     * 함께 쓰기 때문에 그것만으로는 부족하다 — 작업 공간·변환 경로는 경로 수준에 적고,
     * `/documents/{document_id}` 는 **오퍼레이션(`delete`) 안에** 적는다. OpenAPI 의 의미도
     * 그렇다: 경로 수준 선언은 그 경로의 모든 오퍼레이션에 적용되고 오퍼레이션 수준이 더한다.
     *
     * 그래서 「이 오퍼레이션의 경로 변수 이름」을 묻는 호출자는 어느 수준에 적혀 있는지
     * 알 필요가 없어야 한다. 수준을 **골라 읽게 하면** 계약이 선언 자리를 옮기는 날 그
     * 호출자만 빨개지고, 고치는 사람은 「테스트가 낡았다」로 읽는다.
     *
     * **정확히 하나**여야 한다. 0개면 URL 을 조립할 재료가 없고, 둘 이상이면 어느 것을
     * 치환해야 하는지 이 함수가 정할 수 없다 — 어느 쪽도 조용히 첫 번째를 고르지 않는다.
     */
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

    /**
     * **계약이 선언한 값 자리 전수** — 경로 수준과 오퍼레이션 수준을 **둘 다** 훑는다 (β-05).
     *
     * 「값 자리」는 요청이 값을 실을 수 있는 이름 하나다 — 쿼리 파라미터와 경로 변수. 값 자리
     * 불변식(`kr.easydoc.api.ValueSlotInvariantReachTest`)이 *"계약이 파라미터를 더하면 자동으로
     * 덮는다"* 고 선언했는데 그 분모는 **경로 두 개가 하드코딩**돼 있었다. 이 접근자가 그
     * 선언만큼의 분모를 준다.
     *
     * `parameters` 노드가 없는 오퍼레이션은 **버리지 않고 건너뛴다** — 없는 것과 빈 목록을
     * 섞지 않으려고 존재 여부만 본다. 노드가 있으면 그 안의 항목은 하나도 버리지 않는다
     * (형태가 다르면 `error()` 로 끊는다 — [parametersOf] 와 같은 규율).
     */
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

    /**
     * 그 오퍼레이션의 요청 본문 스키마 이름. 본문을 선언하지 않으면 `null`.
     *
     * `$ref` 만 해석한다 — 인라인 스키마는 이름이 없어 부를 수 없으므로 `error()` 로 끊는다.
     */
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

    /**
     * P-10. `ErrorResponse.detail` union 이 선언한 갈래의 `type` — **선언된 순서 그대로, 전부**.
     *
     * 종전 판은 `filterIsInstance<Map<*, *>>()` 로 **읽을 줄 모르는 갈래를 조용히 버렸다.**
     * 그 방향은 소비자 단언으로 막히지 않는다(게이트 23 codex C-5): 갈래가 **사라지는**
     * 손상은 개수가 줄어 잡히지만, 스칼라 같은 미지원 노드를 **더하는** 손상은 그 갈래만
     * 조용히 없어져 목록이 그대로라 통과한다. `requiredOf`·`pathParameters` 가 X-4 에서
     * 받은 처방과 같은 형태로 끊는다 — 몇 번째 갈래인지와 실제 노드를 메시지에 담는다.
     */
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

    /**
     * JSON 값 하나가 **어느 OpenAPI 타입 갈래로 관측됐는지**.
     *
     * 계약이 선언한 갈래 이름과 **같은 어휘**로 돌려주는 것이 요점이다. 소비자가
     * `isInstanceOf(String::class.java)` 로 단언하면 「string 갈래」라는 계약의 말과 JVM
     * 타입의 대응이 테스트 코드에 복제되고, 계약이 갈래를 바꿔도 그 복제본은 따라가지 않는다.
     *
     * **모르는 모양이면 끊는다.** 새 갈래가 계약에 생겼는데 여기 대응이 없으면 조용히
     * 통과시키는 대신 실패해서, 대응을 적는 diff 가 리뷰에 올라가게 한다.
     */
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

    /**
     * **P-36 — 요청 본문의 미디어 타입 키 집합.**
     *
     * `POST /documents` 는 **자동 생성물에 없는 유일한 요청 본문**이라(원본 라우터가
     * `Request` 를 직접 읽는다) 계약 파일이 유일한 기록이다. 코드에 `"multipart/form-data"`
     * 를 적으면 계약이 갈래를 바꿔도 컨트롤러의 `consumes` 가 옛 값으로 남는다.
     */
    fun requestBodyMediaTypes(
        path: String,
        method: String,
    ): Set<String> = map("paths", path, method, "requestBody", "content").keys.map { it.toString() }.toSet()

    /**
     * **P-36 — 스키마가 선언한 속성 이름 집합.**
     *
     * multipart 파트 이름이 코드에 복제되는 것을 막는다. `required` 만 읽으면 선택 파트
     * (`title`·`workspace_id`)가 대조 밖에 남는다.
     */
    fun schemaPropertyNames(schema: String): Set<String> =
        map("components", "schemas", schema, "properties").keys.map { it.toString() }.toSet()

    /**
     * **P-38 — `x-stored-text-domain`**(2026-08-20 신설).
     *
     * 거절 문구를 테스트 코드에 복제하면 계약과 구현이 갈려도 자기 사본과 대조해 초록이다.
     * [StoredTextDomain.detailShape] 까지 읽어야 **문자열이 배열로 뒤집히는 것**이 걸리고,
     * [StoredTextDomain.appliesTo] 의 **측정 상태 표식**을 읽어야 "아직 안 잰 팔이 남아
     * 있다"는 사실이 테스트에서 사라지지 않는다.
     */
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

    /**
     * **P-39 — `x-retired-responses[].status`**(2026-08-20 신설).
     *
     * 폐기한 상태 코드를 코드에 적으면 폐기가 하나 늘어도 검사가 늘지 않는다(P-26·P-15 와
     * 같은 형태). **빈 목록이면 실패한다** — 목록이 비면 「전건이 `paths` 에 없다」가
     * 공허하게 참이 되고, 그것이 이 노드가 막으려는 바로 그 상태다.
     */
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

    /**
     * **P-39 — `paths` 전체가 선언한 응답 상태 코드**를 `(경로, 메서드, 상태)` 로 편다.
     *
     * 오퍼레이션 목록은 [operations] 에서 온다 — 여기서 다시 훑으면 어휘가 두 벌이 된다.
     */
    fun declaredResponseStatuses(): List<Triple<String, String, String>> =
        operations().flatMap { (path, method) ->
            responseStatuses(path, method).map { Triple(path, method, it) }
        }

    /**
     * 계약 파일 **어디에든** 있는 확장 노드 이름(`x-…`) 전수.
     *
     * [keyChains] 에서 **점이 없는 것**만 골라 낸다 — 훑기를 두 벌 두지 않는다. 두 벌이면
     * 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다
     * (`_declared_test_count` 가 받은 것과 같은 처방).
     *
     * **비면 실패한다**: 빈 집합과 대조하면 모든 참조가 미해결로 뒤집혀 그 축이 신호가
     * 아니라 잡음이 된다.
     */
    fun extensionNodeNames(): Set<String> {
        val names = keyChains().filterTo(mutableSetOf()) { !it.contains('.') && it.startsWith("x-") }
        require(names.isNotEmpty()) { "계약에서 `x-` 확장 노드를 하나도 찾지 못했다 — 대조가 공허하다" }
        return names
    }

    /**
     * 계약의 **연속된 키 경로** 전수 — `"a"`·`"a.b"`·`"b.c"` 처럼 **어느 깊이에서 시작해도** 된다.
     *
     * ## 왜 이름 집합이 아니라 경로여야 하나 (R-10-①)
     *
     * 부모·자식으로 이어진 참조를 조각으로 나눠 **평탄한 이름 집합**에 대조하면 자식이
     * **계약 어디에든** 있으면 통과한다 — 그 부모 아래에 없어도 초록이다.
     * 실측(2026-08-21): 실재하는 노드 둘을 이어 붙인 가짜 경로(조각은 둘 다 실재하지만 그
     * 부모 아래에 그 자식이 없다)를 주석에 심었더니 **축 B 가 초록**이었다. **노드 이름의 변경·이동이
     * 이 종류의 가장 흔한 형태**이므로, 부모가 살아 있고 자식이 옮겨 간 참조가 정확히
     * 그 구멍으로 빠진다.
     *
     * ## 「어느 깊이에서 시작해도」인 이유
     *
     * 주석은 정본을 가리킬 때 **절대 경로를 적지 않는다** — `x-service-constraint.measured_on`
     * 처럼 중간 노드에서 시작한다(그 노드는 `components.schemas.*.properties.*` 아래 산다).
     * 루트부터의 경로만 모으면 그런 참조가 전부 미해결로 뒤집힌다. 그래서 각 노드의 전체
     * 경로에 대해 **모든 접미사**를 넣는다.
     *
     * ## 배열은 키 층이 아니다
     *
     * 리스트를 지날 때 경로 조각을 **더하지 않는다.** OpenAPI 의 배열은 이름 있는 층이 아니고,
     * 이 저장소의 주석은 리스트 원소를 `fields[].limit`·`fields[0].limit` 로 적는다. 호출자가
     * 그 대괄호를 지우고 물으면 `fields.limit` 로 맞는다(그 정규화는 호출자 몫 —
     * 무엇이 대괄호인지는 참조 문법의 문제이고 계약 구조의 문제가 아니다).
     */
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

    /**
     * `HealthResponse.checks` 가 정의한 **의존 서비스 키 집합** — 예시 노드에서 파생한다.
     *
     * 계약은 이 집합을 두 자리에 적었다: 산문(*"현재 정의된 키: `database`, `queue`"*)과
     * `examples` 둘. **기계가 읽을 수 있는 것은 후자**라 그쪽에서 뽑는다 — 산문을 정규식으로
     * 긁으면 문장이 다듬어질 때마다 흔들린다.
     *
     * 예시 **전체의 합집합**을 쓴다. 하나만 보면 그 예시가 우연히 좁을 때 검사도 좁아지고,
     * 계약이 키를 추가하면서 예시 하나만 고치는 편집이 조용히 지난다.
     *
     * 비면 **실패한다** — 빈 집합과 대조하면 「구현이 아무 키도 내지 않는다」가 통과한다.
     */
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

    /**
     * **파싱 단계에서 거절돼 필터에 닿지 않는 응답들** — 계약이 열거한 갈래 이름 집합.
     *
     * 이 목록과 [ContainerRejectedRequest] 열거자를 **집합으로** 대조한다. 개수만 맞추면
     * 항목이 맞바뀌어도 통과한다 — 계약이 한 갈래를 빼고 다른 갈래를 넣으면 열거자는
     * 옛 갈래를 계속 재면서 초록이다.
     */
    fun containerRejectedCases(): Set<String> =
        strings(
            "x-global-response-headers",
            "x-phase3-measurement",
            "unreachable_by_filter",
            "cases",
        ).toSet()

    /**
     * 가입이 함께 만드는 작업 공간의 이름.
     *
     * **계약이 이 값을 산문 안에 적었다**(`paths./auth/signup.post.description`) — 전용 노드가
     * 없다. 그래서 앵커 문구로 찾아 백틱 안의 값을 읽는다. 계약이 그 문장을 고치면 이
     * 접근자가 **실패한다** — 조용히 기본값을 돌려주면 계약이 값을 바꿔도 테스트가 옛 값을
     * 요구하고, 그 순간 계약이 되는 것은 테스트다.
     */
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

    /**
     * 계약이 오퍼레이션 키로 쓰는 HTTP 메서드 어휘(소문자).
     *
     * 밖으로 여는 이유는 하나다 — 구현 쪽 매핑을 계약과 **같은 단위**로 투영하려면 그
     * 어휘가 한 곳에서 와야 한다. 두 벌이 되면 계약에 메서드가 늘어난 날 구현 쪽 투영이
     * 조용히 옛 어휘로 남는다(codex C-1 과 같은 형태의 결함이다).
     */
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

/**
 * 계약이 선언한 **값 자리** 하나 — 요청이 값을 실을 수 있는 이름.
 *
 * [method] 가 `null` 이면 **경로 수준** 선언이고, OpenAPI 의미대로 그 경로의 **모든**
 * 오퍼레이션에 걸린다. 두 수준을 한 타입으로 다루는 이유는 소비자가 선언 자리를 알 필요가
 * 없어야 하기 때문이다 — 계약이 자리를 옮기는 날 그 소비자만 빨개지고, 고치는 사람은
 * 「테스트가 낡았다」로 읽는다.
 */
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

/**
 * 계약 `x-input-limits` 의 범위 노드 하나 — 하한·상한·기본값.
 *
 * [max] 가 `null` 이면 **계약이 상한을 두지 않았다**는 뜻이다(`list_offset`).
 */
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

/**
 * 오퍼레이션에 인라인 선언된 쿼리 파라미터 하나.
 *
 * [schema] 를 매핑째로 든다 — 계약이 그 안에 무엇을 적었는지는 소비자가 정한다.
 */
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

    /**
     * 단일 문구를 기대하는 자리. 목록이면 실패한다 — 어느 것을 고를지는 계약이 정하지 않았다.
     *
     * `${this.field}` 로 쓴다: 접근자 안에서 맨 `field` 는 **backing field** 를 가리키는
     * 소프트 키워드라 이 프로퍼티에 backing field 를 요구하게 되고 컴파일이 깨진다.
     */
    val singleDetail: String
        get() = detail as? String ?: error("${this.field} 의 detail 이 문자열 하나가 아니다: $detail")

    /**
     * **P-18 — 문구가 여럿인 필드**(`WorkspaceNameRequest.name` 은 빈 값·상한 초과 둘을 든다).
     *
     * [singleDetail] 은 이 자리에서 **설계대로 실패한다.** 목록 접근자가 없으면 이 필드의
     * 문구를 계약에서 읽을 길이 없어 코드에 복제하게 된다.
     */
    val detailList: List<String>
        get() = (detail as? List<*>)?.map { it.toString() } ?: error("${this.field} 의 detail 이 목록이 아니다: $detail")

    /** `measured_on` 이 가리키는 측정 축. 어휘 해석은 [MeasurementAxis] 한 곳에서 한다. */
    val axis: MeasurementAxis get() = MeasurementAxis.ofProse(measuredOn, this.field)

    val measuresNormalized: Boolean get() = axis == MeasurementAxis.NORMALIZED

    val measuresRaw: Boolean get() = axis == MeasurementAxis.RAW
}

/**
 * 계약이 응답 헤더 하나를 선언한 **방식**.
 *
 * 갈래를 타입으로 가르는 이유는 소비자가 둘을 섞지 못하게 하기 위해서다 — 인라인 갈래에는
 * `const` 정본이 없으므로 [ContractSpec.headerConst] 로 값을 물을 수 없고, 물으려 하면
 * 컴파일이 아니라 실행에서 끊긴다는 것이 종전 결함(조용한 무시)과의 차이다.
 */
sealed interface ContractHeaderDeclaration {
    /** `$ref: '#/components/headers/<X>'`. 값의 정본은 그 컴포넌트의 `schema.const`. */
    data class Component(val component: String) : ContractHeaderDeclaration

    /** 경로에 직접 적힌 선언. 값이 계산되는 헤더라 `const` 정본이 없고 [schema] 만 있다. */
    data class Inline(val schema: String) : ContractHeaderDeclaration
}

/**
 * 계약 `x-stored-text-domain` — 저장되는 본문의 **정의역** 조항.
 *
 * 길이는 `x-request-field-constraints` 가, 정의역은 이 절이 정한다. **다른 축이다** —
 * 길이는 "얼마나"이고 정의역은 "그 값이 텍스트로 성립하는가"다.
 */
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

/**
 * 무엇을 재는가 — 정규화한 뒤인가 원시 값인가.
 *
 * **계약이 이 축을 두 어휘로 적는다.** `x-request-field-constraints.fields[].measured_on` 은
 * 한국어 산문이고 스키마의 `x-service-constraint.measured_on` 은 토큰(`normalized`/`raw`)이다.
 * 두 자리를 대조하려면 **매핑을 거쳐야** 하고, 그 매핑이 두 벌이 되면 어휘가 늘어난 날
 * 한쪽만 따라간다. 그래서 여기 한 곳에 둔다.
 *
 * **매핑에 없는 값은 실패다.** 조용히 「다르다」로 떨어뜨리면 계약이 어휘를 넓혔을 때 대조가
 * 거짓 양성을 낸다 — 두 자리가 같은 뜻인데 다르다고 보고하는 쪽이 더 나쁘다.
 */
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

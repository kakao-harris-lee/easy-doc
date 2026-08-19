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
        list("paths", path, "parameters").mapIndexed { index, entry ->
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

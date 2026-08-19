package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **F3 강제자 — 계약이 지목한 다섯 요청 필드에 길이·형식 Bean Validation 을 달지 않는다.**
 *
 * ## 왜 테스트로 만드는가
 *
 * 이 금지는 2026-08-13 에 판정됐지만 **어디에서도 강제되지 않았다.** 원장의 X6 「강제자
 * 축」이 `안 돎` 이었던 이유이고, 마감이 바로 이 커밋이다. 금지를 산문으로만 두면 다음
 * 사람이 `@Size` 를 붙이고, 상태 코드는 그대로 422 라 아무 테스트도 깨지지 않는다 —
 * 바뀌는 것은 `detail` 의 **모양(배열)** 과 **문구(영문)** 뿐이다.
 *
 * `AuthContractTest` 의 S-3·S-6 이 `detail` 타입을 단언하므로 **지금 있는 두 필드**는
 * 그쪽에서도 잡힌다. 이 테스트가 따로 있는 이유는 나머지 셋
 * (`DocumentTextRequest.text`·`ConversionReviewRequest.edited_text`·
 * `WorkspaceNameRequest.name`)이 **만들어지는 순간** 같은 금지를 받게 하기 위해서다.
 * 응답을 재는 테스트는 그 엔드포인트가 생겨야 쓸 수 있지만, 이 검사는 클래스가 생기는
 * 즉시 자동으로 적용된다.
 *
 * ## 무엇을 대조하는가
 *
 * 금지 대상 필드 목록을 **계약에서 읽는다**(`x-request-field-constraints.fields[].field`).
 * 목록을 코드에 적으면 계약에 필드가 추가돼도 검사 범위가 늘지 않는다 — 이 하네스가
 * 반복해 겪은 「선언한 범위와 실제 도달」의 어긋남이다.
 *
 * ## 도달 범위
 *
 * `api` 모듈의 **컴파일된 클래스 전수**를 훑는다. 소스 텍스트를 읽지 않는 이유는 주석·
 * 문자열 안의 `@Size` 를 위반으로 오인하고, 반대로 별칭 import 를 놓치기 때문이다.
 * 클래스가 아직 없는 필드는 「없음」으로 세어 결과에 드러낸다 — **조용히 건너뛰지 않는다.**
 */
class RequestFieldConstraintLayerTest {
    @Test
    @DisplayName("계약이 지목한 다섯 필드에 길이·형식 Bean Validation 애너테이션이 없다")
    fun `다섯 필드에 스키마 층 제약이 없다`() {
        val fields = contractFieldNames()
        assertThat(fields)
            .withFailMessage("계약의 x-request-field-constraints.fields 가 비었다 — 검사 대상이 없다")
            .isNotEmpty()

        val classes = apiClasses()
        val violations = mutableListOf<String>()
        val covered = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        fields.forEach { qualified ->
            val (simpleName, property) = qualified.split('.', limit = 2)
            val target = classes.firstOrNull { it.simpleName == simpleName } ?: return@forEach
            val inspected = inspect(target, property)
            // **클래스를 찾은 것은 도달이 아니다.** 프로퍼티를 실제로 찾았을 때만 센다 —
            // 클래스만 세면 계약 필드 이름이 어느 프로퍼티와도 맞지 않아 0건을 훑어도
            // 「도달했다」가 참이 된다(게이트 20 T-1).
            if (inspected.matchedProperty) {
                covered += qualified
            } else {
                unmatched += "$qualified — 클래스는 있으나 그 이름의 프로퍼티를 찾지 못했다"
            }
            violations += inspected.forbidden.map { "$qualified 에 @$it 가 붙어 있다" }
        }

        assertThat(violations)
            .withFailMessage(
                "계약 F3 위반 — 이 다섯은 서비스 층에서 판정하고 위반은 422 **문자열** detail 이다.\n%s",
                violations.joinToString("\n"),
            ).isEmpty()

        // 클래스는 있는데 프로퍼티를 못 찾았다면 그 필드의 금지는 아무 데서도 강제되지 않는다.
        assertThat(unmatched)
            .withFailMessage("계약 필드와 프로퍼티가 맞지 않는다 — 이 필드들은 검사되지 않았다:\n%s", unmatched.joinToString("\n"))
            .isEmpty()

        // 도달을 결과에 드러낸다. 0 이면 이 테스트는 아무것도 검사하지 않은 것이다.
        assertThat(covered)
            .withFailMessage("계약의 다섯 필드 중 어느 것도 api 모듈에서 찾지 못했다 — 검사 도달이 0 이다")
            .isNotEmpty()
    }

    @Test
    @DisplayName("아직 구현되지 않은 필드는 「없음」으로 드러난다 — 조용히 건너뛰지 않는다")
    fun `미구현 필드가 목록으로 드러난다`() {
        val classes = apiClasses().map { it.simpleName }.toSet()
        val missing = contractFieldNames().filterNot { it.substringBefore('.') in classes }

        // 단언이 아니라 기록이다. 이 목록이 비면 다섯 필드가 전부 구현됐다는 뜻이고,
        // 비지 않았으면 그 필드의 금지는 **아직 아무 데서도 강제되지 않는다.**
        println("F3 검사 — 아직 api 모듈에 클래스가 없는 필드: ${missing.ifEmpty { listOf("없음") }}")
        assertThat(missing).doesNotContainAnyElementsOf(listOf(SIGNUP_EMAIL, SIGNUP_PASSWORD))
    }

    /**
     * **P-7 — 같은 상한이 계약 안에 두 벌 있다.**
     *
     * `x-input-limits` 는 값을, `x-request-field-constraints.fields[].limit` 은 그 값을
     * 어떻게 적용하는지를 든다. 두 절이 나뉜 데는 이유가 있으므로 합치지 않고 **대조로**
     * 지킨다 — 상한을 고치는 사람이 한 곳만 고치는 것이 이 저장소에서 가장 흔한 드리프트다.
     *
     * auth 가 만지는 **두 필드에 한해** 건다. 나머지 셋은 해당 작업 단위에서 같은 대조를
     * 붙인다 — 여기서 다섯을 한꺼번에 거는 것은 근거를 넘는다.
     */
    @Test
    @DisplayName("P-7 계약 안의 두 벌 상한이 서로 같다 (auth 2필드)")
    fun `계약 내부의 이중 선언이 일치한다`() {
        assertThat(ContractSpec.requestFieldConstraint(SIGNUP_EMAIL).limit)
            .withFailMessage("이메일 상한이 x-input-limits 와 fields[].limit 에서 갈렸다")
            .isEqualTo(ContractSpec.inputLimit("max_email_length"))

        assertThat(ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD).limit)
            .withFailMessage("비밀번호 하한이 x-input-limits 와 fields[].limit 에서 갈렸다")
            .isEqualTo(ContractSpec.inputLimit("min_password_length"))
    }

    /**
     * **P-5 — 고위험 하한선 목록이 auth 세 곳을 여전히 지목한다.**
     *
     * 그 목록이 줄어드는 것은 `x-change-policy.invariants` 의 축소다. 목록에서 한 줄이
     * 조용히 사라지면 X-D1 이 그 자리를 놓친다.
     */
    @Test
    @DisplayName("P-5 사적 헤더 하한선 목록이 auth 세 곳을 지목한다")
    fun `하한선 목록이 auth 를 지목한다`() {
        assertThat(ContractSpec.privateResponseHeaderTargets())
            .contains("POST /auth/signup", "POST /auth/login", "GET /auth/me")
    }

    private fun contractFieldNames(): List<String> =
        ContractSpec
            .list("x-request-field-constraints", "fields")
            .filterIsInstance<Map<*, *>>()
            .map { it["field"]?.toString() ?: error("fields[] 항목에 field 가 없다") }

    /**
     * 필드·getter·생성자 파라미터 어디에 붙어도 잡는다 — Kotlin 은 붙는 자리가 여럿이다.
     *
     * ## 계약의 이름과 Kotlin 프로퍼티 이름은 표기가 다르다 (게이트 20 T-1)
     *
     * 계약의 `field` 는 **snake_case** 이고(`ConversionReviewRequest.edited_text`) Kotlin
     * 프로퍼티는 `editedText` 다. 종전 판은 `edited_text`·`getEdited_text` 만 찾아 앞의 두
     * 갈래가 **0건**이 됐고, 실제로 잡는 것은 「그 클래스의 모든 생성자 파라미터를 쓸어
     * 담는」 세 번째 갈래뿐이었다. 그러면 검사는 (아마) 성립하지만 **코드가 적은 이유로
     * 성립하지 않고**, 위반 메시지가 엉뚱한 필드를 지목한다.
     *
     * 지금 걸린 두 필드(`email`·`password`)는 우연히 camelCase 와 같아 이 갈림이 드러나지
     * 않았다. 다음 세 필드가 만들어지는 순간 드러날 자리를 미리 닫는다.
     *
     * 생성자 파라미터는 이름으로 거른다 — 이름을 못 읽는 컴파일 산출물이면(`-parameters`
     * 부재) 그 클래스 전체를 훑던 종전 동작을 유지해 **놓치는 쪽보다 시끄러운 쪽**으로 둔다.
     */
    private fun inspect(
        target: Class<*>,
        property: String,
    ): Inspection {
        val candidates = setOf(property, camelCase(property))
        val getters = candidates.map { "get${it.replaceFirstChar(Char::titlecase)}" }
        var matched = false
        val annotations =
            buildList {
                target.declaredFields
                    .filter { it.name in candidates }
                    .forEach {
                        matched = true
                        addAll(it.annotations.map { annotation -> annotation.annotationClass.java.simpleName })
                    }
                target.declaredMethods
                    .filter { method -> getters.any { method.name.equals(it, ignoreCase = true) } }
                    .forEach {
                        matched = true
                        addAll(it.annotations.map { annotation -> annotation.annotationClass.java.simpleName })
                    }
                target.declaredConstructors.forEach { constructor ->
                    val named = constructor.parameters.any { it.isNamePresent && it.name in candidates }
                    constructor.parameters.forEach { parameter ->
                        if (!named || parameter.name in candidates) {
                            addAll(parameter.annotations.map { it.annotationClass.java.simpleName })
                        }
                    }
                    matched = matched || named
                }
            }
        return Inspection(matched, annotations.filter { it in FORBIDDEN_ANNOTATIONS }.distinct())
    }

    /** `edited_text` → `editedText`. 계약 표기와 Kotlin 표기 사이의 유일한 변환이다. */
    private fun camelCase(snake: String): String =
        snake
            .split('_')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::titlecase) }
            .joinToString("")

    /** 프로퍼티를 실제로 찾았는가와, 거기서 발견한 금지 애너테이션. */
    private data class Inspection(
        val matchedProperty: Boolean,
        val forbidden: List<String>,
    )

    /**
     * `api` 모듈의 컴파일 산출물을 전수 적재한다.
     *
     * 경로는 빌드가 주입한 Gradle 루트에서 유도한다 — 작업 디렉터리에 기대면 IDE 와
     * Gradle 에서 결과가 갈린다.
     */
    private fun apiClasses(): List<Class<*>> {
        val root =
            System.getProperty("easydoc.kotlin.source.root")
                ?: error("시스템 속성 easydoc.kotlin.source.root 이 없다 — 클래스 경로를 유도할 수 없다")
        val classesDir = File(root, "api/build/classes/kotlin/main")
        require(classesDir.isDirectory) { "api 컴파일 산출물이 없다: $classesDir" }
        return classesDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map {
                it
                    .relativeTo(classesDir)
                    .path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }.mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
            .toList()
    }

    private companion object {
        const val SIGNUP_EMAIL = "SignupRequest.email"
        const val SIGNUP_PASSWORD = "SignupRequest.password"

        /**
         * 금지 애너테이션. 계약이 이름으로 지목한 셋에 더해 **같은 일을 하는 것들**을 넣는다 —
         * `@Pattern` 으로 이메일 형식을, `@Length` 로 길이를 스키마 층에서 재면 금지의 취지가
         * 그대로 우회된다. 단순 **존재** 제약(`@NotNull`)은 대상이 아니다: 계약이 「필드 누락」을
         * 배열 `detail` 갈래로 명시했고 그것은 스키마 층 실패가 맞다.
         */
        val FORBIDDEN_ANNOTATIONS =
            setOf("Size", "NotBlank", "NotEmpty", "Email", "Pattern", "Length", "Min", "Max", "Digits")
    }
}

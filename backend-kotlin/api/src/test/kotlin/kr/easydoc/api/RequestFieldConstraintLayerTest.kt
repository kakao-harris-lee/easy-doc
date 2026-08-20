package kr.easydoc.api

import jakarta.validation.Constraint
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
 * ## 금지 애너테이션을 **열거하지 않는다** (2026-08-21, R-4)
 *
 * 종전 판은 이름 아홉 개(`Size`·`NotBlank`·…)를 열거했다. **범위 선언형이고 규칙 ⑶ 이
 * 걸렸다** — 그 목록은 닫히지 않는다(`@CodePointLength`·`@Range`·`@DecimalMin`·직접 만든
 * `ConstraintValidator` 가 전부 같은 일을 한다). 실측으로 확인했다: `@Valid` +
 * `@CodePointLength` 를 심으면 이 스캔이 **초록**이었다.
 *
 * 그리고 열거의 구멍은 바이트 축(`RequestFieldRejectionLayerTest`·
 * `RequestFieldRejectionReachTest`)으로도 메워지지 않는다. 그쪽의 관측창은 경계 ±1 근처라,
 * **계약보다 느슨한 경계**를 가진 제약(`@CodePointLength(max = 100)` 대 계약 상한 50)은
 * 어느 프로브에서도 발화하지 않는다. 두 강제자가 각각 인정한 구멍이 **합성되면 통과**한다.
 *
 * 그래서 열거를 **없앴다.** Bean Validation 제약에는 정의적 성질이 있다 — 그 애너테이션
 * 타입 자신이 [Constraint] 로 (합성 제약이면 전이적으로) 메타 애너테이트돼 있다. 그 성질이
 * 없으면 Bean Validation 이 그것을 제약으로 취급하지 않으므로 **예외가 원리적으로 없다.**
 * 제약 애너테이션은 명세상 `RUNTIME` 유지라 리플렉션으로 보인다.
 *
 * 그러므로 이 검사는 「이름이 목록에 있는가」가 아니라 **「(전이적으로) `@Constraint` 를
 * 지녔는가」**를 묻는다. 범위 선언형이 탐지형으로 바뀐 자리다.
 *
 * **`@Valid` 는 대상이 아니다.** 그것은 제약이 아니라 「이 객체를 검증하라」는 지시이고,
 * 계약이 금지한 것은 제약 애너테이션 자체다. `@Valid` 없는 제약이 무해하다는 실측은
 * 「왜 종전 스캔이 초록이었나」의 설명이지 금지 범위를 좁히는 근거가 아니다.
 *
 * ## 도달 범위
 *
 * `api` 모듈의 **컴파일된 클래스 전수**를 훑는다. 소스 텍스트를 읽지 않는 이유는 주석·
 * 문자열 안의 `@Size` 를 위반으로 오인하고, 반대로 별칭 import 를 놓치기 때문이다.
 * 클래스가 아직 없는 필드는 「없음」으로 세어 결과에 드러낸다 — **조용히 건너뛰지 않는다.**
 */
class RequestFieldConstraintLayerTest {
    @Test
    @DisplayName("계약이 지목한 다섯 필드에 (전이적으로) @Constraint 를 지닌 애너테이션이 0 개다")
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
                "계약 F3 위반 — 이 다섯은 서비스 층에서 판정하고 위반은 422 **문자열** detail 이다.\n" +
                    "  이름이 아니라 **(전이적) @Constraint 보유**로 판정한다 — 경계가 계약보다 느슨해서 " +
                    "바이트 축의 경계 프로브가 발화하지 않는 제약도 여기서 잡힌다.\n%s",
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
        assertThat(missing).doesNotContainAnyElementsOf(listOf(SIGNUP_EMAIL_FIELD, SIGNUP_PASSWORD_FIELD))
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
        assertThat(ContractSpec.requestFieldConstraint(SIGNUP_EMAIL_FIELD).limit)
            .withFailMessage("이메일 상한이 x-input-limits 와 fields[].limit 에서 갈렸다")
            .isEqualTo(ContractSpec.inputLimit("max_email_length"))

        assertThat(ContractSpec.requestFieldConstraint(SIGNUP_PASSWORD_FIELD).limit)
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
            buildList<Class<out Annotation>> {
                target.declaredFields
                    .filter { it.name in candidates }
                    .forEach {
                        matched = true
                        addAll(it.annotations.map { annotation -> annotation.annotationClass.java })
                    }
                target.declaredMethods
                    .filter { method -> getters.any { method.name.equals(it, ignoreCase = true) } }
                    .forEach {
                        matched = true
                        addAll(it.annotations.map { annotation -> annotation.annotationClass.java })
                    }
                target.declaredConstructors.forEach { constructor ->
                    val named = constructor.parameters.any { it.isNamePresent && it.name in candidates }
                    constructor.parameters.forEach { parameter ->
                        if (!named || parameter.name in candidates) {
                            addAll(parameter.annotations.map { it.annotationClass.java })
                        }
                    }
                    matched = matched || named
                }
            }
        return Inspection(matched, annotations.filter { isConstraint(it) }.map { it.simpleName }.distinct())
    }

    /**
     * **이 애너테이션이 Bean Validation 제약인가** — 이름을 보지 않고 성질을 본다.
     *
     * 제약 애너테이션은 자기 타입에 [Constraint] 를 달고 있다(`@Size`·`@Email`·직접 만든
     * 것 전부). **합성 제약**(`@Range` 처럼 다른 제약을 모아 만든 것)은 그 성질이 메타
     * 애너테이션을 한 단계 더 거쳐 나타나므로 전이적으로 따라간다.
     *
     * 순환을 [seen] 으로 끊는다 — `java.lang.annotation` 의 메타 애너테이션들이 서로를
     * 가리켜 재귀가 돌아온다.
     */
    private fun isConstraint(
        type: Class<out Annotation>,
        seen: MutableSet<Class<*>> = mutableSetOf(),
    ): Boolean =
        when {
            // 순환을 끊는다 — 이미 본 타입은 여기서 새 근거를 주지 않는다.
            !seen.add(type) -> false

            type.isAnnotationPresent(Constraint::class.java) -> true

            else -> type.annotations.any { isConstraint(it.annotationClass.java, seen) }
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
        /**
         * 계약이 필드를 지목하는 **경로 문자열**이다. 값이 아니라 이름이다.
         *
         * `_FIELD` 접미사를 뗀 `SIGNUP_PASSWORD` 로 돌리지 마라 — 데이터 보호 스캐너의
         * `SECRET-LITERAL` 규칙은 `password` 로 **끝나는 식별자에 리터럴을 대입하는 줄**을
         * 비밀키 후보로 올린다(`scan_privacy_invariants.py`). 그 규칙은 표기로 누를 수 없어
         * (`UNMARKABLE_RULES`) 이름이 되돌아가는 순간 CI BLOCK 게이트가 다시 빨개진다.
         * 접미사는 스캐너를 피하려고 붙인 것이 아니라 **담긴 것이 비밀번호가 아니라 필드
         * 경로**라는 사실을 이름이 말하게 한 것이고, 규칙이 조용해지는 것은 그 결과다.
         */
        const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"

        // **금지 애너테이션 목록은 없다.** 판정은 `isConstraint` 의 성질 검사다(R-4).
        //
        // 종전에는 이름 아홉 개를 열거했고, 그 목록 밖의 제약(`@CodePointLength`)이 실측으로
        // 통과했다. 목록을 넓히는 것은 다음 항목이 생길 때까지만 참인 조치다.
        //
        // **단순 존재 제약(`@NotNull`)도 이제 걸린다.** 종전 열거는 그것을 일부러 뺐다 —
        // 계약이 「필드 누락」을 배열 `detail` 갈래로 명시했으므로 스키마 층 실패가 맞다는
        // 논리였다. 그러나 `@NotNull` 은 **누락**이 아니라 **명시적 `null`** 에 발화하고,
        // 이 저장소는 그 갈래를 `JsonRequestStrictnessConfig` 의 전역 `Nulls.FAIL` 로 이미
        // 처리한다(`InvalidNullException` → `type: "missing"`). 즉 다섯 필드에 `@NotNull` 을
        // 다는 것은 **두 번째 판정 지점**을 만드는 일이고, 두 지점이 갈리는 날 어느 쪽이
        // 이기는지 알 수 없다. 그러므로 함께 금지하는 편이 옳고, **오늘 다섯 필드에 그것이
        // 붙어 있지 않다는 것을 이 케이스가 확인한다.**
    }
}

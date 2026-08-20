package kr.easydoc.api

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kr.easydoc.api.document.DocumentController
import kr.easydoc.api.support.ConstraintMetadata
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.KClass

/**
 * **F3 구조 강제자 — 계약이 지목한 다섯 요청 필드에 스키마 층 제약이 걸려 있지 않다.**
 *
 * ## 왜 테스트로 만드는가
 *
 * 이 금지는 2026-08-13 에 판정됐지만 **어디에서도 강제되지 않았다.** 금지를 산문으로만 두면
 * 다음 사람이 제약을 붙이고, 상태 코드는 그대로 422 라 아무 테스트도 깨지지 않는다 —
 * 바뀌는 것은 `detail` 의 **모양(배열)** 과 **문구(영문)** 뿐이다.
 *
 * ## 무엇을 대조하는가
 *
 * 대상 필드 목록을 **계약에서 읽는다**(`x-request-field-constraints.fields[].field`).
 * 목록을 코드에 적으면 계약에 필드가 추가돼도 검사 범위가 늘지 않는다.
 *
 * ## 판정은 **엔진에게 묻는다** — 이름도, 자리도 열거하지 않는다
 *
 * 이 파일의 판정은 두 번 갈렸고 그 경위가 그대로 규칙 4 의 사례다.
 *
 * 1. **초판 — 애너테이션 *이름* 아홉 개 열거.** 범위 선언형이고 규칙 ⑶ 이 걸렸다. 실측:
 *    `@Valid` + `@CodePointLength`(목록 밖)를 심으면 **초록**이었다(R-4).
 * 2. **둘째 판 — (전이적) `@Constraint` 보유라는 성질 검사.** 이름 열거는 사라졌지만
 *    **자리의 열거**가 남았다(`declaredFields`·getter·생성자 파라미터 셋). 실측: 클래스 수준
 *    커스텀 제약·`@Validated` + 메서드 파라미터 제약·`META-INF/validation.xml` 세 형태가
 *    모두 **초록**이었다(R-5, stop-time codex 게이트 지적).
 * 3. **현재 — [kr.easydoc.api.support.ConstraintMetadata] 로 엔진 메타데이터를 묻는다.**
 *    `Validator.getConstraintsForClass` 의 서술자 트리는 애너테이션을 훑은 결과가 아니라
 *    **엔진 자신이 아는 제약**이라, 위 세 형태와 컨테이너 원소·프로그램적 매핑까지 포함한다.
 *
 * **리플렉션 스캔은 지웠다(대체).** 실측에서 그것이 잡던 두 형태(`@field:`·`@param:` 제약)를
 * 엔진 질의가 **둘 다** 잡았고, 엔진이 놓치고 리플렉션만 잡는 형태는 **하나도 없었다**.
 * 도달 범위가 진부분집합이므로 함께 둘 근거가 없다 — R-4 에서 두 강제자를 함께 둔 것은
 * 그때 범위가 **달랐기** 때문이다. 표와 절차는 산출물 「C4-R5」 절이다.
 *
 * 남은 리플렉션은 **이름 대응 확인** 하나다([hasProperty]) — 계약의 snake_case 필드 이름이
 * 실재하는 Kotlin 프로퍼티를 가리키는지 보는 것이고, 제약 판정과는 다른 질문이다.
 *
 * ## 대체가 안전한 이유 — **엔진 생존 확인**을 함께 둔다
 *
 * 판정을 엔진에 맡기면 「엔진이 아무것도 못 보는 상태」가 곧 거짓 초록이다. 그래서
 * [`엔진 질의가 제약을 지목한다`] 가 **제품 코드의 실물**(`DocumentController` 의
 * `limit`·`offset` 파라미터 제약 — 계약이 요구한 것)과 **합성 표본**(클래스 수준 제약)을
 * 함께 확인한다. 컨테이너 축의 같은 확인은 `RequestFieldRejectionReachTest` 에 있다.
 *
 * ## 도달 범위
 *
 * `api` 모듈의 **컴파일된 클래스 전수**를 훑는다. 소스 텍스트를 읽지 않는 이유는 주석·
 * 문자열 안의 `@Size` 를 위반으로 오인하고, 반대로 별칭 import 를 놓치기 때문이다.
 * 클래스가 아직 없는 필드는 「없음」으로 세어 결과에 드러낸다 — **조용히 건너뛰지 않는다.**
 */
class RequestFieldConstraintLayerTest {
    @Test
    @DisplayName("계약 필드 이름이 api DTO 의 **실재하는 프로퍼티**를 가리킨다 (도달 대조, 게이트 20 T-1)")
    fun `계약 필드 이름이 프로퍼티에 대응한다`() {
        val fields = contractFieldNames()
        assertThat(fields)
            .withFailMessage("계약의 x-request-field-constraints.fields 가 비었다 — 검사 대상이 없다")
            .isNotEmpty()

        val classes = apiClasses()
        val matched = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        fields.forEach { qualified ->
            val (simpleName, property) = qualified.split('.', limit = 2)
            val target = classes.firstOrNull { it.simpleName == simpleName } ?: return@forEach
            if (hasProperty(target, property)) {
                matched += qualified
            } else {
                unmatched += "$qualified — 클래스는 있으나 그 이름의 프로퍼티를 찾지 못했다"
            }
        }

        // **클래스를 찾은 것은 도달이 아니다.** 계약 필드 이름이 어느 프로퍼티와도 맞지 않으면
        // 아래 R-5 케이스가 「그 필드」가 아니라 엉뚱한 것을 보고 있게 된다(게이트 20 T-1).
        assertThat(unmatched)
            .withFailMessage("계약 필드와 프로퍼티가 맞지 않는다:\n%s", unmatched.joinToString("\n"))
            .isEmpty()
        assertThat(matched)
            .withFailMessage("계약의 다섯 필드 중 어느 것도 api 모듈에서 찾지 못했다 — 도달이 0 이다")
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

    // ================================================================ R-5 — 엔진에게 직접 묻는다

    @Test
    @DisplayName("R-5 엔진이 아는 제약이 계약 다섯 필드의 DTO 에 **0 개**다 (클래스 수준·XML 매핑 포함)")
    fun `엔진 메타데이터에 DTO 제약이 없다`() {
        val targets = contractDtoClasses()
        assertThat(targets)
            .withFailMessage("계약 필드의 DTO 를 api 모듈에서 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

        // **필드 하나로 좁혀 묻지 않는다** — 클래스 전체에 제약이 0 개인지 묻는 편이
        // fail-closed 다. 다른 프로퍼티에 제약이 정당하게 필요해지면 이 단언이 깨져
        // **명시적 판정을 강제**하고, 그것이 옳은 방향이다.
        val findings = targets.flatMap { ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, it) }

        assertThat(findings.map { it.toString() })
            .withFailMessage(
                "엔진이 계약 다섯 필드의 DTO 에서 제약을 발견했다 — F3 위반이다.\n" +
                    "  이 질의는 애너테이션을 훑지 않는다: 클래스 수준 제약·컨테이너 원소·" +
                    "`META-INF/validation.xml` 매핑까지 **엔진 메타데이터**로 본다.\n%s",
                findings.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("R-5 그 DTO 를 **파라미터로 받는 자리**에도 제약이 0 개다 (컨트롤러 메서드 파라미터 갈래)")
    fun `엔진 메타데이터에 파라미터 제약이 없다`() {
        val targets = contractDtoClasses().toSet()
        val owners = classesTakingParameterOf(targets)

        // 분모가 0 이면 이 케이스는 아무것도 훑지 않는다. 컨트롤러가 그 DTO 를 받고 있으므로
        // 0 이 될 수 없고, 0 이면 적재·필터가 조용히 좁아진 것이다.
        assertThat(owners)
            .withFailMessage("계약 DTO 를 파라미터로 받는 api 클래스를 하나도 찾지 못했다 — 컨트롤러가 사라졌거나 적재가 좁아졌다")
            .isNotEmpty()

        val findings =
            owners.flatMap { ConstraintMetadata.parameterConstraintsOn(ConstraintMetadata.standalone, it, targets) }

        assertThat(findings.map { it.toString() })
            .withFailMessage(
                "계약 DTO 를 받는 파라미터에 제약이 붙어 있다 — `@Validated` + 파라미터 제약 갈래이고 F3 위반이다.\n%s",
                findings.joinToString("\n") { "  - $it" },
            ).isEmpty()
    }

    @Test
    @DisplayName("R-5 엔진 질의가 **실제로 제약을 본다** — 제품 코드의 실물과 합성 표본으로 확인한다")
    fun `엔진 질의가 제약을 지목한다`() {
        // ⑴ **제품 코드의 실물.** `GET /documents` 의 `limit`·`offset` 은 계약이 요구한
        //    스키마 층 제약이다(지침 3). 즉 이 저장소에는 「메서드 파라미터 제약」이 실제로
        //    있고, 엔진 질의가 그것을 본다는 것을 합성 표본 없이 확인할 수 있다.
        val controller = ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, DocumentController::class.java)
        assertThat(controller.map { it.toString() })
            .withFailMessage("엔진 질의가 DocumentController 의 파라미터 제약을 보지 못했다 — 위 두 케이스의 0건은 아무 뜻이 없다")
            .isNotEmpty()

        // ⑵ **클래스 수준 제약** — 제품 코드에 표본이 없으므로 합성한다. 리플렉션 스캔이
        //    보지 못하는 자리이고, codex 가 지목한 거짓 초록의 자리다.
        assertThat(ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, ClassLevelProbe::class.java))
            .withFailMessage("클래스 수준 제약을 보지 못했다 — R-5 가 겨눈 자리가 그대로 남는다")
            .isNotEmpty()

        // ⑶ **컨테이너 원소 제약(type-use)은 단언하지 않는다 — 재현하지 못했다.**
        //
        // Kotlin 으로 `List<@MetadataProbe String>` 을 선언해 보니(생성자 프로퍼티·본문
        // 프로퍼티 두 형태) 엔진 메타데이터에 **0건**으로 나왔다(2026-08-21 실측). Kotlin 이
        // 그 자리의 type-use 애너테이션을 제네릭 시그니처로 내보내지 않는 것으로 보인다.
        //
        // 그래서 **엔진 질의가 컨테이너 원소 제약을 덮는지 증명하지 못했다.** 덮는다고
        // 적지 않고, 관측값을 기록으로 남긴다 — 언젠가 Kotlin 이나 엔진이 그것을 내보내기
        // 시작하면 이 수가 0 이 아니게 되고, 그때 단언으로 올리면 된다.
        println(
            "R-5 컨테이너 원소 제약 관측: " +
                ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, ContainerElementProbe::class.java),
        )

        // 과잉 탐지 0 — 제약 없는 DTO 를 제약 있다고 하지 않는다.
        assertThat(ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, UnconstrainedProbe::class.java))
            .isEmpty()
    }

    /** 계약 다섯 필드가 사는 DTO 클래스 중 **api 모듈에 실재하는 것**. */
    private fun contractDtoClasses(): List<Class<*>> {
        val declared = contractFieldNames().map { it.substringBefore('.') }.toSet()
        return apiClasses().filter { it.simpleName in declared }
    }

    /**
     * [targets] 중 하나를 **파라미터 타입으로 받는** api 클래스들.
     *
     * 엔진 질의를 api 전수에 돌리지 않는 이유는 비용이 아니라 **원인 분리**다 — 무관한
     * 클래스의 메타데이터 조립 실패가 이 케이스의 실패로 섞인다. 그 DTO 를 파라미터로 받지
     * 않는 클래스는 그 DTO 의 파라미터 제약을 가질 수 없으므로 좁힘이 근거를 넘지 않는다.
     */
    private fun classesTakingParameterOf(targets: Set<Class<*>>): List<Class<*>> {
        val undecidable = mutableListOf<String>()
        val owners =
            apiClasses().filter { candidate ->
                runCatching {
                    val methods = candidate.declaredMethods.flatMap { it.parameterTypes.asList() }
                    val constructors = candidate.declaredConstructors.flatMap { it.parameterTypes.asList() }
                    (methods + constructors).any { it in targets }
                }.getOrElse {
                    undecidable += candidate.name
                    false
                }
            }
        // **판정 불가는 통과가 아니다.**
        assertThat(undecidable)
            .withFailMessage("파라미터 타입을 읽지 못한 클래스가 있다 — 이 클래스들은 검사받지 않았다: %s", undecidable)
            .isEmpty()
        return owners
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
     * 계약이 적은 필드 이름에 해당하는 프로퍼티가 [target] 에 **실재하는가**.
     *
     * ## 계약의 이름과 Kotlin 프로퍼티 이름은 표기가 다르다 (게이트 20 T-1)
     *
     * 계약의 `field` 는 **snake_case** 이고(`ConversionReviewRequest.edited_text`) Kotlin
     * 프로퍼티는 `editedText` 다. 종전 판은 `edited_text`·`getEdited_text` 만 찾아 앞의 두
     * 갈래가 **0건**이 됐다. 여기서 재는 것은 **이름 대응**뿐이다 — 제약이 붙어 있는지는
     * [kr.easydoc.api.support.ConstraintMetadata] 가 엔진에게 묻는다(R-5).
     *
     * 생성자 파라미터는 이름을 읽을 수 있을 때만 센다. 읽지 못하는 산출물이면
     * (`-parameters` 부재) 필드·getter 갈래가 남는다.
     */
    private fun hasProperty(
        target: Class<*>,
        property: String,
    ): Boolean {
        val candidates = setOf(property, camelCase(property))
        val getters = candidates.map { "get${it.replaceFirstChar(Char::titlecase)}" }
        val inFields = target.declaredFields.any { it.name in candidates }
        val inGetters = target.declaredMethods.any { method -> getters.any { method.name.equals(it, true) } }
        val inConstructors =
            target.declaredConstructors.any { constructor ->
                constructor.parameters.any { it.isNamePresent && it.name in candidates }
            }
        return inFields || inGetters || inConstructors
    }

    /** `edited_text` → `editedText`. 계약 표기와 Kotlin 표기 사이의 유일한 변환이다. */
    private fun camelCase(snake: String): String =
        snake
            .split('_')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::titlecase) }
            .joinToString("")

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

/**
 * R-5 합성 표본용 **커스텀 제약**. 제품 코드가 아니라 이 파일에 산다 — 분모를 오염시키지 않는다.
 *
 * `@Constraint` 를 지니므로 [kr.easydoc.api.RequestFieldConstraintLayerTest] 의 성질 검사와
 * [kr.easydoc.api.support.ConstraintMetadata] 의 엔진 질의가 **둘 다** 이것을 제약으로 본다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [MetadataProbeValidator::class])
annotation class MetadataProbe(
    val message: String = "probe",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** 언제나 통과한다 — 재는 것은 **메타데이터**이고 검증 결과가 아니다. */
class MetadataProbeValidator : ConstraintValidator<MetadataProbe, Any> {
    override fun isValid(
        value: Any?,
        context: ConstraintValidatorContext?,
    ): Boolean = true
}

/** **클래스 수준** 제약 표본. 리플렉션 스캔이 보지 못하는 자리다. */
@MetadataProbe
class ClassLevelProbe(val name: String)

/** **컨테이너 원소** 제약 표본(type-use 자리). */
class ContainerElementProbe {
    val names: List<@MetadataProbe String> = emptyList()
}

/** 제약이 하나도 없는 표본. 과잉 탐지 0 을 재는 자리다. */
class UnconstrainedProbe(val name: String)

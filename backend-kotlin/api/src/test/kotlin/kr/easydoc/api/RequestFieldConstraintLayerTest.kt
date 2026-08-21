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

/** F3 구조 강제자 — 계약이 지목한 다섯 요청 필드에 스키마 층 제약이 걸려 있지 않다. */
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

        println("F3 검사 — 아직 api 모듈에 클래스가 없는 필드: ${missing.ifEmpty { listOf("없음") }}")
        assertThat(missing).doesNotContainAnyElementsOf(listOf(SIGNUP_EMAIL_FIELD, SIGNUP_PASSWORD_FIELD))
    }

    /** P-7 — 같은 상한이 계약 안에 두 벌 있다. */
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

    @Test
    @DisplayName("R-5 엔진이 아는 제약이 계약 다섯 필드의 DTO 에 **0 개**다 (클래스 수준·XML 매핑 포함)")
    fun `엔진 메타데이터에 DTO 제약이 없다`() {
        val targets = contractDtoClasses()
        assertThat(targets)
            .withFailMessage("계약 필드의 DTO 를 api 모듈에서 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다")
            .isNotEmpty()

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
        val controller = ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, DocumentController::class.java)
        assertThat(controller.map { it.toString() })
            .withFailMessage("엔진 질의가 DocumentController 의 파라미터 제약을 보지 못했다 — 위 두 케이스의 0건은 아무 뜻이 없다")
            .isNotEmpty()

        assertThat(ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, ClassLevelProbe::class.java))
            .withFailMessage("클래스 수준 제약을 보지 못했다 — R-5 가 겨눈 자리가 그대로 남는다")
            .isNotEmpty()

        println(
            "R-5 컨테이너 원소 제약 관측: " +
                ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, ContainerElementProbe::class.java),
        )

        assertThat(ConstraintMetadata.constraintsOf(ConstraintMetadata.standalone, UnconstrainedProbe::class.java))
            .isEmpty()
    }

    /** 계약 다섯 필드가 사는 DTO 클래스 중 api 모듈에 실재하는 것. */
    private fun contractDtoClasses(): List<Class<*>> {
        val declared = contractFieldNames().map { it.substringBefore('.') }.toSet()
        return apiClasses().filter { it.simpleName in declared }
    }

    /** [targets] 중 하나를 파라미터 타입으로 받는 api 클래스들. */
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

        assertThat(undecidable)
            .withFailMessage("파라미터 타입을 읽지 못한 클래스가 있다 — 이 클래스들은 검사받지 않았다: %s", undecidable)
            .isEmpty()
        return owners
    }

    /** P-5 — 고위험 하한선 목록이 auth 세 곳을 여전히 지목한다. */
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

    /** 계약이 적은 필드 이름에 해당하는 프로퍼티가 [target] 에 실재하는가. */
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

    /** `api` 모듈의 컴파일 산출물을 전수 적재한다. */
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
        /** 계약이 필드를 지목하는 경로 문자열이다. 값이 아니라 이름이다. */
        const val SIGNUP_EMAIL_FIELD = "SignupRequest.email"
        const val SIGNUP_PASSWORD_FIELD = "SignupRequest.password"
    }
}

/** R-5 합성 표본용 커스텀 제약. 제품 코드가 아니라 이 파일에 산다 — 분모를 오염시키지 않는다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [MetadataProbeValidator::class])
annotation class MetadataProbe(
    val message: String = "probe",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** 언제나 통과한다 — 재는 것은 메타데이터이고 검증 결과가 아니다. */
class MetadataProbeValidator : ConstraintValidator<MetadataProbe, Any> {
    override fun isValid(
        value: Any?,
        context: ConstraintValidatorContext?,
    ): Boolean = true
}

/** 클래스 수준 제약 표본. 리플렉션 스캔이 보지 못하는 자리다. */
@MetadataProbe
class ClassLevelProbe(val name: String)

/** 컨테이너 원소 제약 표본(type-use 자리). */
class ContainerElementProbe {
    val names: List<@MetadataProbe String> = emptyList()
}

/** 제약이 하나도 없는 표본. 과잉 탐지 0 을 재는 자리다. */
class UnconstrainedProbe(val name: String)

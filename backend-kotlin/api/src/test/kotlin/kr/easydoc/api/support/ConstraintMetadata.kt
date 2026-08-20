package kr.easydoc.api.support

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.metadata.ContainerDescriptor
import jakarta.validation.metadata.ElementDescriptor
import jakarta.validation.metadata.ExecutableDescriptor
import jakarta.validation.metadata.MethodType

/**
 * **「이 클래스에 Bean Validation 제약이 걸려 있는가」를 엔진에게 직접 묻는다.**
 *
 * ## 왜 애너테이션을 훑지 않는가 (R-5)
 *
 * F3 구조 가드의 종전 판은 **자리를 열거**했다 — `declaredFields`·getter·생성자 파라미터 셋.
 * 애너테이션 *이름*의 열거는 R-4 에서 없앴지만 *자리*의 열거가 남아 있었고, 규칙 ⑶ 이
 * 그대로 걸렸다. 제약이 선언될 수 있는 자리는 그보다 많다:
 *
 * - **클래스 수준 제약** — DTO 자신에 붙은 커스텀 제약(교차 필드 검증의 표준 수법).
 * - **메서드·생성자 파라미터 제약** — 컨트롤러 메서드의 `@RequestBody` 파라미터에 직접.
 * - **컨테이너 원소 제약** — `List<@Size String>` 같은 type-use 자리.
 * - **애너테이션이 아예 없는 선언** — `META-INF/validation.xml` 의 XML 매핑, 그리고
 *   Hibernate Validator 의 프로그램적 `ConstraintMapping`. **리플렉션으로는 원리적으로
 *   보이지 않는다.**
 *
 * Bean Validation 은 그 질문의 **정본 응답자**를 노출한다 —
 * [Validator.getConstraintsForClass] 가 돌려주는 서술자 트리다. 그것은 애너테이션을 훑은
 * 결과가 아니라 **엔진 자신의 메타데이터**라서 위 형태를 전부 포함한다. 자리 열거가 사라진다.
 *
 * ## 두 층으로 묻는다 — 어느 층이 무엇을 덮는지는 실측했다
 *
 * | 선언 형태 | [standalone] (Spring 없음) | 스프링이 구성한 `Validator` 빈 |
 * |---|---|---|
 * | 애너테이션(클래스 수준·프로퍼티·파라미터·컨테이너 원소) | 본다 | 본다 |
 * | `META-INF/validation.xml` | 본다 | 본다 |
 * | 프로그램적 `ConstraintMapping`(스프링 빈에만 배선) | **못 본다** | **본다** |
 *
 * 표의 정본과 측정 절차는 산출물 `04_kotlin-implementer_documents.md` 「C4-R5」 절이다.
 * [standalone] 을 남기는 이유는 **컨텍스트 없이 돌아 클래스가 생기는 즉시 도는** 축이라서다.
 */
object ConstraintMetadata {
    /**
     * 표준 부트스트랩으로 만든 엔진. **Spring 컨텍스트가 없다.**
     *
     * `Validation.buildDefaultValidatorFactory()` 는 클래스패스의 제공자를 찾고
     * `META-INF/validation.xml` 을 읽는다. 스프링 빈에만 배선된 프로그램적 매핑은 모른다.
     */
    val standalone: Validator by lazy { Validation.buildDefaultValidatorFactory().validator }

    /** 제약 하나가 발견된 자리와 그 애너테이션 타입. */
    data class Finding(
        val where: String,
        val annotation: String,
    ) {
        override fun toString(): String = "$where 에 @$annotation"
    }

    /**
     * [type] 에 엔진이 아는 제약 **전부**. 자리를 열거하지 않고 서술자 트리를 훑는다.
     *
     * 훑는 갈래: 클래스 수준 · 프로퍼티(와 그 컨테이너 원소) · 메서드(파라미터·반환) ·
     * 생성자(파라미터·반환). 이 넷이 [jakarta.validation.metadata.BeanDescriptor] 가 노출하는
     * 전부다 — 「자리 목록」이 아니라 **엔진 API 의 표면 전체**라는 것이 요점이다.
     */
    fun constraintsOf(
        validator: Validator,
        type: Class<*>,
    ): List<Finding> {
        val bean = validator.getConstraintsForClass(type)
        val name = type.simpleName
        return buildList {
            addAll(elementFindings("$name(클래스 수준)", bean))
            bean.constrainedProperties.forEach { property ->
                addAll(elementFindings("$name.${property.propertyName}", property))
                addAll(containerFindings("$name.${property.propertyName}", property))
            }
            bean.getConstrainedMethods(MethodType.GETTER, MethodType.NON_GETTER).forEach { method ->
                addAll(executableFindings("$name#${method.name}", method))
            }
            bean.constrainedConstructors.forEach { constructor ->
                addAll(executableFindings("$name#<init>", constructor))
            }
        }
    }

    /**
     * [owner] 의 실행 가능 요소 중 **파라미터 타입이 [targets] 에 든** 자리의 제약.
     *
     * 컨트롤러가 `@RequestBody @Size(max = …) DocumentTextRequest` 처럼 **DTO 가 아니라
     * 자기 메서드 파라미터**에 제약을 다는 갈래를 잡는다. DTO 쪽 [constraintsOf] 만으로는
     * 보이지 않는다.
     */
    fun parameterConstraintsOn(
        validator: Validator,
        owner: Class<*>,
        targets: Set<Class<*>>,
    ): List<Finding> {
        val bean = validator.getConstraintsForClass(owner)
        val name = owner.simpleName
        return buildList {
            bean.getConstrainedMethods(MethodType.GETTER, MethodType.NON_GETTER).forEach { method ->
                addAll(targetedParameters("$name#${method.name}", method, targets))
            }
            bean.constrainedConstructors.forEach { constructor ->
                addAll(targetedParameters("$name#<init>", constructor, targets))
            }
        }
    }

    private fun targetedParameters(
        label: String,
        executable: ExecutableDescriptor,
        targets: Set<Class<*>>,
    ): List<Finding> =
        executable.parameterDescriptors
            .filter { it.elementClass in targets }
            .flatMap { elementFindings("$label 의 ${it.index}번 파라미터(${it.elementClass.simpleName})", it) }

    private fun executableFindings(
        label: String,
        executable: ExecutableDescriptor,
    ): List<Finding> =
        buildList {
            executable.parameterDescriptors.forEach { parameter ->
                addAll(elementFindings("$label 의 ${parameter.index}번 파라미터", parameter))
                addAll(containerFindings("$label 의 ${parameter.index}번 파라미터", parameter))
            }
            executable.returnValueDescriptor?.let {
                addAll(elementFindings("$label 의 반환값", it))
                addAll(containerFindings("$label 의 반환값", it))
            }
        }

    private fun elementFindings(
        label: String,
        element: ElementDescriptor,
    ): List<Finding> =
        element.constraintDescriptors.map { Finding(label, it.annotation.annotationClass.simpleName ?: "?") }

    /** `List<@Size String>` 같은 type-use 자리. 한 단계만 내려간다 — 중첩 컨테이너는 아래 잔여에 적었다. */
    private fun containerFindings(
        label: String,
        container: ContainerDescriptor,
    ): List<Finding> =
        container.constrainedContainerElementTypes.flatMap { element ->
            elementFindings("$label 의 컨테이너 원소(${element.elementClass.simpleName})", element)
        }
}

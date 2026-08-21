package kr.easydoc.api.support

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.metadata.ContainerDescriptor
import jakarta.validation.metadata.ElementDescriptor
import jakarta.validation.metadata.ExecutableDescriptor
import jakarta.validation.metadata.MethodType

/** 「이 클래스에 Bean Validation 제약이 걸려 있는가」를 엔진에게 직접 묻는다. */
object ConstraintMetadata {
    /** 표준 부트스트랩으로 만든 엔진. Spring 컨텍스트가 없다. */
    val standalone: Validator by lazy { Validation.buildDefaultValidatorFactory().validator }

    /** 제약 하나가 발견된 자리와 그 애너테이션 타입. */
    data class Finding(
        val where: String,
        val annotation: String,
    ) {
        override fun toString(): String = "$where 에 @$annotation"
    }

    /** [type] 에 엔진이 아는 제약 전부. 자리를 열거하지 않고 서술자 트리를 훑는다. */
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

    /** [owner] 의 실행 가능 요소 중 파라미터 타입이 [targets] 에 든 자리의 제약. */
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

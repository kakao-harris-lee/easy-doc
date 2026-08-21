package kr.easydoc.api.support

import org.springframework.core.env.Environment
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File

/** 「이 서버가 실제로 매핑한 오퍼레이션은 무엇인가」를 스프링 엔진에게 직접 묻는다. */
object ServedOperations {
    /** 빌드가 주입하는 Gradle 루트(= `backend-kotlin/`). */
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    private const val MAIN_CLASSES_DIR = "api/build/classes/kotlin/main"

    /** `server.error.path` 가 비었을 때 서블릿 컨테이너가 쓰는 값. */
    private const val DEFAULT_ERROR_PATH = "/error"

    /**
     * 매핑된 (경로, 소문자 메서드) 짝 전부. 짝의 순서·표기는 [ContractSpec.operations] 와 같다 —
     * 두 집합을 그대로 겹치기 때문에 표기가 갈리면 대조가 조용히 공허해진다.
     */
    fun of(
        handlerMapping: RequestMappingHandlerMapping,
        environment: Environment,
    ): Set<Pair<String, String>> {
        val errorPath = environment.getProperty("server.error.path") ?: DEFAULT_ERROR_PATH
        return handlerMapping.handlerMethods
            .filterValues { isProductionClass(it.beanType) }
            .keys
            .flatMap { info ->
                val methods =
                    info.methodsCondition.methods
                        .map { it.name.lowercase() }
                        .ifEmpty { ContractSpec.HTTP_METHODS.toList() }
                info.patternValues
                    .filterNot { it == errorPath }
                    .flatMap { path -> methods.map { method -> path to method } }
            }.toSet()
    }

    /** 그 경로에 매핑된 메서드들. 경로가 매핑되지 않았으면 빈 집합이다. */
    fun methodsOn(
        handlerMapping: RequestMappingHandlerMapping,
        environment: Environment,
        path: String,
    ): Set<String> = of(handlerMapping, environment).filter { it.first == path }.map { it.second }.toSet()

    private fun isProductionClass(type: Class<*>): Boolean {
        val root =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error("시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 프로덕션 클래스를 가려낼 기준점이 없다")
        val classesDir = File(root, MAIN_CLASSES_DIR)
        require(classesDir.isDirectory) { "api 컴파일 산출물이 없다: $classesDir" }
        return File(classesDir, type.name.replace('.', File.separatorChar) + ".class").isFile
    }
}

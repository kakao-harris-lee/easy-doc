package kr.easydoc.api.support

import org.springframework.core.env.Environment
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File

/**
 * **「이 서버가 실제로 매핑한 오퍼레이션은 무엇인가」를 스프링 엔진에게 직접 묻는다.**
 *
 * 손 목록을 두지 않는 이유는 그것이 곧 범위 선언형이라서다 — 새 컨트롤러가 생기면 목록 밖에서
 * 조용히 태어난다. 엔진의 [RequestMappingHandlerMapping.getHandlerMethods] 는 **디스패처가
 * 실제로 쓰는 표**이므로 그 위험이 없다.
 *
 * ## 왜 파일 하나인가
 *
 * 종전에는 이 유도가 `AuthenticationCoverageContractTest` 안에만 있었다. `ValueSlotInvariantReachTest`
 * 가 같은 것을 필요로 하게 됐을 때(β-05 — 「계약이 파라미터를 더하면 자동으로 덮는다」의
 * 분모를 계약 × 실제 매핑으로 만든다) 두 벌을 두면 한쪽만 고쳐지는 날 **서로 다른 표면을
 * 세면서 둘 다 초록**이 된다.
 *
 * ## 걸러 내는 둘
 *
 * ⑴ **테스트 전용 컨트롤러** — 핸들러의 선언 클래스가 `api` 모듈의 main 컴파일 산출물에 없으면
 *    API 표면이 아니다. 클래스 이름 규칙이 아니라 **산출물 위치**로 가르는 이유는, 규칙은
 *    다음 사람이 어기고 위치는 빌드가 정하기 때문이다.
 * ⑵ **서블릿 오류 디스패치 경로** — 컨테이너가 내부적으로 포워딩하는 자리이지 API 가 아니다.
 *    값은 설정에서 읽는다.
 */
object ServedOperations {
    /** 빌드가 주입하는 Gradle 루트(= `backend-kotlin/`). */
    private const val SOURCE_ROOT_PROPERTY = "easydoc.kotlin.source.root"

    private const val MAIN_CLASSES_DIR = "api/build/classes/kotlin/main"

    /** `server.error.path` 가 비었을 때 서블릿 컨테이너가 쓰는 값. */
    private const val DEFAULT_ERROR_PATH = "/error"

    /**
     * 매핑된 (경로, 소문자 메서드) 짝 전부. 짝의 순서·표기는 [ContractSpec.operations] 와 같다 —
     * 두 집합을 그대로 겹치기 때문에 표기가 갈리면 대조가 조용히 공허해진다.
     *
     * 메서드 조건이 빈 매핑은 **모든 메서드**를 받으므로 계약 어휘 전부로 펼친다. 그러면 계약이
     * 선언하지 않은 메서드가 분류 실패로 드러난다.
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

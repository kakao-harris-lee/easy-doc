package kr.easydoc.infrastructure.crypto

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * 테스트 Spring 컨텍스트가 쓰는 **진짜** 저장 암호화 키.
 *
 * ## 왜 이것이 있는가 (게이트 26 조치 1 — codex A-1)
 *
 * 종전에는 `easydoc.encryption.verify-on-startup=false` 라는 **런타임 프로퍼티**로
 * 기동 자기점검을 껐다. 그 프로퍼티는 `@ConfigurationProperties` 의 평범한 필드라
 * Spring 의 모든 프로퍼티 소스에서 바인딩된다 — JVM `-D`, 명령행 인자, 컨테이너
 * 환경변수, `SPRING_APPLICATION_JSON`, 저장소 밖 배포 매니페스트. 즉 **운영자가
 * 저장소 파일을 하나도 건드리지 않고 배포 시점에 끌 수 있었다.**
 *
 * 그것을 지키던 탐지기는 저장소 안 파일 4종만 훑었으므로 위 경로 어디에도 닿지
 * 못했다. `CLAUDE.md` 규칙 4 ⑵ 는 **은폐형(면제 조항)은 넓히지 말고 없애거나
 * 탐지형으로 갈아타라**고 한다. 그래서 스위치를 없앴다.
 *
 * 스위치를 없애면 테스트 컨텍스트도 자기점검을 지나야 하고, 지나려면 **유효한 키**가
 * 있어야 한다. 이 클래스가 그 키를 준다. 결과는 면제의 제거만이 아니다 —
 * privacy-gate R-1 이 *"저장소의 어떤 Spring 컨텍스트도 `verify()` 를 실행하지 않는다"*
 * 로 적은 상태가 함께 닫힌다. 이제 모든 `@SpringBootTest` 가 실제 키로 자기점검을 밟는다.
 *
 * ## 키를 소스에 적지 않는다
 *
 * 값은 **실행 시점 난수**다. 상수로 적으면 그것이 곧 소스에 든 키 재료이고
 * (스캐너 `SECRET-LITERAL`, 프로젝트 `CLAUDE.md` 보안 규칙), 어떤 실제 키와도 같지
 * 않아야 한다는 조건도 난수가 더 잘 만족한다.
 *
 * 검사값(KCV)은 **제품 코드 [KeyCheckValue] 로 계산한다.** 여기서 다시 구현하면 두
 * 값이 갈릴 수 있고, 갈리면 이 fixture 가 제품과 다른 규칙을 검증하게 된다.
 */
object TestEncryptionKeys {
    /** `application.yml` 의 `${EASYDOC_ENCRYPTION_KEY_V1:}` 가 읽는 이름. */
    const val KEY_PROPERTY: String = "EASYDOC_ENCRYPTION_KEY_V1"

    /** `application.yml` 의 `${EASYDOC_ENCRYPTION_KCV_V1:}` 가 읽는 이름. */
    const val CHECK_VALUE_PROPERTY: String = "EASYDOC_ENCRYPTION_KCV_V1"

    /** AES-256. */
    private const val KEY_BYTES = 32

    private val material: ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** base64 32바이트. 이 JVM 안에서만 유효하고 매 실행 달라진다. */
    val keyBase64: String = Base64.getEncoder().encodeToString(material)

    /** 위 키의 검사값. 제품과 **같은 계산**을 쓴다. */
    val checkValue: String = KeyCheckValue.of(SecretKeySpec(material, "AES"))

    /** 기동 자기점검을 통과하는 최소 설정. 프로퍼티 이름은 제품 `application.yml` 이 읽는 것 그대로다. */
    fun properties(): Map<String, Any> = mapOf(KEY_PROPERTY to keyBase64, CHECK_VALUE_PROPERTY to checkValue)
}

/**
 * [TestEncryptionKeys] 를 **모든 테스트 Spring 컨텍스트**에 자동으로 넣는다.
 *
 * 등록은 `infrastructure/src/testFixtures/resources/META-INF/spring.factories` 다.
 * Spring Boot 4.1 이 `org.springframework.boot.EnvironmentPostProcessor` 를 여전히
 * 그 파일에서 적재한다는 것은 `spring-boot-4.1.0.jar` 의 `META-INF/spring.factories`
 * 를 직접 풀어 확인했다(패키지가 Boot 3 의 `org.springframework.boot.env` 에서 옮겨졌다).
 *
 * ## 왜 테스트 클래스마다 `@DynamicPropertySource` 를 쓰지 않는가
 *
 * 오늘 `@SpringBootTest` 는 11개이고 앞으로 는다. 클래스마다 적으면 **새 테스트가
 * 빠뜨리는 것이 기본**이 되고, 빠뜨린 테스트는 기동 실패로 빨개지므로 다음 사람은
 * 그것을 「이 테스트만 키를 안 주면 되는 것」으로 배우게 된다 — 면제를 다시 만드는 길이다.
 *
 * ## 우선순위 — 가장 낮은 자리에 놓는다
 *
 * `addLast` 다. 그래서 실제 환경변수, `@TestPropertySource`·`@DynamicPropertySource`
 * 로 준 값, `@SpringBootTest(properties = …)` 가 **전부 이긴다.** 키가 없을 때의 거부를
 * 재는 테스트는 같은 이름을 빈 값으로 덮어쓰면 된다.
 *
 * ## 제품으로 새지 않는다
 *
 * `testFixtures` 소스셋의 산출물은 `testFixtures(project(...))` 로 명시적으로 당긴
 * **테스트 클래스패스에만** 올라간다. `bootJar` 가 담는 `runtimeClasspath` 에는 들어가지
 * 않는다. 그래도 [requireTestRuntime] 으로 한 겹 더 막는다 — 조용히 넘어가지 않고
 * **던진다**. 조용한 갈래를 두면 「제품에서 이 클래스가 아무것도 안 한다」가 되어,
 * 실수로 실린 사실 자체를 아무도 못 본다.
 */
class TestEncryptionKeyEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        requireTestRuntime()
        environment.propertySources.addLast(
            MapPropertySource(SOURCE_NAME, TestEncryptionKeys.properties()),
        )
    }

    private fun requireTestRuntime() {
        val isTestRuntime = runCatching { Class.forName(TEST_MARKER_CLASS) }.isSuccess
        check(isTestRuntime) {
            "$SOURCE_NAME 이 테스트가 아닌 클래스패스에서 적재됐다. " +
                "이 클래스는 infrastructure 의 testFixtures 소스셋에만 있어야 한다 — " +
                "제품 실행에 실리면 난수 키로 뜨고, 재시작마다 옛 행을 열 수 없게 된다."
        }
    }

    private companion object {
        const val SOURCE_NAME = "easydoc-test-encryption-keys"

        /** 이 클래스패스가 테스트인지 가르는 표식. 테스트 런타임에는 언제나 있다. */
        const val TEST_MARKER_CLASS = "org.junit.jupiter.api.Test"
    }
}

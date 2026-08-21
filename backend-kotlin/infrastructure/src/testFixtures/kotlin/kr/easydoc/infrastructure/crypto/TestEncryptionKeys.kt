package kr.easydoc.infrastructure.crypto

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/** 테스트 Spring 컨텍스트가 쓰는 **진짜** 저장 암호화 키. */
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

/** [TestEncryptionKeys] 를 **모든 테스트 Spring 컨텍스트**에 자동으로 넣는다. */
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

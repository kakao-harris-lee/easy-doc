package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.security.SecureRandom

// 저장 암호화 조립 — **이 모듈이 소유한다.**
//
// `AuthConfiguration`·`LlmProviderConfiguration` 과 같은 자리이고 이유도 같다: 설정값과
// 구현 클래스를 함께 볼 수 있는 모듈이 `infrastructure` 하나뿐이다. `api`·`worker` 는
// `runtimeOnly(project(":infrastructure"))` 라 [AesGcmContentCipher] 타입을 컴파일 시점에
// 보지 못하고, `application` 은 `infrastructure` 를 아예 의존하지 않는다.
//
// 종전에 `api` 의 `EasyDocProperties.CryptoProperties(fernetKey)` 에 있던 `easydoc.crypto.*`
// 는 **아무도 조립할 수 없는 설정**이었고, 그 값이 가리키던 Fernet 자체가 2026-08-12
// 재개발 전환으로 사라졌다. 그래서 옮기는 것이 아니라 **대체한다** — 접두사도
// `easydoc.crypto` 가 아니라 `easydoc.encryption` 이다. 옛 이름을 그대로 쓰면 Fernet 키를
// 넣어 둔 환경변수가 AEAD 키로 읽히고, 그 값은 32바이트가 아니므로 조용히 버려진다.

// 프로필 면제 검증: `CryptoProfileExemptionTest`.

/**
 * 스키마만 적용하고 종료하는 실행 프로필. `api/src/main/resources/application.yml` 의
 * `on-profile: migrate`, `ApiApplication.main` 의 종료 판정, compose 의 `kotlin-migrate`
 * 가 가리키는 것과 같은 이름이다.
 */
const val MIGRATE_PROFILE: String = "migrate"

/** 저장 암호화 설정. 바인딩 접두사는 `easydoc.encryption`. */
@ConfigurationProperties(prefix = "easydoc.encryption")
data class EncryptionProperties(
    val writeKeyVersion: Int = 1,
    val keys: List<EncryptionKeyProperties> = emptyList(),
)

/** 키 한 세대. */
data class EncryptionKeyProperties(
    val version: Int = 0,
    val value: Secret = Secret.EMPTY,
    val kcv: String = "",
)

/** 저장 암호화 빈 조립과 **기동 자기점검**. */
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class CryptoConfiguration {
    private val logger = LoggerFactory.getLogger(CryptoConfiguration::class.java)

    /** 난수원은 여기서 한 번 만들어 넘긴다. */
    @Bean
    fun contentCipher(properties: EncryptionProperties): ContentCipher {
        val cipher =
            AesGcmContentCipher(
                keyMaterial = materialOf(properties),
                writeKeyVersion = properties.writeKeyVersion,
                random = SecureRandom(),
            )
        verify(properties, cipher)
        return cipher
    }

    private fun materialOf(properties: EncryptionProperties): Map<Int, Secret> {
        val material = mutableMapOf<Int, Secret>()
        properties.keys.forEach { entry ->
            // 같은 세대를 두 번 적는 것은 설정 오류다. 뒤엣것이 조용히 이기면 어느 키로
            // 썼는지 알 수 없어지므로, 먼저 적힌 것을 남기고 경고한다(값은 로그에 없다).
            if (material.putIfAbsent(entry.version, entry.value) != null) {
                logger.warn("저장 암호화 키 v{} 가 설정에 두 번 있다. 먼저 적힌 것을 쓴다.", entry.version)
            }
        }
        return material.toMap()
    }

    /**
     * 기동 자기점검. 어긋난 것을 **전부 모아** 한 번에 알린다 — 하나씩 끊으면 고치고 다시
     * 띄우기를 반복하게 되고, 그 사이에 다음 문제를 모른다.
     */
    private fun verify(
        properties: EncryptionProperties,
        cipher: AesGcmContentCipher,
    ) {
        val problems =
            versionRangeProblems(properties) +
                writeKeyProblems(properties, cipher) +
                checkValueProblems(properties, cipher)

        if (problems.isNotEmpty()) {
            throw ConfigurationException(
                buildString {
                    appendLine("저장 암호화 설정이 기동 자기점검을 통과하지 못했다. 앱을 띄우지 않는다.")
                    problems.forEach { appendLine("  - $it") }
                    append("근거: migration-safety-gate I-7 검증 5 · 게이트 25 F-2·F-3·X8.")
                },
            )
        }
        logger.info("저장 암호화 기동 자기점검 통과. 검증한 세대 {}개.", cipher.loadedKeyVersions.size)
    }

    private fun versionRangeProblems(properties: EncryptionProperties): List<String> {
        val declared = properties.keys.map { it.version } + properties.writeKeyVersion
        return declared
            .filterNot { it in EncryptedContent.KEY_VERSION_RANGE }
            .distinct()
            .map {
                "키 세대 번호 $it 이 스키마 도메인(${EncryptedContent.KEY_VERSION_RANGE}) 밖이다 — " +
                    "그 값이 적힌 행은 V4 의 CHECK 에 걸리거나 smallint 에서 깨진다"
            }
    }

    private fun writeKeyProblems(
        properties: EncryptionProperties,
        cipher: AesGcmContentCipher,
    ): List<String> =
        if (properties.writeKeyVersion in cipher.loadedKeyVersions) {
            emptyList()
        } else {
            listOf(
                "쓰기 세대 v${properties.writeKeyVersion} 의 키가 적재되지 않았다 " +
                    "(설정된 세대: ${cipher.loadedKeyVersions.sorted()}). " +
                    "값이 비었거나 base64 32바이트가 아니다 — 이대로는 첫 업로드가 실패하므로 띄우지 않는다",
            )
        }

    private fun checkValueProblems(
        properties: EncryptionProperties,
        cipher: AesGcmContentCipher,
    ): List<String> =
        properties.keys
            .distinctBy { it.version }
            .mapNotNull { entry -> checkValueProblem(entry, cipher) }

    private fun checkValueProblem(
        entry: EncryptionKeyProperties,
        cipher: AesGcmContentCipher,
    ): String? {
        // **값이 빈 세대를 여기서 빼지 않는다** (게이트 26 조치 3 / privacy-gate S-2).
        // 종전에는 `filterNot { it.value.isBlank() }` 로 걸러 냈고, 그래서 회전 뒤 옛 세대의
        // 환경변수가 비면 앱이 정상 기동한 채 그 세대의 행 전량이 읽히지 않았다.
        // 관대함의 원래 근거(자리표시자만으로 뜨는 개발 기동)는 쓰기 키 fail-fast 로 이미 소멸했다.
        if (entry.value.isBlank()) {
            return blankValueProblem(entry)
        }

        // 값이 비어 있지 않은데 적재되지 않았다(computed == null) = base64 가 아니거나 길이가
        // 다르다. 종전에는 경고 한 줄이었고, 그래서 오설정이 첫 업로드까지 조용했다.
        val computed = cipher.checkValueOf(entry.version)

        return when {
            computed == null -> {
                "키 v${entry.version} 의 값이 base64 32바이트가 아니다 — 이 세대는 실리지 않는다"
            }

            entry.kcv.isBlank() -> {
                "키 v${entry.version} 에 kcv 가 없다. 이 키의 검사값은 $computed 다 — " +
                    "값이 맞는지 확인하고 설정에 적어라(키와 다른 자리에 적는다)"
            }

            !KeyCheckValue.matches(entry.kcv, computed) -> {
                "키 v${entry.version} 의 kcv 가 설정값과 다르다(설정 ${entry.kcv.trim()} / 실제 $computed) — " +
                    "이 자리에 다른 키가 들어와 있다. 이대로 쓰면 그 행은 옛 키로 열리지 않는다"
            }

            else -> {
                null
            }
        }
    }

    /** 값이 빈 세대. 두 갈래를 **다른 문구로** 알린다 — 운영자가 무엇을 잃었는지가 다르다. */
    private fun blankValueProblem(entry: EncryptionKeyProperties): String =
        if (entry.kcv.isBlank()) {
            "키 v${entry.version} 에 값도 kcv 도 없다 — 내용 없는 세대 선언은 두지 않는다. " +
                "이 세대를 설정에서 지우거나 값과 kcv 를 함께 채워라"
        } else {
            "키 v${entry.version} 의 kcv(${entry.kcv.trim()})는 적혀 있는데 값이 비었다 — " +
                "이 세대는 적재되지 않고, 이 세대로 쓴 행은 한 건도 읽히지 않는다. " +
                "환경변수가 빠졌는지 확인하라"
        }
}

package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

/**
 * 저장 암호화 설정. 바인딩 접두사는 `easydoc.encryption`.
 *
 * ```yaml
 * easydoc:
 *   encryption:
 *     write-key-version: 1
 *     keys:
 *       - version: 1
 *         value: ${EASYDOC_ENCRYPTION_KEY_V1:}   # base64 32바이트 — 비밀, .env/환경변수
 *         kcv: ${EASYDOC_ENCRYPTION_KCV_V1:}     # 키 검사값 12자리 hex — 비밀 아님
 * ```
 *
 * ## 왜 `Map<Int, Secret>` 이 아니라 목록인가
 *
 * 세대→키는 지도(map)가 자연스럽지만, 이 저장소의 `toString()` 게이트
 * (`SensitiveToStringReachTest`)가 제품 `data class` 의 주 생성자를 따라 들어가 표본을
 * 만든다. 그 탐지기는 **모르는 타입을 만나면 끊는다** — 조용히 건너뛰면 그 타입을 쓰는
 * DTO 가 통째로 검사 밖에 남기 때문이다(`GeneratedToStringProbes` KDoc). 지도 갈래는
 * 아직 없으므로, 게이트를 넓히는 대신 **게이트가 이미 다루는 형태**(목록 + 제품 타입)로
 * 적는다. 넓히는 쪽이 옳다면 그 판단은 게이트 소유 레인의 몫이다.
 *
 * @property writeKeyVersion 새 암호문에 쓰는 세대. 회전은 **새 세대를 [keys] 에 더하고 이
 *   값을 올리는 것**이고, 옛 세대를 목록에 남겨 두는 한 옛 행은 계속 읽힌다.
 * @property verifyOnStartup 기동 자기점검을 돌릴지. [CryptoConfiguration] KDoc 의
 *   「기동을 막는다」 절이 이 값을 왜 두는지와 누가 끌 수 있는지를 적었다.
 */
@ConfigurationProperties(prefix = "easydoc.encryption")
data class EncryptionProperties(
    val writeKeyVersion: Int = 1,
    val keys: List<EncryptionKeyProperties> = emptyList(),
    val verifyOnStartup: Boolean = true,
)

/**
 * 키 한 세대.
 *
 * @property version `documents.key_version`·`conversions.key_version` 에 적히는 번호.
 *   [EncryptedContent.KEY_VERSION_RANGE] 안이다 — 컬럼이 `smallint` 이고 `V4` 가 `> 0` 을 강제한다.
 * @property value base64 로 인코딩한 32바이트. **환경변수로만 준다** — 코드·YAML 리터럴에
 *   키를 적지 않는다(프로젝트 `CLAUDE.md` 보안 규칙, 스캐너 `SECRET-LITERAL`).
 *   [Secret] 으로 받아 `toString()`·설정 덤프 어디에도 평문이 실리지 않게 한다.
 * @property kcv [KeyCheckValue] 12자리 hex. **비밀이 아니다** — 배포 파이프라인이 대조할 수
 *   있어야 하므로 키와 **다른 자리**에 적는다. 비어 있으면 기동 자기점검이 실패한다.
 */
data class EncryptionKeyProperties(
    val version: Int = 0,
    val value: Secret = Secret.EMPTY,
    val kcv: String = "",
)

/**
 * 저장 암호화 빈 조립과 **기동 자기점검**.
 *
 * ## 기동을 막는다 (게이트 25 F-2·F-3·X8, 리더 판정 2026-08-19)
 *
 * 종전 규약은 `AuthProperties` 를 따라 *"기동은 막지 않고 그 값이 필요한 요청만 503"* 이었다.
 * 그 규약을 여기서만 뒤집는 이유는 **오설정이 드러나는 시점**이 다르기 때문이다.
 *
 * - JWT 비밀키가 없으면 첫 로그인이 503 이 되고, 고친 뒤 다시 로그인하면 끝이다. 잃는 것이 없다.
 * - 저장 암호화 키가 없거나 **틀리면** 첫 업로드가 503 이 되거나(키 부재), 더 나쁘게는
 *   **성공한 채로 열 수 없는 행을 남긴다**(키가 틀림). 뒤엣것은 되돌릴 수 없다.
 *
 * 그래서 기동 시점에 세 가지를 확인하고, 하나라도 어긋나면 **앱을 띄우지 않는다**.
 *
 * 1. 세대 번호가 스키마 도메인 안인가([EncryptedContent.KEY_VERSION_RANGE]) — `V4` 의 CHECK 와 같은 범위.
 * 2. 쓰기 세대의 키가 실제로 적재됐는가(F-2).
 * 3. 적재된 세대마다 설정의 [EncryptionKeyProperties.kcv] 가 계산값과 같은가(F-3).
 *
 * ## [EncryptionProperties.verifyOnStartup] 은 면제 조항인가
 *
 * 이 스위치를 끄는 자리는 **하나뿐이다** — `build.gradle.kts` 가 모든 테스트 태스크에 넣는
 * 시스템 속성이다. 제품 설정(`api`·`worker` 의 `application.yml`, compose, 배포 매니페스트)
 * 어디에도 이 키가 없어야 하고, 그 사실 자체를 `CryptoStartupVerificationTest` 가 확인한다.
 * 스위치를 두면서 **그 스위치가 제품에 새지 않는지 검사하는 장치**를 함께 두는 것이 이
 * 저장소가 면제 조항에 요구하는 형태다(`CLAUDE.md` 규칙 4 — 은폐형은 탐지형으로 갈아탄다).
 */
@Configuration(proxyBeanMethods = false)
class CryptoConfiguration {
    private val logger = LoggerFactory.getLogger(CryptoConfiguration::class.java)

    /**
     * 난수원은 여기서 한 번 만들어 넘긴다.
     *
     * [SecureRandom] 인스턴스를 매 암호화마다 새로 만들면 시딩 비용이 요청 경로에 붙고,
     * 일부 플랫폼에서는 엔트로피 고갈로 블로킹된다. 하나를 공유해도 안전하다 —
     * `nextBytes` 는 스레드 안전이다.
     */
    @Bean
    fun contentCipher(properties: EncryptionProperties): ContentCipher {
        val cipher =
            AesGcmContentCipher(
                keyMaterial = materialOf(properties),
                writeKeyVersion = properties.writeKeyVersion,
                random = SecureRandom(),
            )
        if (properties.verifyOnStartup) {
            verify(properties, cipher)
        } else {
            logger.warn("저장 암호화 기동 자기점검을 건너뛴다. 테스트 실행이 아니면 설정 오류다.")
        }
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
     *
     * 메시지에는 **세대 번호와 검사값만** 넣는다. 키 재료는 한 조각도 들어가지 않는다.
     * 검사값을 넣는 이유는 [KeyCheckValue] KDoc 에 있다 — 비밀이 아니고, 이 값이 없으면
     * 운영자가 새 세대의 설정을 처음 적을 방법이 없다.
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
                    "값이 비었거나 base64 32바이트가 아니다 — 이대로 뜨면 첫 업로드가 503 이 된다",
            )
        }

    private fun checkValueProblems(
        properties: EncryptionProperties,
        cipher: AesGcmContentCipher,
    ): List<String> =
        properties.keys
            .filterNot { it.value.isBlank() }
            .distinctBy { it.version }
            .mapNotNull { entry -> checkValueProblem(entry, cipher) }

    private fun checkValueProblem(
        entry: EncryptionKeyProperties,
        cipher: AesGcmContentCipher,
    ): String? {
        // 값이 비어 있지 않은데 적재되지 않았다 = base64 가 아니거나 길이가 다르다.
        // 종전에는 경고 한 줄이었고, 그래서 오설정이 첫 업로드까지 조용했다.
        val computed =
            cipher.checkValueOf(entry.version)
                ?: return "키 v${entry.version} 의 값이 base64 32바이트가 아니다 — 이 세대는 실리지 않는다"

        return when {
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
}

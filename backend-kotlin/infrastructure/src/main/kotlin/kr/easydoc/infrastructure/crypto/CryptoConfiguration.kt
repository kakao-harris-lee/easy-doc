package kr.easydoc.infrastructure.crypto

import kr.easydoc.application.crypto.ContentCipher
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
 *         value: ${EASYDOC_ENCRYPTION_KEY_V1:}   # base64 32바이트
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
 * ## 기동은 막지 않는다
 *
 * 키가 없거나 잘못돼도 앱은 뜬다(`AuthProperties` 와 같은 규약). 암호화가 필요한 요청만
 * 503 이 되고, 복호화는 단일 예외로 실패한다.
 *
 * @property writeKeyVersion 새 암호문에 쓰는 세대. 회전은 **새 세대를 [keys] 에 더하고 이
 *   값을 올리는 것**이고, 옛 세대를 목록에 남겨 두는 한 옛 행은 계속 읽힌다.
 */
@ConfigurationProperties(prefix = "easydoc.encryption")
data class EncryptionProperties(
    val writeKeyVersion: Int = 1,
    val keys: List<EncryptionKeyProperties> = emptyList(),
)

/**
 * 키 한 세대.
 *
 * @property version `documents.key_version`·`conversions.key_version` 에 적히는 번호.
 * @property value base64 로 인코딩한 32바이트. **환경변수로만 준다** — 코드·YAML 리터럴에
 *   키를 적지 않는다(프로젝트 `CLAUDE.md` 보안 규칙, 스캐너 `SECRET-LITERAL`).
 *   [Secret] 으로 받아 `toString()`·설정 덤프 어디에도 평문이 실리지 않게 한다.
 */
data class EncryptionKeyProperties(
    val version: Int = 0,
    val value: Secret = Secret.EMPTY,
)

/** 저장 암호화 빈 조립. */
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
        val material = mutableMapOf<Int, Secret>()
        properties.keys.forEach { entry ->
            // 같은 세대를 두 번 적는 것은 설정 오류다. 뒤엣것이 조용히 이기면 어느 키로
            // 썼는지 알 수 없어지므로, 먼저 적힌 것을 남기고 경고한다(값은 로그에 없다).
            if (material.putIfAbsent(entry.version, entry.value) != null) {
                logger.warn("저장 암호화 키 v{} 가 설정에 두 번 있다. 먼저 적힌 것을 쓴다.", entry.version)
            }
        }
        return AesGcmContentCipher(
            keyMaterial = material.toMap(),
            writeKeyVersion = properties.writeKeyVersion,
            random = SecureRandom(),
        )
    }
}

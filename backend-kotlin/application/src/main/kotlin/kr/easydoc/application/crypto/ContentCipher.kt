package kr.easydoc.application.crypto

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import java.util.UUID

/**
 * 저장 암호화 포트 — 유스케이스가 바깥 세계에 요구하는 **저장 전 암호화 / 조회 시 복호화**.
 *
 * `application` 은 `infrastructure` 를 의존하지 않는다(계획 §3.2). 실제 AES-256-GCM 조립과
 * 키 조달은 `infrastructure/crypto/AesGcmContentCipher` 가 지고, 이 모듈은 이 인터페이스만
 * 안다. `AuthPorts.kt` 의 `PasswordHasher`·`AccessTokens` 와 같은 자리다.
 *
 * ## 이 인터페이스가 지는 불변식 (`migration-safety-gate` I-7)
 *
 * - **round-trip** — [encrypt] 한 것을 [decrypt] 하면 같은 [PlainBody] 가 나온다.
 * - **변조 거부** — 암호문·태그·결속값 중 한 바이트라도 다르면 [decrypt] 는 실패한다.
 * - **oracle 금지** — 실패는 원인을 구분하지 않는 `DecryptionFailedException` 하나다.
 * - **nonce 재사용 금지** — 같은 평문을 두 번 암호화하면 서로 다른 암호문이 나온다.
 * - **키 회전** — 읽기는 행에 적힌 [EncryptedContent.keyVersion] 으로, 쓰기는 항상
 *   [writeKeyVersion] 으로 한다.
 *
 * ## 결속(binding)을 호출자가 넘기는 이유
 *
 * [encrypt]·[decrypt] 는 암호문뿐 아니라 **그 암호문이 어느 행의 어느 컬럼에 속하는지**를
 * 함께 받는다. 이 둘이 AEAD 의 associated data 로 들어가 바꿔치기를 거부한다
 * ([EncryptedField] KDoc 에 두 공격 갈래가 있다). 호출자가 record 를 잘못 넘기면 복호화가
 * 실패하므로, 이 인자는 "있으면 좋은 문맥"이 아니라 **암호문의 일부**다.
 */
interface ContentCipher {
    /**
     * 지금 쓰기가 쓰는 방식 이름. `encryption_scheme` 컬럼에 그대로 들어간다.
     *
     * 암호문이 아직 없는 행도 이 값을 적어야 한다 — 컬럼이 `NOT NULL` 이고
     * `V3__encryption_scheme_aead.sql` 이 DEFAULT 를 없앴다(거짓 이름이 조용히 채워지는
     * 경로를 막기 위해서다).
     */
    val writeScheme: String

    /**
     * 지금 쓰기가 쓰는 키 세대. `key_version` 컬럼에 그대로 들어간다.
     *
     * 한 행의 암호문 컬럼들이 이 값을 공유한다 — 스키마가 행당 하나만 들고 있기 때문이다
     * ([EncryptedContent] KDoc).
     */
    val writeKeyVersion: Int

    /**
     * 평문을 암호화한다. 매 호출마다 **새 nonce** 를 뽑는다.
     *
     * @param record 이 암호문이 속할 행의 식별자(`documents.id` 또는 `conversions.id`).
     * @param field 이 암호문이 들어갈 컬럼.
     * @throws kr.easydoc.core.exceptions.ConfigurationException 쓰기 키가 설정돼 있지 않다
     *   (→ 503). 기동을 막지 않고 그 값이 필요한 요청만 거절하는 규약은 `AuthProperties`
     *   KDoc 과 같다.
     */
    fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent

    /**
     * 암호문을 연다. 키는 [EncryptedContent.keyVersion] 이 가리키는 세대로 고른다.
     *
     * @throws kr.easydoc.core.exceptions.DecryptionFailedException 어떤 이유로든 열지
     *   못했다. 원인은 구분되지 않는다(I-7 검증 3).
     */
    fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody
}

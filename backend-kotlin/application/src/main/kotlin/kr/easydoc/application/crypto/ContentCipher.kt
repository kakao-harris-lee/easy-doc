package kr.easydoc.application.crypto

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import java.util.UUID

/**
 * 저장 암호화 포트 — 유스케이스가 바깥 세계에 요구하는 **저장 전 암호화 / 조회 시 복호화**.
 *
 * **바이트 짝이 원형이고 문자열 짝은 그 위의 얇은 층이다.** AEAD 는 원래 바이트를 다루고,
 * 봉하는 것 중 하나(업로드 원본 파일)는 어떤 문자 인코딩으로도 해석되지 않는다. 문자열 짝을
 * 여기서 **기본 구현**으로 두면 두 갈래가 같은 nonce 규칙·같은 결속·같은 거절 경로를 지나는
 * 것이 구조로 보장된다 — 어댑터가 UTF-8 변환을 따로 적을 자리가 없다.
 */
interface ContentCipher {
    /** 지금 쓰기가 쓰는 방식 이름. `encryption_scheme` 컬럼에 그대로 들어간다. */
    val writeScheme: String

    /** 지금 쓰기가 쓰는 키 세대. `key_version` 컬럼에 그대로 들어간다. */
    val writeKeyVersion: Int

    /** 평문 바이트를 암호화한다. 매 호출마다 **새 nonce** 를 뽑는다. */
    fun encryptBytes(
        plain: PlainBytes,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent

    /** 암호문을 바이트로 연다. 키는 [EncryptedContent.keyVersion] 이 가리키는 세대로 고른다. */
    fun decryptBytes(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBytes

    /** 평문을 암호화한다. 매 호출마다 **새 nonce** 를 뽑는다. */
    fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent = encryptBytes(PlainBytes(plain.value.toByteArray(Charsets.UTF_8)), record, field)

    /** 암호문을 연다. 키는 [EncryptedContent.keyVersion] 이 가리키는 세대로 고른다. */
    fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody = PlainBody(String(decryptBytes(content, record, field).value, Charsets.UTF_8))
}

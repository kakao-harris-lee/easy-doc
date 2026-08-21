package kr.easydoc.application.crypto

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import java.util.UUID

/** 저장 암호화 포트 — 유스케이스가 바깥 세계에 요구하는 **저장 전 암호화 / 조회 시 복호화**. */
interface ContentCipher {
    /** 지금 쓰기가 쓰는 방식 이름. `encryption_scheme` 컬럼에 그대로 들어간다. */
    val writeScheme: String

    /** 지금 쓰기가 쓰는 키 세대. `key_version` 컬럼에 그대로 들어간다. */
    val writeKeyVersion: Int

    /** 평문을 암호화한다. 매 호출마다 **새 nonce** 를 뽑는다. */
    fun encrypt(
        plain: PlainBody,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent

    /** 암호문을 연다. 키는 [EncryptedContent.keyVersion] 이 가리키는 세대로 고른다. */
    fun decrypt(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): PlainBody
}

package kr.easydoc.core.crypto

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.text.hasUnpairedSurrogate

// 저장 암호화가 다루는 값들 — **평문 래퍼 · 암호문 봉투 · 결속 대상 · 방식 이름**.
//
// ## 여기가 `core` 인 이유
//
// 이 파일에는 알고리즘이 없다. `documents`·`conversions` 행이 **무엇을 들고 있는가**를
// 적은 도메인 타입뿐이고, JCA·JDBC·Spring 을 하나도 모른다. 실제 AES-GCM 조립은
// `infrastructure` 의 어댑터가 하고(`AesGcmContentCipher`), 유스케이스가 요구하는 계약은
// `application` 의 포트가 진다(`ContentCipher`). 계획 §3.2 의 세 자리가 그대로다.
//
// 저장 암호화는 **표준 AEAD 신규 구현**이고 옛 토큰 형식과의 호환 요구가 없다. 요구되는 성질은
// round-trip · 변조 거부 · nonce 재사용 금지 · 복호화 oracle 금지 · 키 회전이다
// (`migration-safety-gate` I-7).

/** **암호화되기 전/복호화된 뒤의 사용자 콘텐츠** 한 조각. */
@JvmInline
value class PlainBody(val value: String) {
    init {
        // 판정은 `core/text/Surrogates.kt` 한 곳이다 — 제목 정제도 **같은 훑기**를 쓴다.
        if (hasUnpairedSurrogate(value)) throw InvalidInputException(UNPAIRED_SURROGATE_MESSAGE)
    }

    /** 길이만 남긴다. 본문은 개인정보 포함 여부와 무관하게 로그 금지다(프로젝트 `CLAUDE.md`). */
    override fun toString(): String = "PlainBody(${value.length}자)"

    companion object {
        /**
         * 거부 문구. **입력값도 위치도 넣지 않는다** — 예외 메시지에 입력을 넣지 않는다는
         * `DomainExceptions.kt` 의 규약이다(그 규약이 메시지를 응답 detail 에 그대로 담아도
         * 되는 근거다).
         */
        const val UNPAIRED_SURROGATE_MESSAGE: String = "문서 본문에 텍스트로 저장할 수 없는 문자가 있습니다"
    }
}

/**
 * **암호화되기 전/복호화된 뒤의 사용자 바이트** 한 조각 — 업로드된 파일 그 자체.
 *
 * [PlainBody] 와 나누는 이유: 저쪽은 문자열이고 짝 없는 서로게이트를 거절하는 **텍스트**
 * 불변식을 진다. 원본 파일(DOCX·HWPX·PDF)은 zip·바이너리라 어떤 문자 인코딩으로도 해석되지
 * 않는다. base64 로 접어 [PlainBody] 에 태우는 길도 있었지만 — 10MB 가 13.3MB 문자열이 되고
 * 다시 13.3MB UTF-8 바이트가 된다 — 값이 아니라 표현을 바꾸는 우회라 타입을 하나 세웠다.
 * 봉인 자체(AES-GCM)는 원래 바이트를 다루므로 어댑터는 오히려 짧아진다.
 */
class PlainBytes(val value: ByteArray) {
    /** 바이트 수. 크기만 묻는 자리가 배열을 복사해 가지 않게 한다. */
    val size: Int get() = value.size

    override fun equals(other: Any?): Boolean = other is PlainBytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** 길이만 남긴다. 내용은 개인정보 포함 여부와 무관하게 로그 금지다(프로젝트 `CLAUDE.md`). */
    override fun toString(): String = "PlainBytes(${value.size}바이트)"
}

// 빈 바이트 배열을 여기서 거절하지 않는다. 「비어 있으면 안 된다」는 **업로드 원본의** 성질이지
// 「봉할 바이트」 일반의 성질이 아니다 — 문자열 짝이 이 타입 위에 서 있으므로(`ContentCipher`)
// 여기서 막으면 빈 문자열을 봉하는 다른 경로가 함께 끊긴다. 원본의 하한은
// `V3` 의 `ck_document_originals_byte_size_positive` 가 마지막 방어선으로 진다.

/** 암호문 한 조각과, 그것을 **다시 열기 위해 같은 행에 함께 적히는 두 값**. */
class EncryptedContent(
    val bytes: ByteArray,
    val scheme: String,
    val keyVersion: Int,
) {
    init {
        // 도메인 타입이 컬럼보다 넓으면 그 틈이 조용히 저장된다(게이트 25 X8 — `key_version = -1`
        // 이 실제로 INSERT 에 성공했다). 마지막 방어선은 `V4` 의 CHECK 이고, 여기는 **그 값이
        // DB 까지 가기 전에** 끊는 자리다. 값 자체는 비밀이 아니므로 메시지에 넣는다.
        require(keyVersion in KEY_VERSION_RANGE) {
            "키 세대 번호가 스키마 도메인($KEY_VERSION_RANGE) 밖이다: $keyVersion"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is EncryptedContent) return false
        return bytes.contentEquals(other.bytes) && scheme == other.scheme && keyVersion == other.keyVersion
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = result * HASH_MULTIPLIER + scheme.hashCode()
        return result * HASH_MULTIPLIER + keyVersion
    }

    /** 바이트 수·방식·키 세대만 남긴다. 암호문 자체는 나가지 않는다. */
    override fun toString(): String = "EncryptedContent(${bytes.size}바이트, $scheme, v$keyVersion)"

    companion object {
        /** 세대 번호가 들어갈 수 있는 범위 — **정본은 여기 하나다.** */
        val KEY_VERSION_RANGE: IntRange = 1..Short.MAX_VALUE.toInt()

        private const val HASH_MULTIPLIER = 31
    }
}

/** 암호문이 들어가는 **컬럼**. AEAD 의 associated data 에 실려 **바꿔치기를 거부**한다. */
enum class EncryptedField(val wireName: String) {
    /** 업로드 원문. `documents.source_text_encrypted` (V1 baseline). */
    DOCUMENT_SOURCE_TEXT("documents.source_text_encrypted"),

    /**
     * 업로드된 **원본 파일 바이트**. `document_originals.file_bytes_encrypted` (V3).
     *
     * [DOCUMENT_SOURCE_TEXT] 와 나뉜 자리다 — 저쪽은 파서가 뽑아낸 텍스트고 이쪽은 파일
     * 그대로다. 원본에는 본문 말고도 작성자·수정 이력·주석이 함께 들어 있어 민감도가 더 높다.
     * 붙여넣기 경로에는 이 열의 행이 아예 없다.
     */
    DOCUMENT_ORIGINAL_BYTES("document_originals.file_bytes_encrypted"),

    /** AI 초안. `conversions.easy_text_encrypted`. */
    CONVERSION_EASY_TEXT("conversions.easy_text_encrypted"),

    /** 마스킹 대응표. `conversions.masked_items_encrypted`. 자리표시자↔원값 표라 최고 민감도다. */
    CONVERSION_MASKED_ITEMS("conversions.masked_items_encrypted"),

    /** 담당자 검수 수정본. `conversions.edited_text_encrypted` (Alembic 0004). */
    CONVERSION_EDITED_TEXT("conversions.edited_text_encrypted"),

    /** 파일럿 검수자의 자유 의견. `conversion_feedback.comment_encrypted` (V2). 본문 조각이 섞일 수 있다. */
    CONVERSION_FEEDBACK_COMMENT("conversion_feedback.comment_encrypted"),
}

/** `encryption_scheme` 컬럼에 들어가는 방식 이름. */
object EncryptionScheme {
    /** 지금 쓰는 유일한 방식. AES-256-GCM · 96비트 난수 nonce · 128비트 태그. */
    const val AES_256_GCM_V1: String = "aes256gcm-v1"
}

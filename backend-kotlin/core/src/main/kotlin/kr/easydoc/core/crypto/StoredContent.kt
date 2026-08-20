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
// ## 2026-08-12 재개발 전환이 바꾼 것
//
// 이 자리는 원래 "Python `cryptography` 의 Fernet 토큰을 Kotlin 이 읽는다"였다. 롤백을
// 포기하면서(master-plan 6.2 · 계획 §4.3 2차 개정) 읽어야 할 옛 암호문이 사라졌고,
// **호환이 아니라 표준 AEAD 신규 구현**이 됐다. 없어진 것은 「Python 과 같은 바이트」이지
// 「암호가 올바른가」가 아니다 — round-trip · 변조 거부 · nonce 재사용 금지 · 복호화
// oracle 금지 · 키 회전은 `migration-safety-gate` I-7 이 그대로 요구한다.

/**
 * **암호화되기 전/복호화된 뒤의 사용자 콘텐츠** 한 조각.
 *
 * 네 컬럼([EncryptedField])이 담는 것을 전부 이 타입으로 받는다 — 원문·AI 초안·검수
 * 수정본은 물론 마스킹 대응표 JSON 도 개인정보라 같은 취급이다.
 *
 * ## 왜 `String` 이 아니라 래퍼인가
 *
 * 암호문과 평문을 같은 `String`·`ByteArray` 로 두면, 저장 시점에 어느 쪽을 넣었는지
 * **타입이 말해 주지 않는다**(kotlin-spring-conventions §4.2 마지막 문단). 평문을 그대로
 * 컬럼에 넣는 회귀는 컴파일러가 잡아 줄 수 있는 종류의 실수이고, 그러라고 두는 타입이다.
 *
 * `toString()` 재정의 사유는 `core/privacy/Masking.kt` 의 「value class 와 toString」 절과
 * 같다 — `@JvmInline value class` 는 컴파일러가 `toString()` 을 만들어 주므로 재정의가
 * 없으면 본문이 로그 한 줄에 그대로 실린다. 길이만 남긴다.
 *
 * ## 짝 없는 서로게이트를 **생성 시점에 거부한다** (게이트 25 X1)
 *
 * 저장 경로는 `String` → UTF-8 바이트 → AES-GCM → 바이트 → `String` 이다. 그런데
 * `String.toByteArray(UTF_8)` 는 **짝 없는 UTF-16 서로게이트를 인코딩할 수 없어 `?`(U+003F)로
 * 바꿔 버린다**(JDK `CharsetEncoder` 의 REPLACE 동작). 실측(JDK 21.0.4): `"x\uD800y"` 를
 * 왕복시키면 `"x?y"` 가 나오고 `equals` 는 거짓이다. 태그 검증은 통과하므로 **인증에는
 * 성공하면서 사용자 문서가 조용히 영구 손상된다.**
 *
 * 이것은 AEAD 의 결함이 아니다 — 손실은 암호화 **이전**, 문자열을 바이트로 바꾸는 자리에서
 * 일어난다. 그래서 고치는 자리도 AEAD 가 아니라 **평문의 정의역**이다. 여기서 거부하면
 * `ContentCipher` 의 round-trip 불변식이 *"[PlainBody] 로 만들 수 있는 모든 값"* 에 대해
 * 전건으로 참이 된다. 거부하지 않으면 그 불변식은 "표본으로 확인한 값들에 대해서만" 참이고,
 * 반례는 손상된 docx/hwpx 를 파서가 훑는 순간(= Phase 4 문서 추출) 들어온다.
 *
 * 대안이었던 「치환을 수용하고 기록한다」를 고르지 않은 이유: 치환은 **비가역**이라
 * 나중에 되돌릴 수 없고, 그 사이에 저장된 행은 원문을 잃은 채 남는다.
 *
 * ## 판정은 `core/text` 에 있고, **제목은 처분이 다르다**
 *
 * 「무엇이 짝 없는 서로게이트인가」는 [kr.easydoc.core.text.hasUnpairedSurrogate] 한 곳이
 * 정한다. 같은 문자를 **제목**은 거절하지 않고 **걷어낸다**
 * ([kr.easydoc.core.document.resolveTitle] — 계약 `x-title-policy.rule`). 처분이 갈리는
 * 사유는 계약 `x-title-policy.x-surrogate-note` 가 적었다 — 제목에서 잃는 것은 라벨
 * 하나이고 본문에서 잃는 것은 문서다. **판정은 하나, 처분은 둘**이며 그 분리를 계약
 * 레인이 음성 대조 N-34·R-3 의 축으로 삼았다.
 *
 * 왜 [InvalidInputException] 인가: 서버 설정이나 저장 상태의 문제가 아니라 **입력이 텍스트로
 * 성립하지 않는다**는 판정이다. HTTP 매핑(422)과 사용자 문구를 어떻게 낼지는 문서 업로드
 * 경로가 생기는 단위에서 계약과 맞춘다 — 지금 고정하는 것은 예외 타입과 메시지뿐이다.
 */
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
 * 암호문 한 조각과, 그것을 **다시 열기 위해 같은 행에 함께 적히는 두 값**.
 *
 * [bytes] 는 `bytea` 컬럼에 그대로 들어가고, [scheme]·[keyVersion] 은 각각
 * `encryption_scheme`·`key_version` 컬럼이다. 세 값이 흩어져 있으면 복호화 시점에
 * 무엇으로 열어야 할지 알 수 없으므로 한 타입으로 묶는다.
 *
 * ## 한 행의 여러 암호문 컬럼은 같은 [scheme]·[keyVersion] 을 공유한다
 *
 * 스키마가 그렇게 생겼다 — `conversions` 는 암호문 컬럼이 셋인데
 * `encryption_scheme`·`key_version` 은 **행당 하나씩**이다. 그래서 한 행을 쓰는 동안
 * 키 세대가 바뀌면 안 되고, 쓰기 세대는 `ContentCipher.writeKeyVersion` 한 곳에서 온다.
 *
 * ## `equals`/`hashCode` 를 손으로 쓰는 이유
 *
 * `data class` 가 만들어 주는 것은 `ByteArray` 에 대해 **참조 동일성**이다. 같은 바이트를
 * 담은 두 인스턴스가 다르다고 나오면 테스트가 조용히 무의미해진다.
 *
 * ## `toString()` 이 암호문을 찍지 않는 이유
 *
 * 암호문도 로그 금지 대상이다. `CLAUDE.md` 보안 규칙의 허용목록은 *"문서 ID·길이·처리
 * 상태까지만"* 이고 암호문은 거기 없다. 암호문이 로그 수집기로 흘러가면 키가 유출된 날
 * 피해 범위가 DB 를 넘어선다.
 */
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
        /**
         * 세대 번호가 들어갈 수 있는 범위 — **정본은 여기 하나다.**
         *
         * 상한은 `key_version` 컬럼이 `smallint` 라 그 최대값이고, 하한 1 은
         * `V4__key_version_domain.sql` 의 `CHECK (key_version > 0)` 과 같은 값이다.
         * 조립 시점 검증(`CryptoConfiguration`)도 이 상수를 쓴다 — 같은 사실을 두 곳에
         * 적으면 갈린다.
         */
        val KEY_VERSION_RANGE: IntRange = 1..Short.MAX_VALUE.toInt()

        private const val HASH_MULTIPLIER = 31
    }
}

/**
 * 암호문이 들어가는 **컬럼**. AEAD 의 associated data 에 실려 **바꿔치기를 거부**한다.
 *
 * ## 왜 컬럼 이름까지 결속하는가
 *
 * 태그 검증만으로는 "이 바이트가 이 키로 만들어졌다"까지만 알 수 있다. 같은 키로 만든
 * **다른 자리의 암호문**을 복사해 넣으면 태그는 통과한다. 실제로 위험한 두 갈래:
 *
 * - 같은 행의 다른 컬럼 — `easy_text_encrypted`(AI 초안)를 `edited_text_encrypted`
 *   (담당자 검수본) 자리에 옮기면 수정률 KPI 의 기준선이 조작된다.
 * - 다른 행의 같은 컬럼 — 남의 문서 암호문을 내 문서 행에 넣으면 소유권 검사를 통과한
 *   경로로 남의 본문이 복호화된다. 이것은 §5 Phase 7 즉시 중단 기준(다른 사용자 데이터
 *   노출)에 그대로 걸린다.
 *
 * 그래서 associated data 에 **행 식별자 + 이 컬럼 이름**을 함께 넣는다. 어느 쪽으로
 * 옮겨도 태그 검증이 실패한다.
 *
 * [wireName] 은 `테이블.컬럼` 이다. 스키마의 그 자리를 가리키는 이름이라 컬럼이 바뀌면
 * 여기도 함께 바뀌어야 한다 — 그때 기존 암호문이 열리지 않으므로, **이 문자열은 스키마
 * 변경만큼 무겁다.** 이름만 다듬는 변경을 하지 않는다.
 */
enum class EncryptedField(val wireName: String) {
    /** 업로드 원문. `documents.source_text_encrypted` (V1 baseline). */
    DOCUMENT_SOURCE_TEXT("documents.source_text_encrypted"),

    /** AI 초안. `conversions.easy_text_encrypted`. */
    CONVERSION_EASY_TEXT("conversions.easy_text_encrypted"),

    /** 마스킹 대응표. `conversions.masked_items_encrypted`. 자리표시자↔원값 표라 최고 민감도다. */
    CONVERSION_MASKED_ITEMS("conversions.masked_items_encrypted"),

    /** 담당자 검수 수정본. `conversions.edited_text_encrypted` (Alembic 0004). */
    CONVERSION_EDITED_TEXT("conversions.edited_text_encrypted"),
}

/**
 * `encryption_scheme` 컬럼에 들어가는 방식 이름.
 *
 * ## `fernet-v1` 이 아니다
 *
 * V2(`2ed897d`)는 이 컬럼을 `DEFAULT 'fernet-v1'` + `CHECK IN ('fernet-v1')` 로 만들었고,
 * 그 근거 셋(Python 이 계속 읽고 쓴다 / Phase 7 롤백 / 관찰 기간 내내 고정)은 2026-08-12
 * 결정으로 **전부 무효**가 됐다. `migration-safety-gate` I-7 검증 5 가
 * *"처음부터 새 scheme 값으로 쓰고 `fernet-v1` 을 쓰지 않는다"* 를 요구하고,
 * `V3__encryption_scheme_aead.sql` 이 CHECK 도메인을 이 값으로 옮겼다.
 *
 * 이름에 키 길이를 적는 이유: `aes-gcm` 만으로는 AES-128 과 AES-256 이 구분되지 않는다.
 * 키를 늘리는 날 옛 행을 가려낼 수 있어야 하고, 그것이 이 컬럼의 존재 이유다.
 *
 * `character varying(16)` 에 들어가야 하므로 16자를 넘기지 않는다(현재 12자).
 */
object EncryptionScheme {
    /** 지금 쓰는 유일한 방식. AES-256-GCM · 96비트 난수 nonce · 128비트 태그. */
    const val AES_256_GCM_V1: String = "aes256gcm-v1"
}

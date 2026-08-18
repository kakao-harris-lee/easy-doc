package kr.easydoc.core.user

import java.security.MessageDigest

/**
 * 비밀번호 해시(PHC 문자열)를 감싸는 타입.
 *
 * ## 왜 `String` 이 아닌가
 *
 * 해시는 평문 비밀번호가 아니지만 **오프라인 공격의 입력**이다. 유출되면 사전 공격이
 * 시작되고, Argon2 파라미터까지 문자열에 실려 있어 공격 비용 산정까지 도와준다. 그래서
 * 평문 비밀번호와 같은 등급으로 다룬다 — 로그·예외 메시지·응답 본문 어디에도 실리지
 * 않아야 한다.
 *
 * Kotlin `data class` 의 기본 `toString()` 은 모든 필드를 그대로 찍는다. 저장소 행을
 * `data class UserRow(..., passwordHash: String, ...)` 로 두고 그 객체를 로그 한 줄에
 * 남기는 것이 가장 흔한 유출 경로다(kotlin-spring-conventions §7). 타입을 갈라 두면
 * 문자열 템플릿·로거 인자에 실수로 흘러도 마스킹된 값만 나간다.
 *
 * `kr.easydoc.core.security.Secret` 과 모양이 같지만 **의도적으로 합치지 않는다.**
 * `Secret` 은 *설정에서 온 비밀값*이고 이쪽은 *저장소에서 온 사용자 자격증명*이라,
 * 한 타입으로 묶으면 "설정 비밀값을 DB에 넣는" 같은 잘못된 대입이 타입 검사를 통과한다.
 */
class PasswordHash(private val value: String) {
    init {
        // 메시지에 값을 넣지 않는다 — 이 예외는 로그로 간다.
        require(value.isNotBlank()) { "비밀번호 해시가 비어 있습니다" }
    }

    /**
     * 실제 PHC 문자열을 꺼낸다.
     *
     * **부르는 자리는 셋뿐이어야 한다** — 해시 검증, DB 저장, 재해시 판정. 그 밖의
     * 호출은 코드 리뷰에서 걸러야 한다.
     */
    fun reveal(): String = value

    /** 로그·예외·디버거 어디에 실려도 값이 나가지 않는다. 길이도 알려주지 않는다. */
    override fun toString(): String = MASK

    /** 상수 시간 비교. 해시 비교에 `==` 를 쓰면 첫 불일치에서 단락돼 타이밍 단서가 남는다. */
    override fun equals(other: Any?): Boolean {
        if (other !is PasswordHash) {
            return false
        }
        return MessageDigest.isEqual(
            value.toByteArray(Charsets.UTF_8),
            other.value.toByteArray(Charsets.UTF_8),
        )
    }

    /** 값에 의존하지 않는 해시. 해시 자료구조의 키로 쓰지 않는다는 뜻이기도 하다. */
    override fun hashCode(): Int = MASK.hashCode()

    companion object {
        /** 마스킹 문자열. `Secret.MASK` 와 같은 자리다. */
        const val MASK: String = "**********"
    }
}

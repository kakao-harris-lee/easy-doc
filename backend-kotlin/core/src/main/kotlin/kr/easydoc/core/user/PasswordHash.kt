package kr.easydoc.core.user

import java.security.MessageDigest

/** 비밀번호 해시(PHC 문자열)를 감싸는 타입. */
class PasswordHash(private val value: String) {
    init {
        // 메시지에 값을 넣지 않는다 — 이 예외는 로그로 간다.
        require(value.isNotBlank()) { "비밀번호 해시가 비어 있습니다" }
    }

    /** 실제 PHC 문자열을 꺼낸다. */
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

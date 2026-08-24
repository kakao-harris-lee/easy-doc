package kr.easydoc.core.security

/** 로그·예외 메시지에 절대 실리면 안 되는 설정값을 감싸는 타입. */
class Secret(private val value: String) {
    /**
     * 실제 값을 꺼낸다. **이 호출은 코드 리뷰에서 눈에 띄어야 한다** —
     * 부르는 자리는 암호화·서명·DB 접속처럼 값이 실제로 필요한 곳뿐이어야 한다.
     */
    fun reveal(): String = value

    /** 값이 비어 있는지. 설정 누락 판정에 쓰며 평문을 노출하지 않는다. */
    fun isBlank(): Boolean = value.isBlank()

    /** 로그·예외·디버거 어디에 실려도 평문이 나가지 않는다. 길이도 알려주지 않는다. */
    override fun toString(): String = MASK

    /** 상수 시간 비교. 비밀값 비교에 `==` 를 쓰면 첫 불일치에서 단락돼 타이밍 단서가 남는다. */
    override fun equals(other: Any?): Boolean {
        if (other !is Secret) return false
        return java.security.MessageDigest.isEqual(
            value.toByteArray(Charsets.UTF_8),
            other.value.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * 값에 의존하지 않는 해시. 값 기반 해시는 해시 충돌 탐색으로 원문 단서를 준다.
     * 비밀값을 해시 자료구조의 키로 쓰지 않는다는 뜻이기도 하다.
     */
    override fun hashCode(): Int = MASK.hashCode()

    companion object {
        /** 로그와 디버그 출력에 사용하는 고정 마스킹 문자열. */
        const val MASK: String = "**********"

        /** 설정이 비어 있음을 타입 안에서 표현하는 값. */
        val EMPTY: Secret = Secret("")
    }
}

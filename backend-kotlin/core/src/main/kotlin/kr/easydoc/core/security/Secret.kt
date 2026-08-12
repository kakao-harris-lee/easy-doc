package kr.easydoc.core.security

/**
 * 로그·예외 메시지에 절대 실리면 안 되는 설정값을 감싸는 타입.
 *
 * Python `app/config.py` 가 `pydantic.SecretStr` 로 얻던 보호를 Kotlin에서 재현한다.
 * pydantic 은 `repr`/`model_dump` 에서 자동 마스킹하는데, Kotlin 데이터 클래스의
 * 기본 `toString()` 은 정반대로 **모든 필드를 그대로 찍는다.** 설정 클래스를
 * `data class` 로 두고 `Settings(jwtSecret=eyJhbGciOi...)` 가 기동 로그 한 줄에
 * 남는 것이 가장 흔한 유출 경로다.
 *
 * 그래서 값을 String 으로 들고 다니지 않고 이 타입으로 감싼다. 꺼내려면
 * [reveal] 을 명시적으로 불러야 하므로, 문자열 템플릿·로거 인자·JSON 직렬화 어디에
 * 실수로 흘러도 마스킹된 문자열만 나간다.
 *
 * `value class` 가 아니라 일반 class 인 이유: value class 는 인라인되면서
 * `toString()` 재정의가 우회되는 경로가 있어 보호가 상황에 따라 사라진다.
 * 여기서는 성능보다 "어느 경로로도 평문이 새지 않는다"가 중요하다.
 */
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

    /**
     * 상수 시간 비교. 비밀값 비교에 `==` 를 쓰면 첫 불일치에서 단락돼 타이밍 단서가 남는다.
     */
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
        /** 마스킹 문자열. Python `SecretStr` 의 `**********` 와 같은 자리다. */
        const val MASK: String = "**********"

        /** 미설정 상태. Python 의 `SecretStr | None = None` 에 대응한다. */
        val EMPTY: Secret = Secret("")
    }
}

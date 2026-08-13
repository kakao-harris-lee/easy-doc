package probes

// 게이트 09 M-03 (codex K-2) 재현 ② — raw string 안의 `)`.
//
// `_depth_after` 가 물리 줄마다 quote 상태를 초기화하던 시절에는 아래 raw string 안의 `)` 가
// **코드로 읽혀** 호출이 조기에 닫혔고, `draft.value` 는 다른 논리 줄로 밀려 규칙이 아예
// 발화하지 못했다. 지금은 어휘 상태가 물리 줄 사이에 유지된다.
object RawStringProbe {
    fun raw문자열(draft: Any) {
        logger.info(
            """
            여러 줄 메시지다.
            여기 닫는 괄호가 있다 )
            그리고 또 하나 )
            """,
            draft.value,
        )
    }
}

package probes

// 스캐너 음성 대조용 **합성 파일**. 제품 코드가 아니다.
//
// 게이트 10 R-2(codex 단독) — Kotlin 은 블록 주석 **중첩**을 허용한다. 상태를 Boolean 으로
// 들면 첫 `*/` 에서 닫혀 **바깥 주석 본문이 코드로 새어 나온다.**
//
// c2255dc 가 닫은 것(코드가 주석으로 새는 것)과 **상보**다 — 같은 함수, 반대 방향.
object NestedCommentProbe {
    // ① 새어 나온 주석 본문의 `)` 가 인자 구간을 끊고, 앞의 안전한 접근이 방패가 된다.
    fun 중첩주석(draft: Any) {
        logger.info(
            "완료 {} {}",
            draft.stats.count, /* 설명 /* 중첩 */ 건수) 계속 */
            draft.value,
        )
    }

    // 대조군 — 중첩만 없앤 같은 코드.
    fun 중첩없음(draft: Any) {
        logger.info(
            "완료 {} {}",
            draft.stats.count, /* 설명 건수 계속 */
            draft.value,
        )
    }
}

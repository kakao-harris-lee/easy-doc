package probes

// 스캐너 음성 대조용 **합성 파일**. 제품 코드가 아니다.
//
// 게이트 10 R-1(양 레인 독립 합의) — 한 논리 줄에 로그 호출이 둘이고 **앞이 안전하면**
// 뒤가 통째로 후보에서 빠졌다. `search` 가 첫 적중 하나만 2차 판정에 넘겼기 때문이다.
//
// `_advance` KDoc 이 이름 붙인 "안전한 접근이 위험한 접근의 방패가 된다"가 호출과 호출
// **사이**에 남아 있었다. c2255dc 가 한 호출 **안**을 닫았고 여기는 호출 **개수**다.
object MultiCallProbe {
    // ① 한 줄 if-else — 두 호출이 물리 줄 하나에 있다.
    fun 한줄분기(draft: Any, ok: Boolean) {
        if (ok) logger.info("건수 {}", draft.stats.count) else logger.info("본문 {}", draft.value)
    }

    // ② `.also` 체인 — 논리 줄 결합이 두 호출을 한 줄로 만든다.
    fun 체인(draft: Any) {
        compute()
            .also { logger.info("건수 {}", draft.stats.count) }
            .also { logger.info("본문 {}", draft.value) }
    }

    // 대조군 — 순서를 뒤집으면 **수정 전에도** 잡혔다. 그것이 "첫 적중 하나만"의 증거다.
    fun 순서뒤집기(draft: Any, ok: Boolean) {
        if (ok) logger.info("본문 {}", draft.value) else logger.info("건수 {}", draft.stats.count)
    }
}

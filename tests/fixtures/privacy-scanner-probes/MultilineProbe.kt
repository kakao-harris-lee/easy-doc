package probes

// 스캐너 음성 대조용 **합성 파일**. 제품 코드가 아니다.
//
// privacy-gate 판정 §4-quater.2 가 요구한 상시 탐침이다. 판정 3 의 음성 대조가 스크래치패드
// 1회성이었던 것이 다중 줄 맹점(C-03)을 놓친 원인이라, 저장소 안 테스트로 고정한다.
//
// **SCAN_ROOTS 밖(`tests/`)에 둔다** — 전수 스캔이 자기 탐침에 걸려 CI 가 빨개지면 안 된다.
// 테스트가 REPO_ROOT·SCAN_ROOTS 를 이 디렉터리로 돌려 돌린다.
//
// 아래 세 호출은 전부 ktlint 가 강제하는 Kotlin 줄바꿈 스타일이고, 줄 단위 판정으로는
// **전 줄 무적중**이었다. 개인정보는 없다 — 식별자 이름만으로 규칙이 걸린다.
object MultilineProbe {
    fun 로그본문(draft: Any) {
        logger.info(
            "변환 완료 {}",
            draft.value,
        )
    }

    fun 마스킹전_원문전송(provider: Any, sourceText: String) {
        provider.complete(
            sourceText,
            options,
        )
    }

    // SQL 을 여러 줄로 쓰면 `INSERT` 와 컬럼 이름이 서로 다른 물리 줄에 놓인다.
    // 줄 하나씩 보면 어느 줄도 규칙을 만족하지 않는다 — 그것이 이 탐침의 요점이다.
    fun 평문저장(jdbc: Any, rawText: String) {
        jdbc.update(
            """
            INSERT INTO documents
                (id, source_text)
            VALUES (?, ?)
            """,
            rawText,
        )
    }

    fun 한줄_대조(draft: Any) {
        logger.info("변환 완료 {}", draft.value)
    }
}

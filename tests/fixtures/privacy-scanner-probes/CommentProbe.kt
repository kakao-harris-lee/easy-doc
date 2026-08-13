package probes

// 스캐너 음성 대조용 **합성 파일**. 제품 코드가 아니다.
//
// codex stop-time 게이트가 찾은 결함 — 인자 파서가 주석을 모른다.
//
// 재현 조건 셋이 **함께** 성립해야 샌다:
//   ① 주석 안에 짝 없는 `)` 가 있다  → 인자 구간이 거기서 조기에 닫힌다
//   ② 그 앞에 **안전한** 본문 접근이 있다 → refine 이 "찾았고 전부 안전" 경로로 들어간다
//   ③ 진짜 위험한 접근이 그 뒤에 있다   → 잘린 구간 밖이라 아예 검사되지 않는다
//
// ②가 없으면 refine 이 "본문 이름 없음"으로 보수적 CAUGHT 를 내므로 드러나지 않는다.
// 그래서 "주석이 있으면 샌다"가 아니라 **"안전한 접근이 방패가 된다"**가 정확한 서술이다.
object CommentProbe {
    fun 줄주석_닫는괄호(draft: Any) {
        logger.info(
            "완료 {} {}",
            draft.stats.count, // 건수) 설명
            draft.value,
        )
    }

    fun 블록주석_닫는괄호(draft: Any) {
        logger.info(
            "완료 {} {}",
            draft.stats.count, /* 건수) 설명 */
            draft.value,
        )
    }

    // 대조군 — 같은 코드에서 주석만 뺐다. 주석 유무로 검출이 갈리면 안 된다.
    fun 주석없음(draft: Any) {
        logger.info(
            "완료 {} {}",
            draft.stats.count,
            draft.value,
        )
    }
}

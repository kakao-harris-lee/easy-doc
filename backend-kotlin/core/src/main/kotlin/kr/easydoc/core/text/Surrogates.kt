package kr.easydoc.core.text

// **짝을 이루지 않은 UTF-16 서로게이트** — 무엇이 그것인가를 정하는 **한 곳**.
//
// ## 왜 판정을 여기 두는가
//
// 같은 문자에 대해 저장소가 내리는 **처분이 둘**이다.
//
// | 자리 | 처분 | 정본 |
// |---|---|---|
// | 저장되는 **본문** | **거절**(422) | 계약 `x-stored-text-domain` · [kr.easydoc.core.crypto.PlainBody] |
// | 평문 열에 남는 **제목** | **정제**(걷어내고 접수) | 계약 `x-title-policy.rule` · [kr.easydoc.core.document.resolveTitle] |
//
// 처분이 갈리는 사유는 계약 `x-title-policy.x-surrogate-note` 가 적었다 — 제목에서 잃는
// 것은 라벨 하나이고 본문에서 잃는 것은 문서다. **두 처분이 다른 것은 실수가 아니다.**
//
// 그런데 **「무엇이 짝 없는 서로게이트인가」는 하나**다. 그 판정을 두 파일에 각각 적으면
// 한쪽만 고쳐지는 날이 오고, 그날 두 처분은 서로 다른 정의역 위에서 돌게 된다. 그래서
// 판정을 이 파일 하나에 두고 [hasUnpairedSurrogate]·[stripUnpairedSurrogates] 가 **같은
// 훑기**([forEachUnpairedSurrogateIndex])를 쓴다.
//
// ## 그렇다고 **한 함수로 합치지는 않는다**
//
// 두 처분을 한 함수(예: "정제하거나 던진다")로 합치면 한쪽 축을 되돌릴 때 다른 쪽도 함께
// 깨져, 「본문 거절」과 「제목 정제」가 실제로 분리돼 있는지를 잴 수 없다. 계약 레인이
// 음성 대조 **N-34 · R-3** 을 그 축으로 설계했다 — *"제목 정제를 빼면 DC-25 만 깨지고
// DC-24 는 깨지지 않아야 한다."* 두 공개 함수가 나뉘어 있는 것이 그 대조의 전제다.
//
// ## 왜 이 값이 위험한가
//
// `String.toByteArray(UTF_8)` 는 짝 없는 서로게이트를 인코딩하지 못해 `?`(U+003F)로
// **비가역 치환**한다(JDK `CharsetEncoder` 의 REPLACE 동작). 그래서
//
// - **본문**은 AEAD 봉인 전에 그 치환이 일어나 복호화는 성공하는데 원문만 사라지고,
// - **제목**은 평문 열이라 JDBC 드라이버가 UTF-8 로 쓰는 시점에 갈린다 — 치환이면 조용한
//   손상, 오류면 원인을 알 수 없는 500 이다.
//
// 둘 다 요구 위반이다. `stripControlChars` 는 이 문자를 **지우지 않는다**(그 패턴은 C0·DEL·
// C1 만 본다) — 그래서 별도 판정이 필요하다.

/** 짝을 이루지 않은 UTF-16 서로게이트가 **하나라도** 있는가. */
fun hasUnpairedSurrogate(text: String): Boolean {
    forEachUnpairedSurrogateIndex(text) { return true }
    return false
}

/** 짝을 이루지 않은 서로게이트만 **걷어낸다**. 나머지 문자는 하나도 건드리지 않는다. */
fun stripUnpairedSurrogates(text: String): String {
    // 판정은 언제나 위 함수를 거친다 — 여기서 다시 훑기를 적으면 두 벌이 된다.
    if (!hasUnpairedSurrogate(text)) return text
    val doomed = HashSet<Int>()
    forEachUnpairedSurrogateIndex(text) { doomed.add(it) }
    return text.filterIndexed { index, _ -> index !in doomed }
}

/** 짝 없는 서로게이트가 놓인 **코드 단위 인덱스**를 앞에서부터 넘긴다. */
private inline fun forEachUnpairedSurrogateIndex(
    text: String,
    action: (Int) -> Unit,
) {
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                index += 2
            }

            character.isSurrogate() -> {
                action(index)
                index++
            }

            else -> {
                index++
            }
        }
    }
}

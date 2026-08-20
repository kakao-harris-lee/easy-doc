package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 제목 규칙과 문자 수 세기 — **Spring 도 DB 도 없이 돈다**(계획 §3.2).
 *
 * ## 이 파일의 중심 축: **제목의 바탕은 사용자가 적어 준 값 하나뿐이다**
 *
 * `documents.title` 은 평문 컬럼이고 업로드 경로에는 마스킹이 없다. 그래서 다른 곳에서
 * 제목을 만들면 그 값은 아무 방어도 받지 않고 평문으로 남는다. 닫힌 갈래가 둘이고 사유가
 * 서로 다르다 — 본문 유도(게이트 27 Critical ①)와 파일 이름(2026-08-20 재판정, 계약
 * `DocumentTextRequest.title` 과 `migration-safety-gate` I-4). 사유의 정본은
 * `TitleRules.kt` 머리말이다.
 *
 * **여기서 잴 수 있는 것과 없는 것을 갈라 둔다.** 이 파일은 순수 함수라 「호출자가 무엇을
 * 넘겼는가」를 잴 수 없다 — 잴 수 있는 것은 **받을 자리가 몇 개인가**(아래 시그니처 핀)와
 * 다듬기 규칙뿐이다. 실제로 무엇이 열에 적혔는지는 `JdbcDocumentStoreTest` 의 두 탐지기가
 * 실 DB 에서 잰다(본문 표식·파일 이름 표식이 **어느 평문 열에도** 없다).
 *
 * ## 여기서 재는 또 하나의 축 — 코드 포인트 경계
 *
 * Python `len`·슬라이스는 코드 포인트 단위라 서로게이트 쌍을 쪼갤 수 없지만 Kotlin
 * `String.length`·`take` 는 UTF-16 코드 단위라 **쪼갤 수 있다.** 쪼개진 문자열은 짝 없는
 * 서로게이트를 갖고, 그것이 UTF-8 로 인코딩될 때 `?` 로 바뀐다 — 즉 우리가 만든 손상이다
 * (게이트 25 X1 과 같은 자리). 제목은 암호화 경로를 지나지 않아 `PlainBody` 검사도 받지
 * 못하므로 여기서 막지 않으면 아무 데서도 막히지 않는다.
 */
class TitleRulesTest {
    // ============================================================ 바탕은 하나뿐이다

    /**
     * **시그니처 핀** — 제목의 바탕이 될 수 있는 자리가 정확히 하나임을 컴파일러가 진다.
     *
     * 값 단언이 아니라 타입 단언인 이유: 본문이든 파일 이름이든 되살리려면 **인자가 늘어야**
     * 하고, 그 순간 이 줄이 컴파일되지 않는다. 반사(reflection)로 인자 수를 세는 방법도
     * 있지만 `core` 는 Spring 없이 도는 모듈이라 `kotlin-reflect` 를 전제하지 않는다.
     */
    private val signaturePin: (String?) -> String = ::resolveTitle

    @Test
    @DisplayName("제목의 바탕을 받는 자리는 **하나**다 — 본문도 파일 이름도 받을 통로가 없다")
    fun `바탕을 받는 자리는 하나다`() {
        // 핀이 실제로 그 함수를 가리키는지 확인한다. 확인하지 않으면 위 선언은 컴파일만
        // 통과하고 아무것도 재지 않는 죽은 줄이 될 수 있다.
        assertThat(signaturePin(null)).isEqualTo(FALLBACK_TITLE)
        assertThat(signaturePin("복지 안내")).isEqualTo("복지 안내")
    }

    @Test
    @DisplayName("적어 준 제목이 없으면 **대체 제목**이다 — 본문에서도 파일 이름에서도 만들지 않는다")
    fun `근거가 없으면 대체 제목이다`() {
        assertThat(resolveTitle(null)).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle("")).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle("   ")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("제어문자만 적어 준 제목도 대체 제목이다 — 다른 값으로 덮지 않는다")
    fun `제어문자뿐인 제목은 대체 제목이다`() {
        // 제어문자는 **이스케이프로만** 적는다. 원시 바이트를 소스에 넣으면
        // `tests/test_raw_control_chars.py` 가 잡는다 — 이 저장소에서 반복해 재발한 자리다.
        assertThat(resolveTitle("\u0001\u0002\u0003")).isEqualTo(FALLBACK_TITLE)
    }

    // ============================================================ 사용자가 준 제목

    @Test
    @DisplayName("사용자가 준 제목은 앞뒤 공백만 털고 그대로 쓴다 — 짧게 줄이지 않는다")
    fun `사용자 제목은 그대로 쓴다`() {
        val given = "  2026년 상반기 복지 안내문 발송 계획 알림 문서입니다  "

        assertThat(resolveTitle(given)).isEqualTo(given.trim())
    }

    @Test
    @DisplayName("제어문자를 자르기 **전에** 걷어낸다 — 잘린 길이가 보이는 글자 수와 맞는다")
    fun `제어문자를 먼저 걷어낸다`() {
        val control = "\u0000".repeat(10)
        val name = "가".repeat(MAX_TITLE_LENGTH)

        val resolved = resolveTitle(control + name)

        assertThat(resolved).isEqualTo(name)
        assertThat(resolved).hasSize(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("제목이 상한을 넘으면 **거절하지 않고 자른다** (계약 x-input-limits.max_title_length)")
    fun `제목은 상한에서 잘린다`() {
        val resolved = resolveTitle("나".repeat(MAX_TITLE_LENGTH + 50))

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
    }

    // ============================================================ 짝 없는 서로게이트 — **걷어내고 접수한다**

    /**
     * **K-14 — 계약 `x-title-policy.rule` 이 요구하는 정제.**
     *
     * 이 절이 생긴 사유: 계약 v1.3.0(`dc9ef8e`)이 제목에서 짝 없는 서로게이트를 걷어내라고
     * 요구했는데 구현에 그 처리가 한 줄도 없었다. `documents.title` 은 **평문 열**이라
     * 짝 없는 서로게이트가 남으면 드라이버가 UTF-8 로 쓰는 시점에 갈린다 — 치환이면
     * **조용한 손상**, 오류면 **원인을 알 수 없는 500** 이다(`x-title-policy.x-surrogate-note`).
     *
     * **이 케이스들이 없으면 K-14 는 검증되지 않는다.** 실측(2026-08-20): `sanitizeName` 에서
     * `stripUnpairedSurrogates` 호출만 걷어내고 `:core:test` 를 돌렸더니 **exit 0** 이었다 —
     * `SurrogatesTest` 는 판정 함수를 **직접** 재므로 `TitleRules` 가 그것을 *쓰는지* 는
     * 아무도 재지 않았다. 아래 다섯이 그 자리를 닫는다.
     */
    @Test
    @DisplayName("K-14 제목의 짝 없는 서로게이트를 **걷어낸다** — 나머지 글자는 그대로 남는다")
    fun `제목의 짝 없는 서로게이트를 걷어낸다`() {
        assertThat(resolveTitle("복지\uD800안내")).isEqualTo("복지안내")
        assertThat(resolveTitle("안내문\uD83D")).isEqualTo("안내문")
        assertThat(resolveTitle("x\uDC00y")).isEqualTo("xy")
    }

    @Test
    @DisplayName("K-14 정제 후 남는 것이 없으면 **대체 제목**이다 — 거절이 아니다")
    fun `서로게이트만 있는 제목은 대체 제목이다`() {
        assertThat(resolveTitle("\uD800\uD800")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("K-14 정제된 제목은 UTF-8 로 왕복한다 — 평문 열에 쓰는 시점에 갈리지 않는다")
    fun `정제된 제목이 UTF-8 로 왕복한다`() {
        val resolved = resolveTitle("복지\uD800안내\uD83D")

        assertThat(String(resolved.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("K-14 **짝을 이룬 쌍은 그대로 남는다** — 정제가 BMP 밖 문자 전체로 번지지 않았다")
    fun `짝 맞는 서로게이트는 제목에 남는다`() {
        // 여기가 빨개지면 정제가 아니라 검열이다 — 사용자가 적은 제목이 이유 없이 훼손된다.
        assertThat(resolveTitle("민원 안내 🙂")).isEqualTo("민원 안내 🙂")
        assertThat(resolveTitle("𝓐 서식")).isEqualTo("𝓐 서식")
    }

    /**
     * **본문과 갈리는 축** — 같은 문자에 제목은 정제, 본문은 거절이다.
     *
     * 계약이 일부러 가른 두 처분이다(`x-title-policy.x-surrogate-note` ·
     * `x-stored-text-domain`). 두 처분을 한 함수로 합치면 이 두 단언 중 하나가 반드시
     * 깨진다 — 계약 레인이 음성 대조 **N-34 · R-3** 를 이 축으로 설계했다.
     */
    @Test
    @DisplayName("K-14 제목은 **던지지 않는다** — 같은 값이 저장 본문에서는 거절된다")
    fun `제목은 던지지 않고 본문은 거절된다`() {
        val broken = "복지\uD800안내"

        assertThatCode { resolveTitle(broken) }
            .describedAs("제목에서 잃는 것은 라벨 하나다 — 라벨 때문에 문서 접수를 거절하지 않는다")
            .doesNotThrowAnyException()
        assertThatThrownBy { PlainBody(broken) }
            .describedAs("본문에서 잃는 것은 문서다 — 여기는 정제가 아니라 거절이다")
            .isInstanceOf(InvalidInputException::class.java)
    }

    // ============================================================ 코드 포인트 경계

    @Test
    @DisplayName("상한에서 자를 때 서로게이트 쌍을 쪼개지 않는다 — 쪼개면 우리가 만든 손상이다")
    fun `자르기가 서로게이트 쌍을 쪼개지 않는다`() {
        // BMP 밖 문자(U+1D4D0). UTF-16 으로 두 코드 단위다.
        val astral = "𝓐"
        val given = astral.repeat(MAX_TITLE_LENGTH + 10)

        val resolved = resolveTitle(given)

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
        assertThat(resolved.any { it.isSurrogate() })
            .describedAs("서로게이트가 있어야 이 케이스가 무언가를 재고 있다")
            .isTrue()
        assertThat(resolved.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("문자 수는 **코드 포인트**로 센다 — UTF-16 코드 단위로 세면 이모지 문서가 두 배로 계산된다")
    fun `문자 수는 코드 포인트다`() {
        val astral = "𝓐"

        assertThat(charCountOf(astral)).isEqualTo(1)
        assertThat(astral.length).describedAs("전제: 이 문자는 코드 단위 둘이다").isEqualTo(2)
        assertThat(charCountOf("가나다 라마")).isEqualTo(6)
    }

    @Test
    @DisplayName("takeCodePoints 는 짧은 입력을 그대로 돌려준다")
    fun `짧은 입력은 그대로다`() {
        assertThat(takeCodePoints("가나다", 10)).isEqualTo("가나다")
        assertThat(takeCodePoints("", 10)).isEmpty()
    }
}

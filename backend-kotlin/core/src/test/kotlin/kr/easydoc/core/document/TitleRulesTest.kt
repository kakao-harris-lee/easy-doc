package kr.easydoc.core.document

import org.assertj.core.api.Assertions.assertThat
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

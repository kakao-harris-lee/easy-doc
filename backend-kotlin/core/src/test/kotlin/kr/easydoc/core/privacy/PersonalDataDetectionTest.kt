package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 개인정보 경고용 검출 — 순수 함수, Spring 도 DB 도 없이 돈다.
 *
 * 계획 `docs/plans/2026-09-10-personal-data-warning.md` §4 수용 기준 5·6(오탐 억제)이
 * 이 파일에서 재진다.
 */
class PersonalDataDetectionTest {
    @Test
    @DisplayName("검증식을 통과하는 주민등록번호는 rrn 으로 검출된다 — 하이픈 유무 둘 다")
    fun `검증식을 통과하는 주민등록번호가 검출된다`() {
        assertThat(detectPersonalData("주민등록번호는 $VALID_RRN_NO_HYPHEN 입니다"))
            .containsExactly(PersonalDataKind.RRN)
        assertThat(detectPersonalData("주민등록번호는 $VALID_RRN_HYPHEN 입니다"))
            .containsExactly(PersonalDataKind.RRN)
    }

    @Test
    @DisplayName("Luhn 을 통과하는 카드번호는 card 로 검출된다 — 하이픈·공백·구분자 없음 모두")
    fun `Luhn 을 통과하는 카드번호가 검출된다`() {
        assertThat(detectPersonalData("카드번호 $VALID_CARD_NO_SEPARATOR")).containsExactly(PersonalDataKind.CARD)
        assertThat(detectPersonalData("카드번호 $VALID_CARD_HYPHEN")).containsExactly(PersonalDataKind.CARD)
        assertThat(detectPersonalData("카드번호 $VALID_CARD_SPACE")).containsExactly(PersonalDataKind.CARD)
    }

    @Test
    @DisplayName("주민등록번호와 카드번호가 함께 있으면 둘 다 검출된다")
    fun `둘 다 있으면 둘 다 검출된다`() {
        val text = "주민등록번호 $VALID_RRN_HYPHEN 이고 카드번호 $VALID_CARD_HYPHEN 입니다"

        assertThat(detectPersonalData(text)).containsExactlyInAnyOrder(PersonalDataKind.RRN, PersonalDataKind.CARD)
    }

    @Test
    @DisplayName("A5 — 전화번호·이메일만 있는 본문은 검출되지 않는다(오탐 억제)")
    fun `전화번호와 이메일만 있으면 검출되지 않는다`() {
        val text = "문의: 02-1234-5678, 담당자 010-9876-5432, 이메일 help@example.com"

        assertThat(detectPersonalData(text)).isEmpty()
    }

    @Test
    @DisplayName("A5 — 인접한 두 전화번호를 공백으로 이어 붙여도 카드번호 모양으로 오검출되지 않는다")
    fun `인접한 전화번호 두 개는 카드번호로 오검출되지 않는다`() {
        val text = "전화 010-1234-5678 팩스 02-1234-5678"

        assertThat(detectPersonalData(text)).isEmpty()
    }

    @Test
    @DisplayName("A6 — 검증식을 통과하지 못하는 13자리 숫자(사업자등록번호 이어붙임 등)는 검출되지 않는다")
    fun `검증식을 통과하지 못하는 13자리 숫자는 검출되지 않는다`() {
        assertThat(detectPersonalData("사업자 정보 $INVALID_13_DIGITS 입니다")).isEmpty()
    }

    // ------------------------------------------------------------------
    // 리뷰 2026-09-10 — 법인등록번호가 모양이 완전히 같아 모듈러스-11 검증식을 10.2%
    // 확률로 우연히 통과했다. 날짜·세기 코드 조건을 검증식 앞에 더해 고쳤다.

    @Test
    @DisplayName("리뷰가 찾은 실제 충돌 사례(법인등록번호) — 이제 검출되지 않는다")
    fun `법인등록번호 충돌 사례는 더 이상 검출되지 않는다`() {
        assertThat(detectPersonalData("발행기관 사업자등록 $CORPORATE_REGISTRATION_COLLISION 안내"))
            .isEmpty()
    }

    @Test
    @DisplayName("월이 13인 13자리는 검출되지 않는다 — YYMMDD 의 MM 이 01~12 를 벗어난다")
    fun `월이 13인 13자리는 검출되지 않는다`() {
        assertThat(detectPersonalData("번호 $MONTH_OUT_OF_RANGE 안내")).isEmpty()
    }

    @Test
    @DisplayName("일이 32인 13자리는 검출되지 않는다 — 그 달에 없는 날짜다")
    fun `일이 32인 13자리는 검출되지 않는다`() {
        assertThat(detectPersonalData("번호 $DAY_OUT_OF_RANGE 안내")).isEmpty()
    }

    @Test
    @DisplayName("7번째 자리(성별·세기 코드)가 9인 13자리는 검출되지 않는다 — 1~8 만 유효하다")
    fun `세기 코드가 9인 13자리는 검출되지 않는다`() {
        assertThat(detectPersonalData("번호 $CENTURY_CODE_OUT_OF_RANGE 안내")).isEmpty()
    }

    @Test
    @DisplayName("기존의 유효한 주민등록번호는 새 조건을 더해도 그대로 검출된다(미탐 방지)")
    fun `기존 유효 주민등록번호는 여전히 검출된다`() {
        assertThat(detectPersonalData("주민등록번호는 $VALID_RRN_NO_HYPHEN 입니다"))
            .containsExactly(PersonalDataKind.RRN)
    }

    /**
     * 미탐 쪽 관문 — 세기 코드 1~8 **전부**를 돈다. `CENTURY_BASE_BY_CODE` 표가 코드 하나만
     * 틀려도 그 코드를 쓰는 실제 주민등록번호를 통째로 놓치는데, 미탐이 오탐보다 나쁘다
     * (계획 §2.5). 여덟 값 모두 검증식을 통과하도록 미리 계산했다(리뷰 2026-09-10).
     */
    @ParameterizedTest(name = "세기 코드 {0} — {1} 은 검출된다")
    @CsvSource(
        "1, 9001011234568",
        "2, 9001012234561",
        "3, 9001013234563",
        "4, 9001014234566",
        "5, 9001015234569",
        "6, 9001016234561",
        "7, 9001017234564",
        "8, 9001018234567",
    )
    fun `세기 코드 1~8 은 모두 검출된다`(
        centuryCode: Int,
        rrn: String,
    ) {
        assertThat(rrn[CENTURY_DIGIT_INDEX_FOR_TEST].digitToInt())
            .withFailMessage("테스트 값 %s 의 7번째 자리가 기대한 세기 코드 %d 와 다르다", rrn, centuryCode)
            .isEqualTo(centuryCode)
        assertThat(detectPersonalData("주민등록번호는 $rrn 입니다"))
            .withFailMessage("세기 코드 %d(%s) 가 검출되지 않았다 — CENTURY_BASE_BY_CODE 표를 확인하라", centuryCode, rrn)
            .containsExactly(PersonalDataKind.RRN)
    }

    // ------------------------------------------------------------------
    // 리뷰 2026-09-10 — 무작위 13~19자리 숫자의 Luhn 통과율이 10.0%였다. 길이를
    // 15~16자리로, 첫 자리를 3·4·5·6으로 좁혀 고쳤다(주요 카드 브랜드 대역).

    @Test
    @DisplayName("Luhn 을 통과해도 17자리면 검출되지 않는다 — 카드 길이가 아니다")
    fun `17자리 Luhn 유효 숫자는 검출되지 않는다`() {
        assertThat(detectPersonalData("접수번호 $LUHN_VALID_17_DIGITS 안내")).isEmpty()
    }

    @Test
    @DisplayName("Luhn 을 통과해도 첫 자리가 9면 검출되지 않는다 — 카드 브랜드 대역이 아니다")
    fun `첫 자리가 9인 Luhn 유효 카드는 검출되지 않는다`() {
        assertThat(detectPersonalData("관리번호 $LUHN_VALID_BAD_PREFIX 안내")).isEmpty()
    }

    @Test
    @DisplayName("기존의 유효한 카드번호는 새 조건을 더해도 그대로 검출된다(미탐 방지)")
    fun `기존 유효 카드번호는 여전히 검출된다`() {
        assertThat(detectPersonalData("카드번호 $VALID_CARD_NO_SEPARATOR")).containsExactly(PersonalDataKind.CARD)
    }

    @Test
    @DisplayName("아무것도 없으면 빈 집합이다")
    fun `평범한 안내문은 빈 집합이다`() {
        assertThat(detectPersonalData("이 안내문은 청년 월세 지원 신청 방법을 안내합니다.")).isEmpty()
    }

    private companion object {
        /** 검증식(모듈러스 11)을 통과하는 합성 주민등록번호 — 실존 인물과 무관하다. */
        const val VALID_RRN_NO_HYPHEN = "9001011234568"
        const val VALID_RRN_HYPHEN = "900101-1234568"

        /** Luhn 을 통과하는 표준 테스트 카드번호(Visa 공개 테스트 번호). */
        const val VALID_CARD_NO_SEPARATOR = "4111111111111111"
        const val VALID_CARD_HYPHEN = "4111-1111-1111-1111"
        const val VALID_CARD_SPACE = "4111 1111 1111 1111"

        /** 두 검증식 모두 실패하는 13자리 숫자. */
        const val INVALID_13_DIGITS = "1234567890000"

        /**
         * 리뷰(2026-09-10)가 실측으로 찾은 실제 충돌 사례 — 법인등록번호 모양이 주민등록번호
         * 모듈러스-11 검증식을 우연히 통과했다. 날짜·세기 코드 조건이 이제 이 값을 앞에서 거른다.
         */
        const val CORPORATE_REGISTRATION_COLLISION = "014193-1417057"

        /**
         * YY=90·MM=13(무효)·DD=01·세기 코드=1·나머지 6자리 — **검증식은 통과한다.**
         * 앞 12자리는 [MONTH_OUT_OF_RANGE] 그대로 두고 검증 숫자(마지막 자리)만 바꿨다
         * (…567 → …565) — 날짜(세기 코드) 조건에서만 떨어져야 이 테스트가 관문을 지킨다.
         * `hasPlausibleBirthDate` 를 지우면 이 값은 검출돼 테스트가 실패한다(리뷰 2026-09-10:
         * 옛 값은 검증식에서 이미 떨어져 날짜 조건을 하나도 재지 못했다).
         */
        const val MONTH_OUT_OF_RANGE = "9013011234565"

        /**
         * YY=90·MM=01·DD=32(1월에 없는 날)·세기 코드=1·나머지 6자리 — **검증식은 통과한다.**
         * 검증 숫자만 바꿨다(…567 → …565) — 날짜(세기 코드) 조건에서만 떨어져야 이
         * 테스트가 관문을 지킨다(같은 근거는 [MONTH_OUT_OF_RANGE] 참고).
         */
        const val DAY_OUT_OF_RANGE = "9001321234565"

        /**
         * YY=90·MM=01·DD=01·세기 코드=9(무효, 1~8 만 유효)·나머지 6자리 — **검증식은
         * 통과한다.** 검증 숫자만 바꿨다(…567 → …560) — 날짜(세기 코드) 조건에서만
         * 떨어져야 이 테스트가 관문을 지킨다(같은 근거는 [MONTH_OUT_OF_RANGE] 참고).
         */
        const val CENTURY_CODE_OUT_OF_RANGE = "9001019234560"

        /** `PersonalDataKind.kt` 의 `CENTURY_DIGIT_INDEX` 와 같은 값 — 그 상수는 `private` 이라 여기서 다시 적는다. */
        const val CENTURY_DIGIT_INDEX_FOR_TEST = 6

        /** Luhn 을 통과하는 17자리 숫자 — 카드 길이(15~16)를 벗어난다. */
        const val LUHN_VALID_17_DIGITS = "41111111111111113"

        /** Luhn 을 통과하는 16자리 숫자인데 첫 자리가 9 — 카드 브랜드 대역(3·4·5·6)을 벗어난다. */
        const val LUHN_VALID_BAD_PREFIX = "9111111111111110"
    }
}

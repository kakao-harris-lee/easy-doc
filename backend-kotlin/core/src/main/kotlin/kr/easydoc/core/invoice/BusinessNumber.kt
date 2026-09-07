package kr.easydoc.core.invoice

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.UserContent

/**
 * 사업자등록번호 — 세금계산서 요청(계획 `docs/plans/2026-09-07-invoice-requests.md` §2 결정 2).
 *
 * 하이픈·공백을 포함한 입력을 숫자만 남겨 정규화하고, 국세청 체크섬(가중치
 * `1,3,7,1,3,7,1,3,5` + 9번째 자리 × 5 의 십의 자리 규칙)을 통과해야 한다 — 실패는
 * 422 「사업자등록번호가 올바르지 않습니다」다.
 *
 * **`@JvmInline value class` 가 아니라 일반 class + private 생성자다**(`EmailAddress`와
 * 같은 형태) — `SensitiveToStringReachTest` 의 자동 표본 생성기(`GeneratedToStringProbes`)
 * 가 값을 감싸는 타입(value class)마다 임의 문자열로 **주 생성자를 직접** 호출해 보는데,
 * value class 였다면 검증이 주 생성자의 `init` 블록에 있어야 하고 그 임의 문자열은
 * 10자리 숫자·체크섬 형식을 만족하지 못해 그 검증이 곧바로 예외를 던져 게이트 자체가
 * 죽는다(구현 중 실측 — 최초 시도는 value class였다). 검증을 `of()` 팩터리로 옮기고
 * 원시 생성자를 private 으로 감추면 그 값-클래스 표본화 대상(`wrapperProbes`)에서는
 * 빠지되, 원시 생성자 자체는 형식을 가리지 않으므로 표본화가 실패하지 않는다.
 *
 * **`@UserContent` 로 R-10(일반 class) 축의 자동 판정 대상에 스스로 들어간다** — 필드
 * 이름(`digits`)이 민감 판정 토큰(email·name·address 등)에 걸리지 않아 애너테이션 없이는
 * `SensitiveToStringReachTest` 가 이 타입에 닿지 않는다. 위 사유로 원시 생성자가 형식을
 * 가리지 않으므로 R-10 표본화(주 생성자를 직접 호출)도 예외 없이 인스턴스를 만들고,
 * 손으로 쓴 [toString] 이 그 값을 가리는지를 그 게이트가 실제로 잰다.
 */
@UserContent
class BusinessNumber private constructor(val digits: String) {
    /** 값을 찍지 않는다 — 사업자 정보(인구조사 규약). */
    override fun toString(): String = "BusinessNumber($CONTENT_MASK)"

    override fun equals(other: Any?): Boolean = other is BusinessNumber && other.digits == digits

    override fun hashCode(): Int = digits.hashCode()

    companion object {
        private const val DIGIT_LENGTH = 10

        /** d1..d9 에 적용하는 가중치. */
        private val CHECKSUM_WEIGHTS = intArrayOf(1, 3, 7, 1, 3, 7, 1, 3, 5)

        /** 9번째 자리(0-based 인덱스 8)만 5를 곱해 십의 자리를 더하는 국세청 규칙 자리. */
        private const val NINTH_DIGIT_INDEX = 8

        /** 마지막(10번째, 0-based 인덱스 9) 자리가 체크 자릿수다. */
        private const val CHECK_DIGIT_INDEX = 9

        private const val NINTH_DIGIT_MULTIPLIER = 5
        private const val TEN = 10

        /** 계약 `x-request-field-constraints`(신설) — 형식·체크섬 위반 문구. */
        const val INVALID_MESSAGE: String = "사업자등록번호가 올바르지 않습니다"

        /**
         * 하이픈·공백을 걷어내고 정확히 10자리 숫자이며 체크섬을 통과해야 만들어진다.
         *
         * **ASCII 숫자(`'0'..'9'`)만 받는다** — `Char.isDigit()` 은 유니코드 십진 숫자
         * 카테고리(Nd) 전체를 참으로 본다(전각 숫자 `０-９` 포함). 그런 문자가 섞여
         * 들어오면 이 자리에서 걸러지지 않고 남아 아래 `it - '0'` 산술이 엉뚱한 값을
         * 내고(전각 `０`는 코드포인트가 ASCII `0`과 다르다), `invoice_requests
         * .business_number` 의 CHECK 제약(`^[0-9]{10}$`, ASCII만 허용)에 걸려서야
         * 500으로 죽는다 — 여기서 ASCII로 좁혀 422로 먼저 막는다.
         */
        fun of(raw: String): BusinessNumber {
            val digits = raw.filter { it in '0'..'9' }
            if (digits.length != DIGIT_LENGTH || !checksumValid(digits)) {
                throw InvalidInputException(INVALID_MESSAGE)
            }
            return BusinessNumber(digits)
        }

        /**
         * 국세청 체크섬 — d1..d10 에 대해 `Σ(di*wi, i=1..9) + floor(d9*5/10)` 을 구하고
         * `(10 - sum%10) % 10 == d10` 이면 유효하다.
         */
        private fun checksumValid(digits: String): Boolean {
            val d = digits.map { it - '0' }
            var sum = 0
            for (index in CHECKSUM_WEIGHTS.indices) {
                sum += d[index] * CHECKSUM_WEIGHTS[index]
            }
            sum += (d[NINTH_DIGIT_INDEX] * NINTH_DIGIT_MULTIPLIER) / TEN
            val check = (TEN - sum % TEN) % TEN
            return check == d[CHECK_DIGIT_INDEX]
        }
    }
}

package kr.easydoc.core.credit

import kr.easydoc.core.exceptions.InvalidInputException

/**
 * 크레딧 값 객체 — **항상 0 이상**(계획 `docs/plans/2026-09-07-credit-accounts.md` §2 결정 3).
 *
 * 마이너스 크레딧은 값이 아니라 상태다 — 워크스페이스 잔액(`workspace_credit_accounts.balance`)
 * 은 집행이 꺼진 상태에서 음수로 떨어질 수 있지만, 그것은 "이 값 객체가 감싸는 크레딧 수량"이
 * 아니라 계정 원장의 계산 결과다. 이 타입은 **요청 하나가 필요로 하는/거래 한 건이 옮기는
 * 크레딧 수량**만 표현하고, 그 수량은 항상 음수가 아니다 — 부호는 거래 종류
 * ([CreditTransactionKind])가 정한다.
 */
@JvmInline
value class Credits(val amount: Int) {
    init {
        if (amount < 0) throw InvalidInputException("크레딧은 음수일 수 없습니다: $amount")
    }

    companion object {
        /** master-plan §3.3 — 공백 포함 1,000자 = 1크레딧. */
        private const val CHARS_PER_CREDIT = 1000

        /**
         * `ceil(charCount / 1000)` 크레딧 — 문서 등록 1회의 필요 크레딧(계획 §2 결정 3).
         * 정수 나눗셈으로 올림한다(`(charCount + 999) / 1000`) — 부동소수 오차가 없다.
         */
        fun requiredFor(charCount: Int): Credits {
            require(charCount >= 0) { "문자 수는 음수일 수 없습니다: $charCount" }
            return Credits((charCount + CHARS_PER_CREDIT - 1) / CHARS_PER_CREDIT)
        }
    }
}

/**
 * 크레딧 거래 종류 — `credit_transactions.kind` CHECK 제약과 같은 값 집합(V15,
 * [CYCLE_SET]·[CYCLE_RESET]는 V21).
 *
 * 부호 규약(계획 §2 결정 2): [GRANT]·[RELEASE]는 거래 금액이 항상 양수, [RESERVE]·[CONSUME]·
 * [ADJUST]는 그 작업이 계정 잔액·예약에 미치는 방향대로 부호가 갈린다 — [RESERVE]는 음수,
 * [CONSUME]은 0(예약 단계에서 이미 음수로 반영됐으므로 완료가 추가로 잔액을 또 깎지
 * 않는다 — `credit_transactions` 합계 = `balance − reserved` 불변식이 그것을 강제한다),
 * [ADJUST]는 운영자가 준 부호 그대로. [CYCLE_SET]·[CYCLE_RESET]도 [balanceDelta] 방향
 * 그대로다(설정/초기화 전후 잔액 차, 음수일 수 있다) — 크레딧을 「구독 주기에 포함된
 * 이용량」으로 바꾼 사용자 결정(2026-09-10)의 구현.
 */
enum class CreditTransactionKind(val wireName: String) {
    /** 운영자 수동 부여 — 가입 보너스·월 구독 지급. 항상 양수. */
    GRANT("grant"),

    /** 문서 등록이 예약한 몫 — `reserved` 증가, 거래 금액은 음수. */
    RESERVE("reserve"),

    /** 변환 완료로 예약이 실제 소비로 확정됐다 — `balance`·`reserved` 가 함께 줄고 거래 금액은 0. */
    CONSUME("consume"),

    /** 변환이 끝내 실패해 예약을 되돌렸다 — `reserved` 만 줄고 거래 금액은 양수. */
    RELEASE("release"),

    /** 운영자 수동 조정 — 음수도 허용한다(환급 취소 등). */
    ADJUST("adjust"),

    /**
     * 운영자가 새 주기(이용량·종료일)를 연다 — `balance`를 `allowance`로 **설정**(더하지
     * 않는다). 거래 금액은 설정 전후 잔액 차(음수일 수 있다). `CreditAccountService.setAllowance`.
     */
    CYCLE_SET("cycle_set"),

    /**
     * 주기 종료 배치가 남은 잔액을 버리고 그 주기의 이용량으로 다시 채운다 — `balance =
     * allowance`. 거래 금액은 초기화 전후 잔액 차(음수일 수 있다). `reserved`는 건드리지
     * 않는다. 매일 도는 배치(`ResetCreditCycles`)만 남긴다.
     */
    CYCLE_RESET("cycle_reset"),
    ;

    companion object {
        fun ofWireName(value: String): CreditTransactionKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 크레딧 거래 종류입니다: $value")
    }
}

/** 크레딧 거래 사유 — `credit_transactions.reason` CHECK 제약과 같은 값 집합(V15). */
enum class CreditReason(val wireName: String) {
    /** 가입 시 자동 부여(`easydoc.credits.signup-grant`). */
    SIGNUP("signup"),

    /** 월 구독 갱신 부여 — `credit-grant` 운영 프로필. */
    PLAN_MONTHLY("plan_monthly"),

    /** 운영자 수동 부여·조정. */
    MANUAL("manual"),

    /** 환급. */
    REFUND("refund"),

    /** 문서 변환 1건의 예약·소비·해제. */
    CONVERSION("conversion"),

    /**
     * 갱신 없이 주기가 닫혔다(`cycle_renews = false`) — `kind`는 갱신과 똑같이
     * `cycle_reset`이지만, **왜** 닫혔는지는 이 사유가 말한다(무엇을 했는지는 `kind`,
     * 왜 그랬는지는 `reason`). 무료 체험 전용이 아니다 — 운영자가 `credit-grant` CLI의
     * `--cycle-ends-at`만 주고 `--cycle-renews` 없이 연 유상 주기도 같은 사유로 닫힌다.
     * 갱신되는 주기가 끝나 다시 채워질 때는 [PLAN_MONTHLY]를 그대로 쓴다 — 이 사유와
     * 헷갈리면 안 된다: 「이용량은 계정당 하나」 결정(2026-09-10)이 잔액을 출처별로
     * 쪼개지 않는 대신 사유의 정확성에 기댄다.
     */
    CYCLE_END("cycle_end"),
    ;

    companion object {
        fun ofWireName(value: String): CreditReason =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 크레딧 거래 사유입니다: $value")
    }
}

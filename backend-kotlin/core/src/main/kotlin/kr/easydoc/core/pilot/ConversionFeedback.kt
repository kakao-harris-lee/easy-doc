package kr.easydoc.core.pilot

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.StorageException

// 파일럿 게이트 ①(master-plan §9, 절차는 `docs/pilot-runbook.md` 「게이트 ① 판정」)의
// 검수 피드백 도메인 타입.
//
// 이 파일이 **수치 범위의 정본**이다. 계약(`contracts/easy-doc-v1.yaml`)·스키마
// (`V2__conversion_feedback.sql` 의 CHECK)·프런트 폼이 같은 숫자를 각자 적지만, 셋 중
// 하나가 갈렸을 때 어느 쪽이 맞는지를 이 파일이 정한다. 스키마 CHECK 는 그 값이 DB 까지
// 갔을 때의 마지막 방어선이고, 여기는 **가기 전에** 끊는 자리다(`EncryptedContent` 의
// `key_version` 이 같은 두 겹을 이미 쓰고 있다).
//
// ## 왜 `Int` 로 흘리지 않는가
//
// `quality_score` 와 `minutes_spent` 는 둘 다 `Int` 라 자리를 맞바꿔도 컴파일된다 —
// 「만족도 30분, 소요 시간 4점」이 타입 검사를 통과한다. 도메인 의미가 있는 숫자를 공개
// 경계에 `Int` 로 두지 않는다는 프로젝트 `CLAUDE.md` 설계 규칙이 이 자리를 겨냥한다.
//
// ## `toString()` 을 재정의하지 않는 이유
//
// `api` 의 `SensitiveToStringReachTest` 가 제품의 모든 value/data class 를 훑고, 값을 감싸는
// 타입이 감싼 값을 찍으면 실패시킨다. 그 판정은 **감싼 것이 텍스트인지**로 갈린다
// (`GeneratedToStringProbes.INERT_VALUES` 에 `Int` 가 있어 `carriesText = false` 다) —
// 아래 둘은 사용자가 고른 척도값이지 사용자 콘텐츠가 아니므로 가릴 것이 없고, 값이 보이는
// 편이 로그에서 쓸모가 있다. 문자열을 감싸는 래퍼를 이 파일에 더하게 되면 그때는
// `MaskedText`·`ModelDraft` 처럼 길이만 남기는 재정의가 필요하다.

/** 검수자가 이 변환 결과를 실제로 쓸 것인가 — 게이트 ① 통과 기준 ①의 판정 대상이다. */
enum class PublishIntent(val wireName: String) {
    /** 그대로 쓸 수 있다. */
    AS_IS("as_is"),

    /** 조금 고쳐서 쓰겠다. 기준 ①은 이것까지 「쓸 수 있다」로 센다. */
    WITH_EDITS("with_edits"),

    /** 쓸 수 없다. */
    NOT_USABLE("not_usable"),

    ;

    companion object {
        /**
         * 저장된 컬럼 값(`conversion_feedback.publish_intent`)을 항목으로 되읽는다.
         *
         * 모르는 값은 **사용자 입력 문제가 아니라 코드·데이터 버그**다 — 컬럼에 CHECK 가
         * 걸려 있어 이 목록 밖의 값이 들어갈 수 없기 때문이다. 그래서 [ofRequestValue] 와
         * 예외 갈래가 다르다(`ConversionStatus.ofWireName` 과 같은 규칙).
         */
        fun ofWireName(value: String): PublishIntent =
            entries.firstOrNull { it.wireName == value }
                ?: throw StorageException(UNKNOWN_STORED_INTENT_MESSAGE)

        /**
         * 요청 본문의 `publish_intent` 를 항목으로 읽는다. 필수 항목이라 `null` 도 같은
         * 갈래로 거절한다.
         */
        fun ofRequestValue(value: String?): PublishIntent =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException(UNKNOWN_INTENT_MESSAGE)

        /** 저장된 값을 읽지 못했을 때의 문구. 계약 `InternalError` 의 `storage` 갈래와 같다. */
        const val UNKNOWN_STORED_INTENT_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"

        /**
         * 거부 문구. **입력값을 넣지 않는다** — 예외 메시지에 입력을 넣지 않는다는
         * `DomainExceptions.kt` 의 규약이고, 그 규약이 이 문구를 응답 `detail` 에 그대로
         * 실어도 되는 근거다.
         *
         * **계약에 대응 예시가 없다.** 같은 422 의 형제 갈래들은
         * `paths./conversions/{conversion_id}/feedback.put.responses.422.content.application/json.examples`
         * 아래에 `score_out_of_range`·`minutes_out_of_range`·`comment_too_long` 으로 적혀 있고
         * `api` 의 `ConversionFeedbackReachTest` 가 그것을 읽어 응답과 대조하지만, 목록 밖
         * `publish_intent` 갈래는 대조할 예시가 없어 `detail` 이 문자열인지까지만 잰다.
         * 계약을 손볼 때(`x-change-policy` 절차) 그 examples 맵에 예시 하나를 더하면 이 문구도
         * 형제들과 같은 방식으로 고정된다.
         */
        const val UNKNOWN_INTENT_MESSAGE: String = "배포 의향은 as_is, with_edits, not_usable 중 하나여야 합니다"
    }
}

/** 검수자가 매긴 품질 만족도. 게이트 ① 통과 기준 ②(평균)의 표본 하나다. */
@JvmInline
value class QualityScore(val value: Int) {
    init {
        if (value !in RANGE) throw InvalidInputException(OUT_OF_RANGE_MESSAGE)
    }

    companion object {
        /** 만족도가 들어갈 수 있는 범위 — **정본은 여기 하나다.** */
        val RANGE: IntRange = 1..5

        /**
         * 거부 문구. 경계값은 [RANGE] 에서 끌어오고 **입력값은 넣지 않는다.**
         *
         * 문구의 정본은 계약의 422 예시
         * `paths./conversions/{conversion_id}/feedback.put.responses.422
         * .content.application/json.examples.score_out_of_range.value.detail` 이다
         * (`contracts/easy-doc-v1.yaml`). **한 글자도 다르게 다듬지 마라** —
         * `api` 의 `ConversionFeedbackReachTest` 가 그 예시를 읽어 응답 `detail` 과 대조한다.
         * 문구를 바꿔야 하면 계약을 먼저 고쳐라(`x-change-policy` 절차).
         */
        val OUT_OF_RANGE_MESSAGE: String = "품질 만족도는 ${RANGE.first}에서 ${RANGE.last} 사이의 값이어야 합니다"
    }
}

/**
 * 이 문서 한 건에 들인 시간(분). 게이트 ① 통과 기준 ③(중앙값)의 표본 하나다.
 *
 * 상한을 하루 근무 시간 남짓으로 둔다. 이 값은 **한 건**의 소요이므로 그보다 큰 값은
 * 대개 단위 착오(초·시간)이거나 오타이고, 표본이 10건뿐이라 그런 한 건이 중앙값을 흔든다.
 */
@JvmInline
value class MinutesSpent(val value: Int) {
    init {
        if (value !in RANGE) throw InvalidInputException(OUT_OF_RANGE_MESSAGE)
    }

    companion object {
        /** 소요 시간이 들어갈 수 있는 범위(분) — **정본은 여기 하나다.** */
        val RANGE: IntRange = 0..600

        /**
         * 거부 문구. 경계값은 [RANGE] 에서 끌어오고 **입력값은 넣지 않는다.**
         *
         * 문구의 정본은 계약의 422 예시
         * `paths./conversions/{conversion_id}/feedback.put.responses.422
         * .content.application/json.examples.minutes_out_of_range.value.detail` 이다
         * (`contracts/easy-doc-v1.yaml`). **한 글자도 다르게 다듬지 마라** —
         * `api` 의 `ConversionFeedbackReachTest` 가 그 예시를 읽어 응답 `detail` 과 대조한다.
         * 문구를 바꿔야 하면 계약을 먼저 고쳐라(`x-change-policy` 절차).
         */
        val OUT_OF_RANGE_MESSAGE: String = "소요 시간은 ${RANGE.first}에서 ${RANGE.last}분 사이의 값이어야 합니다"
    }
}

package kr.easydoc.core.document

import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
import java.time.Instant
import java.util.UUID

/**
 * 변환 한 건의 **비밀 아닌 부분** — 이 커밋이 실제로 쓰는 만큼.
 *
 * 원본: `app/models/conversion.py::Conversion`.
 *
 * ## 왜 계약 `ConversionResponse` 의 전 필드가 아닌가
 *
 * 결과 필드(초안·대응표·검수본은 물론 `provider_name`·`model`·토큰 수·
 * `missing_placeholders`·`reviewed_at`)를 **읽는 코드가 아직 없다.** 업로드는 대기 중 행을
 * 만들 뿐이고, 그 값을 채우는 것은 워커(Phase 5), 화면에 싣는 것은 변환 조회 커밋이다.
 * 쓰지 않는 컬럼을 미리 타입에 담으면 **아무도 시험하지 않는 매핑 코드**가 생기고, 그
 * 코드가 틀렸다는 사실은 그것을 처음 쓰는 커밋에서야 드러난다. 필요해지는 커밋이 더한다.
 *
 * 암호문 세 열이 여기 없는 이유는 [Document] 와 같다 — 본문을 들 이유가 있는 자리와 없는
 * 자리를 타입으로 가른다.
 *
 * ## `data class` 가 아닌 이유
 *
 * [failureCode] 는 예외 클래스명이라 그 자체는 안전하지만, 이 타입이 앞으로 담을 값
 * (`provider_name`·`model`)은 로그 허용목록(*"문서 ID·길이·처리 상태까지만"*, 프로젝트
 * `CLAUDE.md`) 밖이다. 컴파일러가 만드는 `toString()` 은 **필드가 늘 때 자동으로 넓어지므로**
 * 지금 안전하다는 사실이 다음 커밋에도 참이라는 보장이 되지 않는다. 손으로 쓴 [toString] 은
 * 반대로 **더할 때 사람이 판단하게** 만든다.
 */
class Conversion(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 로그 허용목록 그대로 — 식별자·상태·실패 코드(계획 §4.4 가 명시적으로 허용한 값). */
    override fun toString(): String = "Conversion($id, ${status.wireName}, failure=$failureCode)"
}

/**
 * 복호화된 마스킹 항목 한 건 — 검수 화면이 보여 주는 「무엇을 무엇으로 가렸는가」.
 *
 * 원본: `app/services/documents.py::MaskedItemView`.
 * 계약 `MaskedItemResponse` 의 세 필드에 1:1 대응한다.
 *
 * ## [original] 이 `String` 이 아니라 [Secret] 인 이유 — 원본과 다르게 만든 지점
 *
 * Python 은 평문 `str` 이었다(*"이미 복호화된 표현이기 때문"*). Kotlin 에서 그대로 두면
 * 이 `data class` 의 생성 `toString()` 이 **가려졌던 실제 개인정보를 그대로 찍는다** —
 * 로그 한 줄, 예외 메시지 한 줄, 컬렉션 출력 한 번이면 마스킹의 목적이 통째로 사라진다.
 *
 * [Secret] 으로 감싸면 ⑴ 어떤 경로로 흘러도 마스킹된 문자열만 나가고 ⑵ 값을 실제로 실어야
 * 하는 자리(소유자 인증을 통과한 검수 응답)가 [Secret.reveal] 호출로 **눈에 띈다.**
 * `MaskedItem.original` 이 이미 같은 이유로 [Secret] 이라, 저장 형식과 조회 형식이 같은
 * 규율을 쓰게 되는 부수 효과도 있다.
 *
 * 값이 나가는 자리는 응답 DTO 한 곳뿐이어야 한다. 그 자리는 HTTP 표면이 생기는 커밋이 만든다.
 */
data class MaskedItemView(
    val category: MaskCategory,
    val placeholder: String,
    val original: Secret,
)

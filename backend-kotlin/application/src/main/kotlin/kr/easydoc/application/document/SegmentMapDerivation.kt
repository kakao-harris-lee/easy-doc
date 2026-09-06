package kr.easydoc.application.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.alignSegments
import kr.easydoc.core.segment.splitUnits

/**
 * `segment_map` 유도 — 계획 §10.2 결정 1 「지도의 원천은 하나다」.
 *
 * 조회(`ConversionQueryService`)와 내보내기(`ConversionExportService`)가 **같은 인스턴스**를
 * 주입받아 호출해야 한다. 두 갈래가 각자 계산하면 화면이 보여 준 지도와 파일에 실제로 적용된
 * 지도가 갈릴 수 있다(계획 §10.1) — `ReflectionPlan` KDoc이 판정과 반영을 한 함수로 묶은 것과
 * 같은 이유다. 두 유스케이스는 생성자로 **같은 빈**을 주입받는다(composition root — 각 모듈의
 * `DocumentConfiguration`/`DocumentExportConfiguration`, api 슬라이스는 `AuthSliceBeans`).
 *
 * [DocumentExporter] 와 같은 모양의 `fun interface` 다 — 실물은 하나([MaskedSegmentMapDerivation])
 * 뿐이지만, 교체 가능한 포트로 두면 테스트가 새 구현이 아니라 **데코레이터**로 호출 인자를
 * 기록할 수 있다(§10.4 「지도 계산이 조회와 내보내기에서 같은 함수를 지남」 — 두 서비스에
 * 같은 대역을 꽂아 호출 인자가 같음을 단언하는 테스트).
 */
fun interface SegmentMapDerivation {
    /**
     * [source] 가 없거나 [body] 가 없으면 `null` 로 접는다 — 예외로 튀지 않는다. 이 값은
     * 파생값이고, 호출부의 유스케이스 자체를 막을 이유가 아니다.
     *
     * [body] 는 두 호출부 모두 `edited_text ?: easy_text` — 자리표시자를 **복원하기 전**의
     * 원문이다(오늘 `ConversionQueryService:112`가 그렇다). 내보내기가 실제로 쓰는 복원된
     * 본문으로 지도를 다시 구하지 않는 이유는 자리표시자가 앵커이기 때문이다 — 복원하면 그
     * 앵커가 실제 개인정보 값으로 바뀌어 마스킹된 원문과 더는 짝지어지지 않는다.
     */
    fun deriveOrNull(
        source: StoredSourceText?,
        body: PlainBody?,
    ): SegmentMap?
}

/**
 * [SegmentMapDerivation] 의 유일한 실물 — 마스킹된 원문과 본문에서 매번 다시 계산한다.
 *
 * **저장하지 않는다** — 매 호출마다 (마스킹된 원문, 본문)에서 [alignSegments] 로 다시 계산한다.
 * 앵커(마스킹 자리표시자·사실)가 원문·본문 양쪽에서 성립하려면 **같은 마스킹을 원문에 다시
 * 적용해야 한다** — `ConvertDocumentUseCase.Pass.run` 이 LLM 에 넘긴 것이 마스킹된 원문이고,
 * 그 결과 본문에 남는 것도 그 마스킹이 심은 자리표시자이기 때문이다(`maskText` 는 결정적이고
 * 줄 수를 바꾸지 않으므로 색인이 원문 그대로와도 일치한다).
 *
 * [source] 는 호출부가 **자신의 트랜잭션 경계 안에서** 미리 읽어 넘긴다 — 이 클래스가 직접
 * `DocumentRepository` 를 부르지 않는 것은 조회(`ConversionQueryService.read`)가 원문 읽기와
 * 복호화를 **다른 경계**(읽기는 안, 복호화는 밖)로 가르기 때문이다. 그 경계를 이 클래스가
 * 대신 정하면 그 규칙이 갈린다 — 각 유스케이스가 자신의 경계 규칙대로 [source] 를 얻어
 * 넘기고, 이 클래스는 **그 뒤의 계산**(복호화 → 마스킹 → 정렬)만 공유한다.
 */
class MaskedSegmentMapDerivation(private val cipher: ContentCipher) : SegmentMapDerivation {
    override fun deriveOrNull(
        source: StoredSourceText?,
        body: PlainBody?,
    ): SegmentMap? {
        if (source == null || body == null) return null
        val sourceText = cipher.decrypt(source.sourceText, source.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val maskedSource = maskText(sourceText.value).maskedText.value
        return alignSegments(splitUnits(maskedSource), splitUnits(body.value))
    }
}

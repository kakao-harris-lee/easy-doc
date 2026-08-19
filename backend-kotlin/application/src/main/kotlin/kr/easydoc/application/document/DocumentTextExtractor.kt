package kr.easydoc.application.document

import kr.easydoc.core.document.SourceFormat

/**
 * 업로드 파일에서 본문 텍스트를 뽑는 포트.
 *
 * 원본: `app/ingest/extractors.py::extract_text`.
 * 구현은 `infrastructure/ingest` 에 있고 POI·PDFBox·commons-compress 를 쓴다 —
 * 유스케이스는 그 라이브러리들을 **타입으로도 모른다**(계획 §3.2).
 *
 * ## 던지는 것
 *
 * - `UnsupportedFormatException` — 지원하지 않는 확장자(구버전 `.hwp` 포함). → 422
 * - `DocumentExtractionException` — 손상·암호·텍스트 없음·압축 폭탄·추출 길이 초과. → 422
 *
 * 둘 다 **메시지에 파일 이름도 본문 조각도 담지 않는다**. 그 규약이 `app/api/errors.py`
 * 와 `GlobalExceptionHandler` 가 메시지를 그대로 응답 `detail` 에 실어도 되는 근거다.
 *
 * ## 동기 · CPU 바운드
 *
 * 큰 문서는 수 초가 걸리고 힙을 수십 MB 쓴다. 컨테이너 스레드 수만큼 동시에 들어오면
 * 곱해져서 OOM 이므로 **동시 진입이 제한된 구현으로 배선한다**(원본의
 * `CapacityLimiter(4)` 와 같은 자리). 그 제한은 이 포트의 데코레이터가 진다.
 */
fun interface DocumentTextExtractor {
    /**
     * [filename] 의 확장자로 형식을 가려 [bytes] 에서 본문을 뽑는다.
     *
     * [filename] 은 **형식 판별에만 쓰이고 버려진다** — 저장하지도 로그에 남기지도 않는다.
     */
    fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument
}

/**
 * 추출 결과 — 가려낸 형식과 정규화된 본문.
 *
 * ## `data class` 가 아닌 이유
 *
 * `data class` 는 컴파일러가 `toString()` 을 만들어 주고, 그 산출에 [text] 가 통째로
 * 실린다. 본문은 개인정보 포함 여부와 무관하게 로그 금지다(프로젝트 `CLAUDE.md`).
 * 손으로 쓴 [toString] 이 길이만 남긴다 — `SensitiveToStringReachTest` 의 「일반 class 의
 * 손으로 쓴 toString」 축이 이 재정의를 실제로 시험한다.
 *
 * [text] 를 `PlainBody` 로 감싸지 않는 이유: `PlainBody` 는 **저장 경로의 정의역**이고
 * 그 생성 지점은 유스케이스다(게이트 25 X1 의 도달 자리). 추출기가 미리 감싸면 저장하지
 * 않는 경로(길이 초과로 거절되는 업로드)에서도 그 판정을 지나게 되어, 두 검사의 경계가
 * 흐려진다.
 */
class ExtractedDocument(
    val format: SourceFormat,
    val text: String,
) {
    /** 형식과 길이만 남긴다. 본문은 나가지 않는다. */
    override fun toString(): String = "ExtractedDocument(${format.wireName}, ${text.length}자)"
}

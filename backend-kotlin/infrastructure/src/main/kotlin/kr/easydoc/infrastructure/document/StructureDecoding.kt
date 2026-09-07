package kr.easydoc.infrastructure.document

import kr.easydoc.core.segment.SourceStructure
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("kr.easydoc.infrastructure.document.StructureDecoding")

/**
 * `documents.source_unit_kinds` 컬럼 값을 구조로 디코딩하는 **DB 읽기 경계 전용** 안전판
 * (리뷰 BLOCK 1, P0-4 S8-2).
 *
 * [SourceStructure.decode] 자신은 fail-fast 다 — 알 수 없는 코드 문자는 우리가 저장한 값이
 * 깨졌다는 뜻이라 예외를 던진다([SourceStructure.decode] KDoc, `UnitKindTest` 가 그 계약을
 * 고정한다). 하지만 구조는 **파생 정보**이지 변환을 막을 이유가 아니다(계획 §1.2 「불변식이
 * 깨지면 예외가 아니라 전부 BODY 로 접는다」와 같은 방침을 DB 읽기 경계까지 넓힌다) — 저장된
 * 바이트 하나가 손상됐다고 [kr.easydoc.infrastructure.document.JdbcConversionWorkStore
 * .loadForProcessing] 이 던져 변환 작업이 재시도 상한까지 실패하거나(worker),
 * [kr.easydoc.infrastructure.document.JdbcDocumentRepository.findOwnedSource] 가 던져
 * 재변환 조회가 500 을 내면 안 된다.
 *
 * 그래서 이 함수가 그 경계에서 예외를 삼키고 `null`(호출자가 전부 BODY 로 읽는 값,
 * [kr.easydoc.application.document.StoredSourceText.structureOrBody] 와 같은 신호)로 접는다.
 * 삼킬 때 WARN 으로 [documentId] 와 [raw] 의 **길이만** 남긴다 — 이 값은 사용자가 올린 문서에서
 * 유도된 값이라 원문 조각(손상된 값 자체)을 로그에 싣지 않는다(CLAUDE.md 「사용자 문서 본문…을
 * 로그에 남기지 않는다」와 같은 원칙).
 */
@Suppress("SwallowedException")
fun decodeStructureOrNull(
    raw: String,
    documentId: UUID,
): SourceStructure? =
    try {
        SourceStructure.decode(raw)
    } catch (exc: IllegalArgumentException) {
        // 예외 메시지에는 알 수 없는 코드 문자 하나가 실린다(`UnitKind.ofCode`) — 그마저도
        // WARN 에 담지 않는다. documentId·length 만으로 운영자가 어떤 문서를 다시 올려야
        // 하는지 알 수 있고, 값 자체는 사용자 문서에서 유도된 것이라 로그 금지 대상이다.
        log.warn(
            "documents.source_unit_kinds 디코딩 실패 — 전부 BODY 로 접는다: documentId={}, length={}",
            documentId,
            raw.length,
        )
        null
    }

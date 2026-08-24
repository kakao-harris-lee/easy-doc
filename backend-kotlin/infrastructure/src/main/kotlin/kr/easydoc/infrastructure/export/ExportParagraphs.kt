package kr.easydoc.infrastructure.export

/** 내려받을 본문을 문단으로 나눈다. 빈 본문은 빈 문단 하나다. */
internal fun exportParagraphs(body: String): List<String> = body.split("\r\n", "\n")

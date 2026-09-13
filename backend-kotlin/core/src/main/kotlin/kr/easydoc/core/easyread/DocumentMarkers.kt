package kr.easydoc.core.easyread

/**
 * 원문 위치를 찾는 제목·목록·주의 표식. 날짜의 점, 음수, 전화번호의 붙임표,
 * 표의 빈 값 '-'는 항목 표식으로 세지 않는다. ※는 문장 안에서도 안내의 시작이다.
 */
private val DOCUMENT_MARKER =
    Regex(
        """(?m)^[\t ]*([○●□■◎◇◆▶▷•◦①-⑳]|[-*](?=[\t ]+\S)|""" +
            """(?:\d{1,3}|[가나다라마바사아자차카타파하])[.)](?=[\t ]*[^\d\s]))(?=[\t ]*\S)|※""",
    )

/** 표식만 순서대로 추출한다. 원문이나 주변 문장을 진단 값에 복사하지 않는다. */
fun documentMarkers(text: String): List<String> =
    DOCUMENT_MARKER.findAll(text).map { it.groups[1]?.value ?: "※" }.toList()

/**
 * 원문에 표식이 있으면 종류·개수·순서를 유지해야 한다. 없으면 새 목록 작성을 허용한다.
 * 들여쓰기나 ※ 안내를 다음 줄로 옮기는 것은 허용한다. 같은 표식끼리의 의미상 위치가
 * 바뀌었는지는 이 검사만으로 판단할 수 없어 프롬프트와 원문 대조로 함께 확인한다.
 */
fun hasMarkerChanges(
    source: String,
    draft: String,
): Boolean {
    val expected = documentMarkers(source)
    return expected.isNotEmpty() && expected != documentMarkers(draft)
}

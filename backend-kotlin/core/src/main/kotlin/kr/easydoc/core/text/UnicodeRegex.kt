package kr.easydoc.core.text

import java.util.regex.Pattern

/** 유니코드를 인식하는 문자 클래스로 정규식을 컴파일한다. */
internal fun unicodeRegex(pattern: String): Regex = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS).toRegex()

package kr.easydoc.core.privacy

/** `toString()` 이 값을 가릴 때 그 자리에 대신 찍는 표식. */
const val CONTENT_MASK: String = "***"

/** **이 타입은 사용자 콘텐츠를 담는다** — 필드 **이름**만 봐서는 드러나지 않는 자리에 붙인다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UserContent

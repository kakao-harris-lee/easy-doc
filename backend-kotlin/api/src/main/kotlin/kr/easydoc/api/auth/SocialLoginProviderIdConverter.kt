package kr.easydoc.api.auth

import kr.easydoc.application.auth.SocialLoginProviderId
import org.springframework.core.convert.converter.Converter

/**
 * 경로 `provider` 값을 계약 enum 으로 읽는다 — `ExportFormatConverter` 와 같은 자리
 * (그 클래스 KDoc). `WebMvcConfig` 가 등록한다.
 *
 * enum 밖 값(빈 값·공백·그 밖 전부, 예: `foo`)은 평범한 `IllegalArgumentException` 을
 * 던진다 — Spring 변환 실패로 이어져 `GlobalExceptionHandler.handleTypeMismatch` 가 422
 * **배열** `detail` 로 옮긴다(`ValueSlotInvariantReachTest` 가 재는 스키마 층 불변식).
 * "그 provider 가 서버에 설정되지 않았다"는 다른 층(도메인 규칙, `SocialLoginService`)이라
 * 여기서 가르지 않는다 — 이 변환기는 "이 문자열이 아는 provider 인가"만 본다.
 */
class SocialLoginProviderIdConverter : Converter<String, SocialLoginProviderId> {
    override fun convert(source: String): SocialLoginProviderId =
        SocialLoginProviderId.entries.firstOrNull { it.wireValue == source }
            ?: throw IllegalArgumentException(UNSUPPORTED)

    private companion object {
        const val UNSUPPORTED: String = "unsupported social login provider"
    }
}

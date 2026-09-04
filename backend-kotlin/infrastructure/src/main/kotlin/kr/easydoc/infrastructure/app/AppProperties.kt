package kr.easydoc.infrastructure.app

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 서비스가 사용자에게 보여 주는 절대 URL의 기준. 바인딩 접두사는 `easydoc.app`.
 *
 * 지금은 변환 완료 알림 메일의 링크([kr.easydoc.application.conversion.ConversionCompletedNotifier])
 * 하나가 쓴다. 프런트엔드 도메인이지 API 도메인이 아니다 — 기본값(`http://localhost:5173`)은
 * `frontend/vite.config.ts` 의 dev 서버 기본 포트다(`api`의 `easydoc.cors-origins` 기본값과
 * 같은 자리를 가리킨다).
 */
@ConfigurationProperties(prefix = "easydoc.app")
data class AppProperties(val publicBaseUrl: String = DEFAULT_PUBLIC_BASE_URL) {
    private companion object {
        const val DEFAULT_PUBLIC_BASE_URL: String = "http://localhost:5173"
    }
}

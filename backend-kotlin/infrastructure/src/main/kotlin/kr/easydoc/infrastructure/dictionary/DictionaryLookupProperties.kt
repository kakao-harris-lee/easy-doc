package kr.easydoc.infrastructure.dictionary

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 검수 화면 조회(P0-5) 설정. 바인딩 접두사는 `easydoc.dictionary.lookup`.
 *
 * worker 의 프롬프트 주입 스위치([DictionaryProperties.enabled])와 **의도적으로 분리한
 * 이름**이다 - 프롬프트 주입은 기본이 켜짐(계획 §3.2 정책)이지만, 조회는 API 프로세스가
 * 색인을 새로 읽는 경로라 기본값을 켜짐으로 두면 이번 변경만으로 API 기동이 1.5MB JSON을
 * 읽기 시작하는 부작용이 생긴다. 그래서 [enabled] 기본값은 **꺼짐**이다 - 실제 조회
 * 엔드포인트(조각 4)가 배선되고 운영자가 명시적으로 켤 때만 API 가 색인을 적재한다.
 *
 * `max-query-chars`·`rate-limit-per-minute`(계획 조각 3)은 HTTP 컨트롤러가 생기는 조각 4에서
 * 추가한다 - 지금은 소비하는 곳이 없어 여기 두지 않는다.
 */
@ConfigurationProperties(prefix = "easydoc.dictionary.lookup")
data class DictionaryLookupProperties(val enabled: Boolean = false)

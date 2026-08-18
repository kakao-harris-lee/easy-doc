package kr.easydoc.infrastructure.auth

import java.util.Base64

/**
 * Argon2 PHC 문자열의 파라미터 집합.
 *
 * ## 왜 직접 파싱하는가
 *
 * 재해시 판정(`migration-safety-gate` I-8 검증 4)이 요구하는 것은 **전체 파라미터
 * 동등성**이다. Spring Security `Argon2PasswordEncoder.upgradeEncoding()` 은 `memory` 와
 * `iterations` 의 **"미만"만** 본다 — 파라미터를 **낮춘** 경우와 `parallelism`·salt 길이·
 * hash 길이만 바뀐 경우를 "최신"으로 오판한다(Phase 0 탐침 7건 중 5건 불일치).
 * 지금은 무해해도 파라미터를 바꾸는 날 **이관이 조용히 멈춘다.**
 *
 * Spring Security 의 `Argon2EncodingUtils` 는 **패키지 전용**이라 밖에서 부를 수 없다
 * (7.1.0 실측: `final class`, `static` 메서드에 접근 제어자 없음). 그래서 판정에 필요한
 * 만큼만 여기서 읽는다. **해시 계산·검증은 여전히 라이브러리가 한다** — 여기서 하는 것은
 * 문자열 파싱뿐이고 암호 프리미티브를 조립하지 않는다(I-7·I-8 의 "즉흥 암호 금지"와 같은
 * 선).
 *
 * ## 형식
 *
 * `$argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>` — salt·hash 는 **패딩 없는 base64**.
 * `v=` 는 생략될 수 있고(초기 Argon2 명세), 그때 버전은 0x10 = 16 이다. `m,t,p` 뒤에
 * `keyid`·`data` 같은 선택 항목이 붙을 수 있어 **필요한 키만 골라 읽는다**.
 */
internal data class Argon2Phc(
    val variant: String,
    val version: Int,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltLength: Int,
    val hashLength: Int,
) {
    companion object {
        /** `v=` 가 없는 PHC 의 버전. Argon2 초기 명세(0x10). */
        private const val LEGACY_VERSION = 16

        private const val VARIANT_SEGMENT = 1
        private const val MIN_SEGMENTS = 5

        private val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()

        /**
         * PHC 문자열을 읽는다. 형식이 아니면 `null` — **예외를 던지지 않는다.**
         *
         * 호출자(재해시 판정)는 읽지 못한 해시를 "현행 정책과 다르다"로 다루면 되고,
         * 여기서 예외를 던지면 로그인 경로에 새 실패 지점이 생긴다. 실패 원인을 갈라
         * 알려 주지 않는 것은 복호화 oracle 을 만들지 않는 것과 같은 이유다.
         */
        fun parse(encoded: String): Argon2Phc? {
            // `$argon2id$...` 이므로 첫 조각은 빈 문자열이다.
            val segments = encoded.split('$')
            if (segments.size < MIN_SEGMENTS || segments.first().isNotEmpty()) {
                return null
            }
            val variant = segments[VARIANT_SEGMENT]
            if (!variant.startsWith("argon2")) {
                return null
            }

            // `v=` 는 있을 수도 없을 수도 있다. 있으면 파라미터 조각이 한 칸 뒤로 밀린다.
            val versionSegment = segments.getOrNull(2).orEmpty()
            val hasVersion = versionSegment.startsWith("v=")
            val version =
                if (hasVersion) {
                    versionSegment
                        .removePrefix(
                            "v=",
                        ).toIntOrNull() ?: return null
                } else {
                    LEGACY_VERSION
                }
            val parameterIndex = if (hasVersion) 3 else 2

            val parameters = readParameters(segments.getOrNull(parameterIndex)) ?: return null
            val salt = decodeUnpadded(segments.getOrNull(parameterIndex + 1)) ?: return null
            val hash = decodeUnpadded(segments.getOrNull(parameterIndex + 2)) ?: return null

            return Argon2Phc(
                variant = variant,
                version = version,
                memoryKib = parameters["m"] ?: return null,
                iterations = parameters["t"] ?: return null,
                parallelism = parameters["p"] ?: return null,
                saltLength = salt,
                hashLength = hash,
            )
        }

        /** `m=65536,t=3,p=4[,keyid=...]` 를 읽는다. 값이 정수가 아닌 항목은 버린다. */
        private fun readParameters(segment: String?): Map<String, Int>? {
            if (segment.isNullOrEmpty()) {
                return null
            }
            return segment
                .split(',')
                .mapNotNull { entry ->
                    val separator = entry.indexOf('=')
                    if (separator <= 0) {
                        return@mapNotNull null
                    }
                    val value = entry.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
                    entry.take(separator) to value
                }.toMap()
        }

        /**
         * 패딩 없는 base64 를 풀어 **바이트 길이만** 돌려준다.
         *
         * 내용은 쓰지 않는다 — 필요한 것은 salt·hash 의 길이뿐이고, 값을 들고 다니면
         * 로그로 샐 자리가 하나 는다.
         */
        private fun decodeUnpadded(segment: String?): Int? {
            if (segment.isNullOrEmpty()) {
                return null
            }
            val padding =
                when (segment.length % 4) {
                    0 -> ""

                    2 -> "=="

                    3 -> "="

                    // 길이 %4 == 1 은 base64 로 나올 수 없다.
                    else -> return null
                }
            return runCatching { BASE64_DECODER.decode(segment + padding).size }.getOrNull()
        }
    }
}

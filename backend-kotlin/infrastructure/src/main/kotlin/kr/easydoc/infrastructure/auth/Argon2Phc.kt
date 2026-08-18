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
        private const val VERSION_SEGMENT = 2
        private const val MIN_SEGMENTS = 5

        private const val VARIANT_PREFIX = "argon2"
        private const val VERSION_PREFIX = "v="

        private const val MEMORY_KEY = "m"
        private const val ITERATIONS_KEY = "t"
        private const val PARALLELISM_KEY = "p"

        /** base64 는 4문자 단위다. 나머지에 따라 붙일 패딩이 정해지고, 1은 나올 수 없다. */
        private const val BASE64_GROUP = 4
        private val PADDING_BY_REMAINDER = mapOf(0 to "", 2 to "==", 3 to "=")

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
            val header = readHeader(segments) ?: return null
            return readBody(segments, header)
        }

        /** 변형·버전과, 파라미터 조각이 몇 번째인지. `v=` 는 생략될 수 있어 자리가 밀린다. */
        private fun readHeader(segments: List<String>): Header? {
            val variant =
                segments
                    .takeIf { it.size >= MIN_SEGMENTS && it.first().isEmpty() }
                    ?.get(VARIANT_SEGMENT)
                    ?.takeIf { it.startsWith(VARIANT_PREFIX) }
                    ?: return null
            val versionSegment = segments.getOrNull(VERSION_SEGMENT).orEmpty()
            return if (versionSegment.startsWith(VERSION_PREFIX)) {
                versionSegment
                    .removePrefix(VERSION_PREFIX)
                    .toIntOrNull()
                    ?.let { Header(variant, it, VERSION_SEGMENT + 1) }
            } else {
                Header(variant, LEGACY_VERSION, VERSION_SEGMENT)
            }
        }

        /** 비용 셋과 salt·hash 길이. 하나라도 못 읽으면 통째로 `null` 이다. */
        private fun readBody(
            segments: List<String>,
            header: Header,
        ): Argon2Phc? {
            val costs = readCosts(segments.getOrNull(header.parameterIndex)) ?: return null
            val saltLength = decodedLength(segments.getOrNull(header.parameterIndex + 1))
            val hashLength = decodedLength(segments.getOrNull(header.parameterIndex + 2))
            return if (saltLength != null && hashLength != null) {
                Argon2Phc(
                    variant = header.variant,
                    version = header.version,
                    memoryKib = costs.memoryKib,
                    iterations = costs.iterations,
                    parallelism = costs.parallelism,
                    saltLength = saltLength,
                    hashLength = hashLength,
                )
            } else {
                null
            }
        }

        /**
         * `m=65536,t=3,p=4[,keyid=...]` 에서 비용 셋을 읽는다. 하나라도 없으면 `null`.
         *
         * 셋을 한 타입으로 묶는 이유는 **부분적으로 읽힌 상태를 만들지 않기 위해서**다.
         * 개별 `Int?` 로 들고 다니면 호출부마다 같은 널 검사를 되풀이하게 되고, 한 곳에서
         * 빠뜨려도 컴파일된다.
         */
        private fun readCosts(segment: String?): Costs? {
            val values = readKeyValues(segment)
            val memory = values[MEMORY_KEY]
            val iterations = values[ITERATIONS_KEY]
            val parallelism = values[PARALLELISM_KEY]
            return if (memory != null && iterations != null && parallelism != null) {
                Costs(memory, iterations, parallelism)
            } else {
                null
            }
        }

        /** `키=정수` 쌍만 남긴다. 선택 항목(`keyid`·`data`)은 정수가 아니라 조용히 버려진다. */
        private fun readKeyValues(segment: String?): Map<String, Int> =
            segment
                .orEmpty()
                .split(',')
                .mapNotNull { entry ->
                    val separator = entry.indexOf('=')
                    val value = entry.substring(separator + 1).toIntOrNull()
                    if (separator <= 0 || value == null) null else entry.take(separator) to value
                }.toMap()

        /**
         * 패딩 없는 base64 를 풀어 **바이트 길이만** 돌려준다.
         *
         * 내용은 쓰지 않는다 — 필요한 것은 salt·hash 의 길이뿐이고, 값을 들고 다니면
         * 로그로 샐 자리가 하나 는다.
         */
        private fun decodedLength(segment: String?): Int? {
            if (segment.isNullOrEmpty()) {
                return null
            }
            return PADDING_BY_REMAINDER[segment.length % BASE64_GROUP]
                ?.let { padding -> runCatching { BASE64_DECODER.decode(segment + padding).size }.getOrNull() }
        }
    }

    /** Argon2 비용 파라미터 셋. 셋이 함께 있어야 의미가 있으므로 한 타입으로 묶는다. */
    private data class Costs(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    /** [parse] 가 앞부분에서 읽어 낸 것. 뒷부분을 어디서부터 읽을지도 함께 든다. */
    private data class Header(
        val variant: String,
        val version: Int,
        val parameterIndex: Int,
    )
}

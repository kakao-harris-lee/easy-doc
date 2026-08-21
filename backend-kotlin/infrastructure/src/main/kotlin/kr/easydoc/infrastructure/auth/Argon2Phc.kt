package kr.easydoc.infrastructure.auth

import java.util.Base64

/** Argon2 PHC 문자열의 파라미터 집합. */
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

        /** PHC 문자열을 읽는다. 형식이 아니면 `null` — **예외를 던지지 않는다.** */
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

        /** `m=65536,t=3,p=4[,keyid=...]` 에서 비용 셋을 읽는다. 하나라도 없으면 `null`. */
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

        /** 패딩 없는 base64 를 풀어 **바이트 길이만** 돌려준다. */
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

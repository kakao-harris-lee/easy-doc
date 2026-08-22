package kr.easydoc.api.support

import org.assertj.core.api.Assertions.assertThat
import java.net.http.HttpResponse

/** 소유권 은닉의 성질 P1 — 「응답 구별 불가」의 판정 한 벌. */
object OwnershipConcealment {
    /**
     * 응답마다 값이 달라지는 헤더 — 이름 집합 비교에서 뺀다. **면제 목록이라 커지면 이
     * 판정이 보는 것이 줄어들고**, 그 증가는 [MAX_VARIABLE_HEADERS] 가 드러낸다.
     */
    val VARIABLE_HEADERS: Set<String> = setOf("date")

    /** [VARIABLE_HEADERS] 의 **개수 상한**. 여유가 0 이고 상향은 이력 라쳇이 막는다. */
    const val MAX_VARIABLE_HEADERS = 1

    /** 한 응답에서 P1 이 보는 것 셋. 판정을 응답 객체에서 떼어 낸다. */
    class Observation(
        val status: Int,
        val body: ByteArray,
        val headerNames: Set<String>,
    )

    fun observe(response: HttpResponse<ByteArray>): Observation =
        Observation(response.statusCode(), response.body(), headerNames(response))

    /** 응답의 헤더 이름 집합(소문자). [VARIABLE_HEADERS] 는 뺀다. */
    fun headerNames(response: HttpResponse<*>): Set<String> =
        response
            .headers()
            .map()
            .keys
            .map { it.lowercase() }
            .toSet() - VARIABLE_HEADERS

    /** P1 전부를 잰다 — 없는 것([absent])과 남의 것([others])이 구별되지 않는가. */
    fun assertIndistinguishable(
        label: String,
        absent: HttpResponse<ByteArray>,
        others: HttpResponse<ByteArray>,
    ) = assertIndistinguishable(label, observe(absent), observe(others))

    /** 같은 판정을 관측값으로 한다. 대조 프로브가 이 갈래를 쓴다. */
    fun assertIndistinguishable(
        label: String,
        absent: Observation,
        others: Observation,
    ) {
        assertThat(others.status)
            .withFailMessage(
                "%s: 없는 것은 %d, 남의 것은 %d — 상태 코드가 존재 여부를 흘린다",
                label,
                absent.status,
                others.status,
            ).isEqualTo(absent.status)

        assertThat(others.body)
            .withFailMessage(
                "%s: 두 팔의 응답 **바이트**가 다르다 — 없는 것 %d바이트 / 남의 것 %d바이트",
                label,
                absent.body.size,
                others.body.size,
            ).isEqualTo(absent.body)
        assertThat(others.headerNames)
            .withFailMessage(
                "%s: 헤더 이름 집합이 다르다 — 없는 것에만 %s, 남의 것에만 %s",
                label,
                absent.headerNames - others.headerNames,
                others.headerNames - absent.headerNames,
            ).isEqualTo(absent.headerNames)
    }
}

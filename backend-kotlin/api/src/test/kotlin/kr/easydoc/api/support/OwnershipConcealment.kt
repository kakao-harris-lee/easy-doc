package kr.easydoc.api.support

import org.assertj.core.api.Assertions.assertThat
import java.net.http.HttpResponse

/**
 * **소유권 은닉의 성질 P1 — 「응답 구별 불가」의 판정 한 벌.**
 *
 * 같은 요청자가 ⑴ **없는** 식별자와 ⑵ **남의** 식별자를 지목했을 때, 상태 코드 · 응답 본문의
 * **원시 바이트** · 헤더 **이름 집합**이 같다(시각처럼 매 응답 달라지는 헤더는 뺀다).
 *
 * ## 왜 파일 하나인가 — 여러 자리가 같은 성질을 서로 다른 강도로 재고 있었다
 *
 * `privacy-gate` 회차 2 의 처방 **X1-1** 이 지목한 것이다. 지목된 형제는 셋
 * (`DocumentListReachTest` DL-9 ② · `DocumentDeleteReachTest` DD-3 ·
 * `WorkspaceEndpointReachTest` WR-4)이었으나, 같은 성질을 재는 자리를 전수로 훑으면
 * **다섯**이다 — 위 셋에 `WorkspaceEndpointReachTest` WD-2·WD-3 과
 * `DocumentEndpointReachTest` DC-17(`POST /documents` 의 남의 작업 공간)이 더해진다.
 * 다섯 중 하나를 빼고 「판정 한 벌」이라 적으면 그 순간 거짓 전칭이 된다.
 *
 * | 자리 | 상태 | 본문 | 헤더 이름 집합 |
 * |---|---|---|---|
 * | DL-9 ② (고치기 전) | 잰다 | **UTF-8 디코딩 문자열** | **0건** |
 * | 나머지 넷 (고치기 전) | 잰다 | **UTF-8 디코딩 문자열** | 잰다 |
 *
 * 헤더 축은 한 자리가 아예 비어 있었고, 본문 축은 **다섯 자리 모두** 바이트 단언이 아니었다.
 * 디코딩을 지나면 원시 바이트의 차이가 사라질 수 있다 — 다른 인코딩으로 같은 글자를 내보내는
 * 응답, 짝 없는 서로게이트가 대체 문자로 접히는 응답이 그 형태다. 은닉의 요구는 「같은 글자가
 * 나간다」가 아니라 **「나간 바이트가 같다」**이므로 바이트로 잰다.
 *
 * 판정을 자리마다 두지 않는 이유는 그 표 자체다 — 여러 벌이면 한쪽만 고쳐지는 날 **같은
 * 성질을 서로 다른 강도로 재면서 전부 초록**이 된다.
 *
 * ## 합치면 새 단일 실패점이 생긴다 — 그 자리를 대조 프로브가 받는다
 *
 * 이 파일은 JUnit 애너테이션이 없어 **테스트 클래스가 아니고**, 하네스의 개수·단언 하한 표
 * 분모 밖이다(실측). 즉 아래 단언을 비우는 한 줄에 자동 신호가 0 이 된다. 그래서
 * `DocumentListReachTest` 의 「공유 판정이 상태·바이트·헤더 세 축 모두에서 구별한다」가
 * 축마다 하나씩 합성 관측을 먹여 **셋 다 발화함**을 실행으로 고정한다.
 *
 * ## 제외 헤더도 여기 한 벌만 둔다
 *
 * [VARIABLE_HEADERS] 가 그것이다. 종전에는 이 집합이 **파일마다 따로 선언**돼 있었고 값이
 * 이미 갈려 있었다(실측: `DocumentEndpointReachTest` 만 `content-length` 를 더 뺐다).
 * 제외 집합이 갈리면 「헤더 이름 집합이 같다」의 뜻이 자리마다 달라진다.
 *
 * `content-length` 를 제외하지 않는 이유: 두 팔의 본문 바이트가 같아야 하므로 길이도 같다.
 * 길이가 갈리는 순간 그것은 잡음이 아니라 **재야 할 회귀**다.
 *
 * ## 이 판정이 증명하지 **못하는** 것
 *
 * - **거절 비용의 무상관(P2).** 상태·바이트·헤더가 같아도 **일하는 양**이 다르면 존재가
 *   새어 나간다. 그 성질은 이 파일이 재지 않는다 — 단건 오퍼레이션에서는 응답 시간 축이,
 *   목록 오퍼레이션에서는 `JdbcDocumentStoreTest` 의 **문장 수** 축이 진다(X1-2).
 * - **헤더 값.** 이름 집합만 잰다. 값까지 재면 `date` 밖에도 응답마다 달라지는 자리
 *   (`vary` 의 순서 등)가 잡음으로 들어온다. 값이 존재를 흘리는 형태는 아직 관측되지 않았고,
 *   관측되면 그때 이 파일에 축을 더한다.
 */
object OwnershipConcealment {
    /**
     * 응답마다 값이 달라지는 헤더 — **이름 집합** 비교에서 뺀다.
     *
     * 이름이 아니라 **값**이 달라지는 자리라서 이름 집합에는 원래 영향이 없다. 그런데도 빼는
     * 이유는 한쪽 팔에만 붙는 일이 실제로 있기 때문이다(오류 응답의 `date` 는 컨테이너가
     * 만들고, 본문 없는 응답에는 붙지 않는 구현도 있다).
     */
    val VARIABLE_HEADERS: Set<String> = setOf("date")

    /**
     * 한 응답에서 P1 이 보는 것 셋. **판정을 응답 객체에서 떼어 낸다.**
     *
     * 떼어 내는 이유는 대조 프로브다 — 세 축이 각각 발화하는지를 재려면 「상태는 같고
     * 바이트만 다른」 같은 조합을 만들어야 하고, 실제 HTTP 응답으로는 그 조합을 만들 수 없다.
     */
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

    /**
     * **P1 전부**를 잰다 — 없는 것([absent])과 남의 것([others])이 구별되지 않는가.
     *
     * @param label 실패 메시지가 어느 오퍼레이션인지 말하게 한다. 세 자리가 같은 판정을
     *   쓰므로 이것이 없으면 어디서 깨졌는지 메시지만 보고 알 수 없다.
     */
    fun assertIndistinguishable(
        label: String,
        absent: HttpResponse<ByteArray>,
        others: HttpResponse<ByteArray>,
    ) = assertIndistinguishable(label, observe(absent), observe(others))

    /**
     * 같은 판정을 **관측값**으로 한다. 대조 프로브가 이 갈래를 쓴다.
     */
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
        // 문구 차이 하나가 존재를 흘린다. **바이트**로 본다 — 디코딩을 지나면 인코딩 차이가
        // 사라진다.
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

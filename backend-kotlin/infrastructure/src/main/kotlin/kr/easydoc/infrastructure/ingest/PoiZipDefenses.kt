package kr.easydoc.infrastructure.ingest

import org.apache.poi.openxml4j.util.ZipSecureFile

/**
 * POI 자신의 zip 방어값을 **우리 예산에 맞춰 낮춘다** (계획 §5 D-8).
 *
 * ## 왜 필요한가 — 기본값이 계약 예산보다 훨씬 헐겁다
 *
 * 공식 문서 확인(계획 §1.2 Q-1)한 기본값:
 *
 * | 항목 | POI 기본값 | 우리 예산 |
 * |---|---|---|
 * | `maxEntrySize` | `0xFFFFFFFF` (4GiB-1) | [ZIP_UNCOMPRESSED_BUDGET_BYTES] (50MiB) |
 * | `minInflateRatio` | 0.01 | 그대로 |
 * | `maxFileCount` | 1000 | 그대로 |
 *
 * 항목 하나가 4GiB 까지 허용되는 상태를 그대로 두지 않는다.
 *
 * ## 이 값들은 **JVM 전역 static** 이다
 *
 * `ZipSecureFile` 의 설정은 인스턴스가 아니라 클래스에 붙는다. 그래서
 *
 * - 기동 시 한 번만 적용하면 되고([apply] 는 여러 번 불러도 같은 값을 쓴다),
 * - **테스트가 서로를 오염시킨다.** 이 값을 바꿔 보는 테스트는 반드시 되돌려야 한다.
 *
 * ## 이것은 1차 방어가 아니라 **backstop** 이다 (정직하게 적는다)
 *
 * 업로드는 [ZipBudget.ensureWithinBudget] 를 **먼저** 지나고, 그 검사는 POI 를 부르기 전에
 * 압축 폭탄을 거절한다. 따라서 여기 설정을 지워도 zip bomb fixture 는 여전히 거부된다 —
 * 즉 **「폭탄이 막히는가」축의 행동 음성 대조는 성립하지 않는다.**
 *
 * **다만 「조립이 이 값을 설치하는가」축은 음성 대조가 성립한다**(게이트 27 codex C-10).
 * `IngestDefensesTest` 가 전역 값을 일부러 어긋뜨린 뒤 [IngestConfiguration] 의 빈 생성만
 * 부르고 값이 돌아오는지 본다 — [apply] 호출을 지우면 빨개진다. 이전 판은 테스트가
 * **스스로** [apply] 를 불러 그 축마저 재지 못했다.
 *
 * 그럼에도 두는 이유: [ZipBudget] 을 지나지 않는 경로가 생기는 날(예: POI 로 여는 다른
 * 형식을 더할 때) 그 경로가 4GiB 짜리 무방비로 시작하지 않게 한다.
 *
 * ## 확인하지 못한 것 (계획 §1.2 Q-1b)
 *
 * `maxFileCount` 가 `InputStream` 경로(`XWPFDocument(InputStream)`)에서도 강제되는지는
 * 공식 문서로 확정하지 못했다. 우리 1차 방어가 항목 수가 아니라 **바이트 예산**이라
 * 이 미확인이 남기는 위험은 "항목이 아주 많고 전체 크기는 작은 아카이브" 하나이며,
 * 그 입력은 예산을 넘지 않으므로 파싱 시간만 늘고 메모리는 늘지 않는다. 산출물에 남긴다.
 */
object PoiZipDefenses {
    /**
     * 항목 하나가 풀렸을 때 허용하는 최대 바이트. 전체 예산과 같은 값을 쓴다 —
     * 항목 하나가 전체 예산을 다 쓰는 것은 허용하되 그 이상은 아니다.
     */
    const val MAX_ENTRY_SIZE: Long = ZIP_UNCOMPRESSED_BUDGET_BYTES

    /** 압축비 하한. POI 기본값과 같지만 **명시**한다 — 기본값에 기대면 업그레이드가 조용히 바꾼다. */
    const val MIN_INFLATE_RATIO: Double = 0.01

    /** 아카이브 항목 수 상한. 역시 POI 기본값과 같은 값을 명시한다. */
    const val MAX_FILE_COUNT: Long = 1000

    /** 전역 설정을 적용한다. 멱등하다. */
    fun apply() {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE)
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO)
        ZipSecureFile.setMaxFileCount(MAX_FILE_COUNT)
    }
}

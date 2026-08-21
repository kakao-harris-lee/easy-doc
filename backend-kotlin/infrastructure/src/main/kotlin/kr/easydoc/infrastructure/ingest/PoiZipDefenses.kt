package kr.easydoc.infrastructure.ingest

import org.apache.poi.openxml4j.util.ZipSecureFile

/** POI 자신의 zip 방어값을 **우리 예산에 맞춰 낮춘다** (계획 §5 D-8). */
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

package kr.easydoc.api.admin

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 관리자 조회·변경 1건당 구조화 로그 한 줄 — **actor id·action·(있으면) target id뿐**
 * (본문·이메일 없음, 어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md` §2 결정 3).
 * 변경(크레딧 조정·세금계산서 처리·공지 작성·수정)과 자원 하나를 겨눈 조회
 * (워크스페이스 상세)는 [record]를, 특정 자원을 겨누지 않는 목록·집계 조회(워크스페이스
 * 목록·오류·사용량)는 target 없는 [record] 오버로드를 쓴다.
 */
object AdminActionLog {
    private val log = LoggerFactory.getLogger("admin_action")

    fun record(
        action: String,
        actorId: UUID,
        targetId: UUID,
    ) {
        log.info("action={} actor={} target={}", action, actorId, targetId)
    }

    /** 특정 자원을 겨누지 않는 조회(목록·집계) 전용 — target 없이 actor·action만 남긴다. */
    fun record(
        action: String,
        actorId: UUID,
    ) {
        log.info("action={} actor={}", action, actorId)
    }
}

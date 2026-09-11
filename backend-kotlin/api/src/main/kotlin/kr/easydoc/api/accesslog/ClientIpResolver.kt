package kr.easydoc.api.accesslog

import jakarta.servlet.http.HttpServletRequest

/**
 * 관리자 API 요청의 접속지 정보(IP) — 접속기록(계획
 * `docs/plans/2026-09-11-access-log-retention.md` §3.4)이 요구하는 값. 이 저장소의
 * 유일한 리버스 프록시는 우리가 직접 관리하는 `frontend/nginx.conf`이고, 그 설정이
 * `X-Real-IP`를 `$remote_addr`(TCP 접속 자체의 주소, 위조 불가)로 채워 붙인다 — 그래서
 * 클라이언트가 임의로 얹을 수 있는 `X-Forwarded-For`가 아니라 이 헤더를 신뢰한다. 헤더가
 * 없으면(프록시를 거치지 않은 직접 접속 — 로컬 개발·테스트) `request.remoteAddr`로
 * 내려간다.
 */
object ClientIpResolver {
    private const val REAL_IP_HEADER = "X-Real-IP"

    fun resolve(request: HttpServletRequest): String = request.getHeader(REAL_IP_HEADER) ?: request.remoteAddr
}

package kr.easydoc.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * 변환 worker 실행 진입점.
 *
 * `api` 를 의존하지 않는다(계획 §3.2). 웹 서버를 띄우지 않으므로
 * `spring.main.web-application-type=none` 이다(`application-worker.yml`).
 *
 * Phase 1에서는 기동과 스키마 적용만 확인한다. 다음이 Phase 5에서 붙는다.
 * - `conversion_jobs` lease 획득 루프 (`FOR UPDATE SKIP LOCKED`)
 * - 실패 분류와 재시도 정책 (`app/workers/tasks.py` 의 분류를 그대로)
 * - 04:00 KST 보존 만료 파기 (advisory lock, 500건 단위)
 */
@SpringBootApplication(scanBasePackages = ["kr.easydoc"])
@ConfigurationPropertiesScan("kr.easydoc")
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}

package kr.easydoc.worker

import kr.easydoc.worker.operation.OPERATION_PROFILES
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

internal const val WORKER_PROFILE = "worker"

/** 변환 worker 실행 진입점. */
@SpringBootApplication(scanBasePackages = ["kr.easydoc.worker", "kr.easydoc.infrastructure"])
@ConfigurationPropertiesScan(basePackages = ["kr.easydoc.infrastructure"])
@EnableScheduling
class WorkerApplication

fun main(args: Array<String>) {
    val context = runApplication<WorkerApplication>(*args)
    if (context.environment.activeProfiles.any { it in OPERATION_PROFILES }) {
        val exitCode = SpringApplication.exit(context)
        if (exitCode != 0) {
            exitProcess(exitCode)
        }
    }
}

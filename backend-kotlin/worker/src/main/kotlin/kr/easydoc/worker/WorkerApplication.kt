package kr.easydoc.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** 변환 worker 실행 진입점. */
@SpringBootApplication(scanBasePackages = ["kr.easydoc"])
@ConfigurationPropertiesScan("kr.easydoc")
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}

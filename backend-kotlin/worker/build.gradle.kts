// worker — 변환 worker, 보존 만료 scheduler.
//
// api 를 의존하지 않는다 (계획 §3.2). 리스 기반 작업 큐와 보존 파기 scheduler 를 기동한다.

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":application"))
    runtimeOnly(project(":infrastructure"))
    // 설정 바인딩이 Kotlin 주 생성자를 찾으려면 필요하다 (EasyDocProperties KDoc).
    runtimeOnly(libs.kotlin.reflect)

    implementation(libs.spring.boot.starter)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":infrastructure")))
    // 기동 테스트가 DataSource·WebApplicationContext 타입을 참조한다.
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.web)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("easy-doc-worker.jar")
}

// api — Spring MVC 컨트롤러, 인증 필터, 오류·응답 계약.
//
// worker 를 의존하지 않는다. 두 실행 진입점은 profile(`api`, `worker`, `migrate`)로만
// 갈리고 서로를 모른다 (계획 §3.2).

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":application"))
    // infrastructure 는 구현체 제공용이라 runtimeOnly 로 붙인다 — api 소스가
    // JDBC·암호화·LLM SDK 타입을 컴파일 시점에 볼 수 없게 막는다(계획 §3.2 의존 방향).
    runtimeOnly(project(":infrastructure"))

    implementation(libs.spring.boot.starter.web)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    // 기동 테스트에서 Flyway 이력을 직접 확인하고 기존 스키마 스냅샷을 만든다.
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(testFixtures(project(":infrastructure")))
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("easy-doc-api.jar")
}

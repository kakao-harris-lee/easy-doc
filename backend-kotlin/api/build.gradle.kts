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
    // 설정 바인딩이 Kotlin 주 생성자를 찾으려면 필요하다 (EasyDocProperties KDoc).
    runtimeOnly(libs.kotlin.reflect)

    implementation(libs.spring.boot.starter.web)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    // 탐지기가 주 생성자 파라미터를 읽는다. JVM 반사로는 `@JvmInline value class` 파라미터의
    // `componentN()` 이 맹글링돼 세어지지 않아 그 타입을 든 DTO 가 통째로 검사 밖에 남았다
    // (게이트 24 privacy-gate A-3′). 런타임에는 이미 있던 좌표를 컴파일 시점으로 올린 것뿐이다.
    testImplementation(libs.kotlin.reflect)
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

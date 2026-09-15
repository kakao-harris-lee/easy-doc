// HTTP API와 migrate 진입점. worker를 의존하지 않는다.

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":application"))
    // 어댑터 타입이 API 소스의 컴파일 클래스패스에 노출되지 않게 한다.
    runtimeOnly(project(":infrastructure"))
    // 설정 바인딩이 Kotlin 주 생성자를 찾으려면 필요하다 (EasyDocProperties KDoc).
    runtimeOnly(libs.kotlin.reflect)

    implementation(libs.spring.boot.starter.web)
    // 계약이 스키마 층 판정을 요구하는 **두 자리**(`GET /documents` 의 limit·offset)를 위해
    // 들인다. 함께 켜지는 것과 그것이 F3 에 무슨 뜻인지는 version catalog 의 주석에 있다.
    implementation(libs.spring.boot.starter.validation)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    // 테스트가 Kotlin 주 생성자와 value class 파라미터를 검사한다.
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

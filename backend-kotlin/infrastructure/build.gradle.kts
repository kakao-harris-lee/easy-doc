// infrastructure — JDBC repository, 암호화, 문서 파서, LLM provider 구현, 작업 큐.
//
// 벤더 LLM SDK는 **이 모듈에서만** 의존성으로 선언한다 (프로젝트 CLAUDE.md 아키텍처
// 규칙 1). 규칙을 문서로만 두지 않고 빌드로 강제하기 위해 `implementation` 으로 선언한다 —
// api/worker 의 컴파일 클래스패스에 SDK 타입이 올라오지 않는다.
// Phase 1에는 아직 SDK가 없다(Phase 5).

plugins {
    // @Configuration·@Component 클래스를 자동으로 open 으로 만든다.
    alias(libs.plugins.kotlin.spring)
    // Testcontainers 기동 코드를 api 테스트가 함께 쓴다.
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    api(project(":application"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesRuntimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
}

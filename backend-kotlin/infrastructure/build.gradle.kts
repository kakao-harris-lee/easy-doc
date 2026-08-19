// infrastructure — JDBC repository, 암호화, 문서 파서, LLM provider 구현, 작업 큐.
//
// 벤더 LLM 접근은 **이 모듈에서만** 의존성으로 선언한다 (프로젝트 CLAUDE.md 아키텍처
// 규칙 1). 규칙을 문서로만 두지 않고 빌드로 강제하기 위해 `implementation` 으로 선언한다 —
// api/worker 의 컴파일 클래스패스에 HTTP·JSON 타입이 올라오지 않는다.
//
// LLM 어댑터는 벤더 SDK 대신 Spring RestClient + Jackson 을 쓴다(사유는 version catalog).

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
    // LLM provider 어댑터의 HTTP·JSON. 서블릿 컨테이너를 끌고 오는 starter-web 이 아니라
    // spring-web 만 쓴다 — infrastructure 는 요청을 받는 쪽이 아니라 보내는 쪽이다.
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)
    // 인증 — Argon2id 해시(검증된 인코더 + PHC 인코딩)와 HS256 토큰.
    // 두 라이브러리 모두 이 모듈 밖으로 새지 않는다: api·worker 는 infrastructure 를
    // runtimeOnly 로만 의존하므로 컴파일 시점에 `com.nimbusds.*` 를 볼 수 없다.
    implementation(libs.spring.security.crypto)
    implementation(libs.nimbus.jose.jwt)
    // Argon2PasswordEncoder 가 런타임에 요구한다. 우리 코드는 BC 타입을 import 하지 않는다.
    runtimeOnly(libs.bouncycastle.bcprov)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    // 테스트 Spring 컨텍스트에 실제 암호화 키를 넣는 EnvironmentPostProcessor
    // (`TestEncryptionKeys`)가 `org.springframework.boot.EnvironmentPostProcessor` 를
    // 구현한다. testFixtures 산출물은 `testFixtures(project(...))` 로 명시적으로 당긴
    // 테스트 클래스패스에만 올라가므로 bootJar 의 runtimeClasspath 에는 실리지 않는다.
    testFixturesImplementation(libs.spring.boot.starter)
    testFixturesRuntimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
    // 설정 바인딩 회귀 테스트가 실행 환경과 같은 조건에서 돌아야 한다.
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
}

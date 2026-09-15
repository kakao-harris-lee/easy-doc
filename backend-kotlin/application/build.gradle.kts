// 유스케이스와 포트. infrastructure 구현에는 의존하지 않는다.

dependencies {
    api(project(":core"))

    // 로깅 구현은 실행 모듈이 선택한다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 순수 Kotlin 도메인 타입과 정책. Spring, DB, JSON, 벤더 SDK를 사용하지 않는다.

plugins {
    // 공통 테스트 대역을 application과 실행 모듈 테스트가 함께 쓴다.
    `java-test-fixtures`
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // BOM은 테스트 라이브러리 버전 정렬에만 사용하며 Spring jar를 추가하지 않는다.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // 골든 fixture의 JsonElement 타입을 소비 테스트에 노출한다.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.junit.jupiter)
}

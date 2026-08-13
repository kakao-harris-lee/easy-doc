// core — 마스킹, 스타일 규칙, 프롬프트, 후처리, 도메인 타입·예외.
//
// **Spring도 DB도 모른다** (계획 §3.2, kotlin-spring-conventions §1).
// 이 조건이 깨지면 Phase 2의 "외부 API·DB 없이 실행하는 parity suite" 종료 조건이
// 성립하지 않는다 — 불일치가 났을 때 도메인 로직 문제인지 배선 문제인지 가릴 수 없다.
// 조건은 문서가 아니라 CoreModuleBoundaryTest 가 지킨다.

plugins {
    // parity 하네스(ParityActualWriter)를 infrastructure·application 테스트가 함께 쓴다.
    // 별도 모듈을 만들지 않는 이유: 계획 §3.2가 정한 모듈은 다섯 개뿐이고,
    // 테스트 지원 코드는 제품 모듈이 아니다.
    `java-test-fixtures`
}

dependencies {
    // Spring Boot BOM 을 **버전 정렬 용도로만** 쓴다. platform() 은 제약(constraint)만
    // 더하고 jar 를 한 개도 추가하지 않으므로 "core 는 Spring 을 모른다"가 유지된다 —
    // CoreModuleBoundaryTest 가 실제 클래스패스로 그것을 확인한다. 이렇게 하지 않으면
    // JUnit·AssertJ 버전을 여기서 따로 정해야 하고, 그 순간 다른 모듈과 갈린다.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // testFixtures 에만 JSON 라이브러리를 준다. core 본 소스에는 들어가지 않는다.
    // `api` 로 노출하는 이유: fixture 를 읽고 산출물을 만드는 쪽(core·infrastructure 테스트)이
    // 같은 JsonElement 타입을 다뤄야 한다.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.junit.jupiter)
}

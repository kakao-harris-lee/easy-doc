// application — 인증, 문서, 작업 공간, 변환 유스케이스.
//
// infrastructure 를 의존하지 않는다. 필요한 것은 이 모듈이 선언한 포트 인터페이스이고,
// infrastructure 가 그것을 구현한다 (현재 Python이 services 에서 Protocol 로 저장소
// 계약을 선언하고 repositories 가 만족시키는 구조와 같다).
//
// Phase 1에서는 모듈 경계와 의존 방향만 세운다. 유스케이스 포팅은 Phase 3~5다.

dependencies {
    api(project(":core"))

    // 로깅 **파사드만** 쓴다. 유스케이스가 재해시 실패 같은 best-effort 실패를 남겨야
    // 하는데(migration-safety-gate I-8 검증 3), 구현체를 여기서 고르면 실행 모듈의
    // 로그 설정과 갈린다. Spring 은 여전히 의존하지 않는다 — 이 모듈이 지키는 조건은
    // "infrastructure 를 모른다"와 "Spring 컨텍스트 없이 테스트된다"이지 "의존성 0"이 아니다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// application — 인증, 문서, 작업 공간, 변환 유스케이스.
//
// infrastructure 를 의존하지 않는다. 필요한 것은 이 모듈이 선언한 포트 인터페이스이고,
// infrastructure 가 그것을 구현한다 (현재 Python이 services 에서 Protocol 로 저장소
// 계약을 선언하고 repositories 가 만족시키는 구조와 같다).
//
// Phase 1에서는 모듈 경계와 의존 방향만 세운다. 유스케이스 포팅은 Phase 3~5다.

dependencies {
    api(project(":core"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Kotlin 백엔드의 독립 Gradle 루트.

rootProject.name = "easy-doc-backend"

// 세 코드 계층과 두 실행 진입점. 자세한 책임은 README.md를 따른다.
include("core", "application", "infrastructure", "api", "worker")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 모듈 빌드 스크립트가 저장소를 각자 선언하지 못하게 막는다 — 저장소가 갈리면
    // 같은 좌표가 모듈마다 다른 아티팩트로 해석될 수 있다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Kotlin 런타임의 Gradle 루트. 저장소 루트가 아니라 backend-kotlin/ 이 루트다 —
// Python(uv)·React(npm) 빌드와 서로의 설정 파일을 밟지 않게 하기 위해서다.

rootProject.name = "easy-doc-backend"

// 계획 §3.2가 지정한 다섯 모듈. 이 목록에 없는 모듈을 늘리기 전에 §3.2와
// kotlin-spring-conventions §2의 매핑표에서 어느 칸에 들어가는지 먼저 정한다.
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

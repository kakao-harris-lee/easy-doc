import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// 다섯 모듈이 공유하는 설정. 버전은 여기 적지 않는다 — gradle/libs.versions.toml 한 곳이다.

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// parity 산출물의 저장소 루트 기준 경로. backend-kotlin/ 이 Gradle 루트이므로 한 단계 위다.
val parityActualDir: File = File(rootDir.parentFile, "parity/actual")

allprojects {
    group = "kr.easydoc"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // --- toolchain ----------------------------------------------------------
    // 로컬에 설치된 JDK가 무엇이든 Java 21로 컴파일·실행한다. 이것이 없으면
    // 개발자 기계마다 다른 바이트코드가 나오고 "내 컴퓨터에선 되는데"가 시작된다.
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            // 경고를 남긴 채 통과로 보고하지 않는다 (계획 §6 Build 게이트).
            allWarningsAsErrors.set(true)
        }
    }

    // --- dependency locking -------------------------------------------------
    // 전이 의존성이 조용히 올라가면 Fernet·JWT·문서 파서처럼 바이트 단위 호환이 걸린
    // 지점에서 재현 불가능한 차이가 난다. 락파일은 "어제는 통과했는데 오늘 실패"의
    // 원인 후보에서 의존성 드리프트를 제거한다.
    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    // --- 품질 게이트 --------------------------------------------------------
    extensions.configure<KtlintExtension> {
        version.set(
            rootProject.libs.versions.ktlintCli
                .get(),
        )
        // 규칙의 정본은 backend-kotlin/.editorconfig 다.
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    // --- 테스트 -------------------------------------------------------------
    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // 실제 LLM API를 부르는 테스트는 기본 실행에서 뺀다
            // (Python의 @pytest.mark.llm 에 대응). 비용 승인 후에만 돌린다.
            excludeTags("llm")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
        // Testcontainers 컨테이너 재사용 — 테스트 클래스마다 새 PostgreSQL을 띄우면
        // 전체 실행이 분 단위로 늘어난다.
        systemProperty("testcontainers.reuse.enable", "true")
    }

    // 일반 `test` 는 게이트 디렉터리를 건드리지 않는다. parity 산출물은 모듈 build/ 안에
    // 버려지고, 저장소 루트 parity/actual/ 은 아래 parityHarness 태스크만 쓴다.
    tasks.named<Test>("test") {
        systemProperty(
            "parity.actual.dir",
            layout.buildDirectory
                .dir("parity-actual")
                .get()
                .asFile.absolutePath,
        )
    }

    // parity 게이트용 산출 태스크. `parity/actual/{도메인}` 아래 json 산출물 을 **Kotlin 테스트가**
    // 쓴다는 것을 보장하는 유일한 장치다 (Phase 0 필수 조치 E,
    // python-kotlin-parity 스킬 "이 게이트가 막지 못하는 것" 참고).
    val sourceSets = extensions.getByType<SourceSetContainer>()
    tasks.register<Test>("parityHarness") {
        group = "verification"
        description = "parity 산출물을 저장소 루트 parity/actual/ 에 쓴다 (@Tag(\"parity\") 테스트만)"
        testClassesDirs = sourceSets.getByName("test").output.classesDirs
        classpath = sourceSets.getByName("test").runtimeClasspath
        useJUnitPlatform {
            includeTags("parity")
            excludeTags("llm")
        }
        systemProperty("parity.actual.dir", parityActualDir.absolutePath)
        // 입력이 같아도 매번 다시 써야 게이트가 최신 산출물을 본다.
        outputs.upToDateWhen { false }
    }
}

// 루트에서 한 번에 도는 편의 태스크.
tasks.register("parityHarness") {
    group = "verification"
    description = "모든 모듈의 parity 산출물을 저장소 루트 parity/actual/ 에 쓴다"
    dependsOn(subprojects.map { "${it.path}:parityHarness" })
}

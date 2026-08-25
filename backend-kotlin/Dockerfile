# Kotlin 백엔드 프로젝트 이미지. API·워커·마이그레이션이 **하나의 이미지**를 공유하고
# Spring profile 로만 갈린다.
#
# 빌드 컨텍스트는 backend-kotlin/ 이다:
#   docker build -f backend-kotlin/Dockerfile backend-kotlin

# --- 빌드 단계 ---------------------------------------------------------------
# 태그를 고정한다 — 이미지가 조용히 다른 JDK로 바뀌면 재현 가능한 빌드가 아니다.
# Java 21은 build.gradle.kts 의 jvmToolchain(21)과 같은 값이다.
FROM eclipse-temurin:21.0.4_7-jdk-noble AS build

WORKDIR /build

# 의존성 해석 레이어를 먼저 굳힌다. 소스만 바뀐 빌드는 이 층을 캐시에서 재사용한다.
# 락파일(gradle.lockfile)을 함께 복사해야 컨테이너 빌드도 호스트와 같은 의존성을 본다.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
COPY .editorconfig ./
COPY core/build.gradle.kts core/gradle.lockfile core/
COPY application/build.gradle.kts application/gradle.lockfile application/
COPY infrastructure/build.gradle.kts infrastructure/gradle.lockfile infrastructure/
COPY api/build.gradle.kts api/gradle.lockfile api/
COPY worker/build.gradle.kts worker/gradle.lockfile worker/
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY core core
COPY application application
COPY infrastructure infrastructure
COPY api api
COPY worker worker

# 이미지 빌드에서는 테스트를 돌리지 않는다 — Testcontainers 가 Docker 데몬을 요구하므로
# 이미지 빌드 안에서 돌리려면 docker-in-docker 가 된다. 테스트는 CI가 책임진다.
RUN ./gradlew --no-daemon :api:bootJar :worker:bootJar -x test

# --- 런타임 단계 -------------------------------------------------------------
FROM eclipse-temurin:21.0.4_7-jre-noble AS runtime

# compose healthcheck 와 운영 진단에 쓸 curl. JRE 이미지에는 들어 있지 않다.
# --no-install-recommends: 이미지에 불필요한 패키지를 늘리지 않는다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# non-root 실행. 애플리케이션이 자기 코드를 고칠 수 없어야 한다.
RUN useradd --system --create-home --uid 10001 easydoc

WORKDIR /app

COPY --from=build /build/api/build/libs/easy-doc-api.jar /app/easy-doc-api.jar
COPY --from=build /build/worker/build/libs/easy-doc-worker.jar /app/easy-doc-worker.jar

USER easydoc

ENV SERVER_PORT=8100 \
    # 구조화 로그. Spring Boot 가 콘솔 출력을 ECS JSON 한 줄로 만든다 —
    # 로그 수집기가 필드로 파싱할 수 있어야 문서 ID·상태·failure code 로 집계된다.
    # 본문·개인정보는 애초에 로그에 넣지 않는다(프로젝트 CLAUDE.md 보안 규칙).
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    # 컨테이너 메모리 한도를 JVM 힙에 반영한다. 기본값(1/4)은 작은 컨테이너에서 낭비가 크다.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8100

# 기본은 API. worker 는 compose 에서 command 로 덮어쓴다.
CMD ["java", "-jar", "/app/easy-doc-api.jar", "--spring.profiles.active=api"]

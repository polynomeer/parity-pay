# pay-api와 기관 두 개의 공용 이미지입니다. APP으로 어느 쪽인지 고릅니다. 근거: ADR-011
#
# **저장소 안에서 빌드합니다.** 미리 만들어 둔 jar를 COPY하면 "먼저 ./gradlew bootJar"라는
# 기억해야 할 단계가 생기고, 그것은 실제로 한 번 새어 나간 적이 있습니다 — run-e2e.sh가 기관
# jar를 빌드하지 않았는데 로컬에 남아 있던 파일 덕분에 오래 드러나지 않았습니다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 래퍼와 빌드 스크립트를 먼저 넣습니다. 소스만 바뀌면 이 층은 캐시에 남습니다.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY modules modules
COPY apps/pay-api apps/pay-api
COPY apps/mock-bank apps/mock-bank
COPY apps/mock-pg apps/mock-pg

ARG APP
RUN test -n "$APP" || (echo "APP 빌드 인자가 필요합니다 (pay-api | mock-bank | mock-pg)" && exit 1)
# 데몬은 한 번 쓰고 버리는 컨테이너에서 이득이 없습니다.
RUN ./gradlew --no-daemon ":apps:${APP}:bootJar" \
    && cp "apps/${APP}/build/libs/${APP}"-*.jar /app.jar

FROM eclipse-temurin:21-jre AS runtime

# root로 돌 이유가 없습니다.
RUN useradd --system --uid 10001 paritypay
USER paritypay

WORKDIR /app
COPY --from=build /app.jar app.jar

# 프로필을 지정하지 않습니다. `local` 프로필에만 있는 개발용 비밀값과 느슨한 쿠키 설정에
# 배포가 닿을 수 없어야 합니다. 근거: ADR-011
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

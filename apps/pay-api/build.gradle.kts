plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:wallet"))
    implementation(project(":modules:payment"))
    implementation(project(":modules:settlement"))
    implementation(project(":modules:reconciliation"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    // 이벤트 계약 검사용 JSON Schema 검증기입니다. 근거: docs/08-db-api-event-spec.md §7
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.awaitility:awaitility")
    testImplementation(testFixtures(project(":modules:ledger")))
    // 통합 테스트는 기관을 진짜로 띄웁니다. 같은 JVM의 별도 Spring 컨텍스트로 뜨고 HTTP로
    // 호출되므로, 타임아웃과 연결 오류가 실제로 재현됩니다.
    testImplementation(project(":apps:mock-bank"))
    testImplementation(project(":apps:mock-pg"))
}

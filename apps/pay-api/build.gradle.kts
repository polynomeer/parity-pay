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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    // 이벤트 계약 검사용 JSON Schema 검증기입니다. 근거: docs/08-db-api-event-spec.md §7
    implementation("com.networknt:json-schema-validator:3.0.7")
    // Spring Boot 4는 Flyway 자동설정도 별도 모듈입니다. 이것이 없으면 flyway-core가 클래스패스에
    // 있어도 마이그레이션이 돌지 않고, Hibernate 검증이 "테이블 없음"으로 실패합니다.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4에서 TestRestTemplate이 spring-boot-test에서 떨어져 나왔습니다.
    // 3.5에서는 starter-test가 끌어왔으므로 선언할 필요가 없었습니다.
    // Spring Boot 4는 Kafka 자동설정을 별도 모듈로 떼어냈습니다. 3.5에서는 spring-boot-autoconfigure에
    // 들어 있어 spring-kafka만 있으면 됐지만, 4에서는 이것이 없으면 KafkaTemplate 자동설정도
    // Testcontainers 서비스 연결도 만들어지지 않습니다.
    // Spring Boot 4는 RestClient/RestTemplate 지원도 별도 모듈입니다. MockBankClient·MockPgClient가
    // RestClient를 쓰고, 시험의 TestRestTemplate도 이 모듈의 RestTemplateBuilder를 필요로 합니다.
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation("org.springframework.boot:spring-boot-kafka")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
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

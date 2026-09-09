plugins {
    id("org.springframework.boot")
}

// 카드 PG 대역입니다. 은행과 다른 회사이므로 코드도 공유하지 않습니다.
// 외부기관 대역입니다. 우리 도메인 모듈에 의존하지 않습니다. 의존하면 "외부"라는 전제가 무너지고,
// 우리 타입이 바뀔 때 기관이 함께 바뀌는 일이 생깁니다. 주고받는 것은 JSON뿐입니다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 기관은 자기 스키마를 자기 Flyway로 만듭니다. Spring Boot 4에서는 자동설정이 별도 모듈이라
    // flyway-core만으로는 돌지 않습니다.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// wallet은 ledger의 포트만 사용합니다. ledger 어댑터·테이블에 직접 접근하지 않습니다.
// 근거: docs/05-technical-design.md §5
dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")

    // 소비자가 이벤트 봉투를 JsonNode로 다룹니다. 지금까지는 다른 스타터가 끌어와 컴파일됐지만,
    // 쓰는 것은 선언해야 합니다 — Spring Boot 판올림에서 전이 의존이 빠지자 이 모듈이 컴파일되지
    // 않았습니다(Dependabot PR에서 드러났습니다).
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":modules:ledger")))
}

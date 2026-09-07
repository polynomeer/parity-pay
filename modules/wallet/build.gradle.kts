// wallet은 ledger의 포트만 사용합니다. ledger 어댑터·테이블에 직접 접근하지 않습니다.
// 근거: docs/05-technical-design.md §5
dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")

    testImplementation(testFixtures(project(":modules:ledger")))
}

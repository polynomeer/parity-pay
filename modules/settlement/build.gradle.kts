// settlement는 payment의 읽기 포트와 ledger 포트만 사용합니다.
// 결제 테이블을 직접 읽지 않고 이벤트와 포트로만 연결됩니다. 근거: docs/05-technical-design.md §5
dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")
}

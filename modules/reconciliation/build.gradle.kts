// reconciliation은 다른 모듈의 테이블을 직접 읽지 않습니다.
// 내부·외부 기록은 포트로 받고, 그 구현은 조립 지점(pay-api)이 제공합니다.
// 근거: docs/05-technical-design.md §5
dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}

// payment는 wallet과 ledger의 포트만 사용합니다. 두 모듈의 테이블·어댑터에 직접 접근하지 않습니다.
// 근거: docs/05-technical-design.md §5
dependencies {
    api(project(":modules:shared-kernel"))
    implementation(project(":modules:ledger"))
    implementation(project(":modules:wallet"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}

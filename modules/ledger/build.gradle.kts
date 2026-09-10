// ledger 모듈은 다른 업무 모듈을 참조하지 않습니다. referenceType + referenceId만 저장합니다.
// 근거: docs/05-technical-design.md §5, docs/07-ledger-journal-catalog.md §4
dependencies {
    api(project(":modules:shared-kernel"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 원장 조회 API를 위한 웹 어댑터입니다. 쓰기 경로는 없습니다 — 확정 원장은 수정하지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-web")
}

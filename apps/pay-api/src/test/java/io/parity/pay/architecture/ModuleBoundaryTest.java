package io.parity.pay.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 모듈 경계와 계층 규칙을 코드로 강제합니다.
 *
 * <p>모듈 경계는 문서만으로 유지되지 않습니다. 근거: docs/05-technical-design.md §5·§6, ADR-001
 */
// 모듈은 pay-api에 JAR로 들어오므로 JAR를 제외하면 검사 대상이 비어버립니다.
// packages 필터가 이미 io.parity.pay로 범위를 좁힙니다.
@AnalyzeClasses(packages = "io.parity.pay", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /** 도메인은 프레임워크에 의존하지 않습니다. 근거: docs/05-technical-design.md §6 */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "com.fasterxml.jackson..")
            .as("도메인 계층은 Spring·JPA·Jackson에 의존하지 않아야 합니다");

    /** 도메인은 바깥 계층을 모릅니다. */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_outer_layers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter..")
            .as("도메인 계층은 애플리케이션·어댑터 계층에 의존하지 않아야 합니다");

    /** 애플리케이션은 어댑터 구현을 모릅니다. 포트만 사용합니다. */
    @ArchTest
    static final ArchRule application_must_not_depend_on_adapters = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .as("애플리케이션 계층은 어댑터 구현에 의존하지 않아야 합니다");

    /**
     * ledger는 업무 모듈을 참조하지 않고 referenceType + referenceId만 저장합니다.
     * 근거: docs/05-technical-design.md §5
     */
    @ArchTest
    static final ArchRule ledger_must_not_depend_on_business_modules = noClasses()
            .that()
            .resideInAPackage("io.parity.pay.ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.parity.pay.wallet..",
                    "io.parity.pay.payment..",
                    "io.parity.pay.settlement..",
                    "io.parity.pay.reconciliation..",
                    "io.parity.pay.risk..",
                    "io.parity.pay.operations..")
            .as("ledger 모듈은 다른 업무 모듈에 의존하지 않아야 합니다");

    /** shared-kernel은 어떤 업무 모듈도 알지 못합니다. */
    @ArchTest
    static final ArchRule shared_kernel_must_not_depend_on_modules = noClasses()
            .that()
            .resideInAPackage("io.parity.pay.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.parity.pay.ledger..",
                    "io.parity.pay.wallet..",
                    "io.parity.pay.payment..",
                    "io.parity.pay.api..")
            .as("shared-kernel은 업무 모듈에 의존하지 않아야 합니다");

    /** 업무 모듈은 다른 모듈의 어댑터(=구현 세부)에 접근하지 않습니다. */
    @ArchTest
    static final ArchRule ledger_adapters_are_internal = noClasses()
            .that()
            .resideOutsideOfPackage("io.parity.pay.ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.parity.pay.ledger.adapter..")
            .as("다른 모듈은 ledger 어댑터 내부에 접근하지 않아야 합니다");

    @ArchTest
    static final ArchRule wallet_adapters_are_internal = noClasses()
            .that()
            .resideOutsideOfPackage("io.parity.pay.wallet..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.parity.pay.wallet.adapter..")
            .as("다른 모듈은 wallet 어댑터 내부에 접근하지 않아야 합니다");

    @ArchTest
    static final ArchRule payment_adapters_are_internal = noClasses()
            .that()
            .resideOutsideOfPackage("io.parity.pay.payment..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.parity.pay.payment.adapter..")
            .as("다른 모듈은 payment 어댑터 내부에 접근하지 않아야 합니다");

    @ArchTest
    static final ArchRule settlement_adapters_are_internal = noClasses()
            .that()
            .resideOutsideOfPackage("io.parity.pay.settlement..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.parity.pay.settlement.adapter..")
            .as("다른 모듈은 settlement 어댑터 내부에 접근하지 않아야 합니다");

    /**
     * 대사는 다른 업무 모듈을 모릅니다. 내부·외부 기록은 포트로만 받습니다.
     * 근거: docs/05-technical-design.md §5
     */
    @ArchTest
    static final ArchRule reconciliation_must_not_depend_on_business_modules = noClasses()
            .that()
            .resideInAPackage("io.parity.pay.reconciliation..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.parity.pay.wallet..",
                    "io.parity.pay.payment..",
                    "io.parity.pay.settlement..")
            .as("reconciliation 모듈은 업무 모듈에 직접 의존하지 않아야 합니다");

    /**
     * wallet은 payment를 모릅니다. 결제가 지갑 포트를 호출하는 단방향 의존입니다.
     * 근거: docs/05-technical-design.md §5
     */
    @ArchTest
    static final ArchRule wallet_must_not_depend_on_payment = noClasses()
            .that()
            .resideInAPackage("io.parity.pay.wallet..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.parity.pay.payment..")
            .as("wallet 모듈은 payment 모듈에 의존하지 않아야 합니다");

    /** 금액에 부동소수점을 사용하지 않습니다. 근거: BR-001, CLAUDE.md §3 */
    @ArchTest
    static final ArchRule no_floating_point_fields = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("io.parity.pay..")
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .as("금액을 포함한 어떤 필드도 부동소수점 타입을 사용하지 않아야 합니다");
}

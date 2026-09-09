import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("com.diffplug.spotless") version "7.0.2"
    id("net.ltgt.errorprone") version "5.1.1" apply false
}

val springBootVersion = "4.1.1"
val archUnitVersion = "1.5.0"
val jqwikVersion = "1.10.1"
val errorProneVersion = "2.50.0"

// Spring Boot 4의 BOM은 Testcontainers 버전을 더 이상 관리하지 않습니다. 3.5에서는 관리해 주어
// 버전을 적지 않았고, 판올림하자 버전이 빈 문자열로 해석되어 해석 자체가 실패했습니다.
// 우리가 쓰는 것은 우리가 버전을 정합니다.
val testcontainersVersion = "1.21.3"

allprojects {
    group = "io.parity"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        add("testImplementation", platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
        add("testFixturesImplementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testImplementation", "com.tngtech.archunit:archunit-junit5:$archUnitVersion")
        add("testImplementation", "net.jqwik:jqwik:$jqwikVersion")
        add("errorprone", "com.google.errorprone:error_prone_core:$errorProneVersion")
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            // google-java-format이 아니라 palantir를 쓰는 이유: 이 저장소의 주석은 한국어
            // 산문인데 google-java-format은 Javadoc 문단을 한 줄로 이어 붙입니다(한글 폭을
            // 1로 세므로 140자짜리 줄이 됩니다). palantir는 Javadoc을 건드리지 않고, 기존
            // 코드가 이미 이 도구의 결정과 거의 같아 도입 변경 폭도 작았습니다.
            palantirJavaFormat("2.50.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
        options.errorprone {
            // 경고로 두면 빌드 로그에만 쌓이고 아무도 고치지 않습니다. 현재 코드가 통과하는
            // 검사만 오류로 올려서, 새로 들어오는 위반이 CI에서 막히게 합니다.
            error(
                // 쓰지 않는 필드·메서드·매개변수. 지우다 만 코드가 남는 것을 막습니다.
                "UnusedVariable",
                "UnusedMethod",
                // 정규식으로 동작하고 뒤쪽 빈 칸을 조용히 버리는 String.split(String).
                "StringSplitter",
                // 기본 로케일에 따라 결과가 달라지는 대소문자 변환.
                "StringCaseLocaleUsage",
                // int로 계산한 뒤 long에 담는 코드. 금액을 다루는 곳에서 조용히 넘칩니다.
                "IntLongMath",
                // 재정의인데 @Override가 없는 메서드.
                "MissingOverride",
                // 반환값을 버리면 안 되는 호출.
                "ReturnValueIgnored",
                // 공개 API의 Javadoc에 요약문이 없는 경우. 이 저장소는 "왜"를 주석으로 남깁니다.
                "MissingSummary")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik", "archunit")
            // 벤치마크는 오래 걸리고 실행 환경에 민감합니다. 기본 실행에서 빼고 필요할 때만 켭니다.
            if (!project.hasProperty("includeBenchmarks")) {
                excludeTags("benchmark")
            }
        }
        // 스냅샷 갱신 플래그를 테스트 JVM으로 넘깁니다.
        if (project.hasProperty("updateOpenApiSnapshot")) {
            systemProperty("updateOpenApiSnapshot", "true")
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.16" apply false
}

val springBootVersion = "3.5.16"
val archUnitVersion = "1.4.1"
val jqwikVersion = "1.9.2"

allprojects {
    group = "io.parity"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
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
        add("testFixturesImplementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testImplementation", "com.tngtech.archunit:archunit-junit5:$archUnitVersion")
        add("testImplementation", "net.jqwik:jqwik:$jqwikVersion")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik", "archunit")
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

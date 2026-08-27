plugins {
    id("org.springframework.boot") version "3.4.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("java") apply false
}

allprojects { group = "com.kaas"; version = "0.1.0-SNAPSHOT" }

subprojects {
    apply(plugin = "java")
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
    tasks.withType<Test> { useJUnitPlatform() }
}

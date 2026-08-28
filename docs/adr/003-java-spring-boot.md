# ADR-003: Java 25, Spring Boot 4.1, and Gradle 9.7

## Status

IMPLEMENTED

## Context

The backend and runner need a supported JVM baseline and a reproducible build that works without a globally installed Gradle. The project explicitly standardizes on Java 25. Version selection should reduce near-term obsolescence while avoiding preview build tools.

## Decision

Use Java 25 as the compilation, test, and runtime toolchain. Use Spring Boot 4.1.1 for the API and Gradle 9.7.1 through the committed wrapper for all JVM modules. Gradle 9.7.1 is the newest stable patch release selected on 2026-08-27; Gradle 9.8 milestone builds are excluded.

## Alternatives considered

- Java 21 with Spring Boot 3.x and Gradle 8.x.
- Java 25 with an older Gradle release.
- Gradle 9.8 milestone/nightly builds.
- Maven instead of Gradle.

## Why alternatives were rejected

Java 21 contradicts the selected project baseline. Gradle versions before 9.1 cannot run on Java 25; using the current stable patch provides tested Java 25 support and avoids carrying known fixed build-tool defects. Milestones/nightlies trade stability for features the project does not need. Maven would add migration work without a demonstrated benefit for this Kotlin DSL monorepo.

## Advantages

- Current stable JVM and build-tool support.
- Spring Boot 4.1 explicitly supports Java 25 and Gradle 9.x.
- The wrapper pins the build tool and verifies the distribution checksum.

## Disadvantages

- Contributors and CI require a Java 25 JDK.
- Spring Boot 4 uses Jakarta Servlet 6.1/Tomcat 11 and has a smaller compatibility window for legacy libraries than Spring Boot 3.
- Staying current requires deliberate upgrade verification rather than automatic major-version updates.

## Consequences

`./gradlew clean check` is the authoritative JVM verification command. CI runs Temurin 25 and the wrapper; no globally installed Gradle is assumed. New dependencies must be compatible with Java 25, Spring Boot 4.1, and Gradle 9.7.

## Validation and revisit conditions

Validated by a clean wrapper build executing API and runner tests on Java 25. Revisit on upstream end-of-support, a security advisory, a required dependency incompatibility, or when a newer stable Gradle patch provides material fixes. Preview releases are never selected solely because they have a higher version number.

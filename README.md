# Playwright Testing - Java API (Using Kotlin code)

This repository is a Kotlin-based Playwright testing workspace with support for browser tests, API tests, opt-in
external integrations, and a Turso-backed JUnit 5 statistics pipeline.

It mirrors the functionality of the [Java project](https://github.com/oscarvarto/playwright-practice) but leverages
idiomatic Kotlin:

- **Arrow** instead of functionalJava
- **Kotlin's type system** (type aliases, `@JvmInline value class`) instead of Checker Framework
- **Data classes and idiomatic Kotlin features** instead of Lombok
- **Kotest + JUnit 5 integration** for test specifications
- **Java interop** for libraries without mature Kotlin equivalents (Playwright, Testcontainers, Flyway)

The top-level README is intentionally short. Detailed design notes and operational guides live in focused documents
linked below.

## Documentation

| Topic                                                                            | Document                                                       |
| -------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| API testing architecture, `@UsePlaywright`, GitHub integration, and secrets flow | [docs/api-testing.md](docs/api-testing.md)                     |
| Turso test statistics schema, lifecycle, SQL views, and dashboard contract       | [docs/turso-test-statistics.md](docs/turso-test-statistics.md) |
| AWS / LocalStack testing setup and mode selection                                | [AWS_TESTING_GUIDE.md](AWS_TESTING_GUIDE.md)                   |
| Streamlit dashboard for test statistics (Python / pixi)                          | [python-analysis/README.md](python-analysis/README.md)         |

## Quick Start

**NOTE**: Follow <https://github.com/tursodatabase/turso/blob/main/bindings/java/README.md> to deploy the Turso JDBC
driver to your local maven repo first.

Default build:

```zsh
./gradlew build
```

GitHub API integration tests are opt-in:

```zsh
./gradlew test \
  -Dplaywright.github.integration.enabled=true \
  --tests "*.TestGitHubAPI"
```

AWS / LocalStack-backed tests are also opt-in:

```zsh
./gradlew test \
  -Dplaywright.aws.testing.enabled=true \
  --tests "*AwsTestingTest"
```

The Turso statistics pipeline is enabled by default for JUnit 5 test runs and stores data in
`src/test/resources/test-statistics.db`. For schema and analytics details, see
[docs/turso-test-statistics.md](docs/turso-test-statistics.md).

## Why Playwright + Kotlin?

Most Playwright adoption defaults to Node.js with TypeScript. That works, but TypeScript's type system is structural and
opt-out: `any`, type assertions, and unchecked index access are one keystroke away, and the compiler cannot prevent them
at the project level. In practice, large test suites accumulate these escape hatches quietly.

Kotlin's type system is nominal and closed by default. There is no `any`. Casts are explicit and checked at runtime.
Generics are reified when needed and enforced at compile time. Null safety is built into the type system — `String` vs
`String?` — eliminating an entire class of `NullPointerException` bugs at compile time.

Beyond the type system:

- **Kotest** provides expressive test styles (FunSpec, StringSpec, BehaviorSpec) with coroutine support, while still
  integrating seamlessly with JUnit 5 extensions for lifecycle hooks, parameter resolution, and conditional execution.
- **Arrow** brings typed functional programming (Either, Raise, Resource) as a natural fit for Kotlin's coroutines and
  context receivers, replacing the heavier Functional Java / Vavr approach.
- **Gradle Kotlin DSL** provides reproducible, cacheable builds with full IDE support and type-safe dependency
  declarations.
- **One toolchain for teams already on the JVM.** If the application under test is Kotlin or Java, the test suite shares
  the same language, IDE, debugger, and dependency graph. Kotlin's seamless Java interop means Playwright's Java API,
  Testcontainers, and other Java libraries work without wrappers.
- **Playwright's Java API is a first-class binding**, not a community wrapper. It is maintained by Microsoft, ships with
  the same browser versions, and supports the same features (auto-waits, trace viewer, codegen) as the Node.js API.

## Why Turso / SQLite for test statistics?

The test statistics pipeline stores execution data in a local Turso database file (`test-statistics.db`). A natural
question is whether a "real" database like PostgreSQL would be a better fit. For this use case, it would be overkill.

**The data volume is inherently bounded.** Test statistics grow as *(number of test cases) x (number of runs)*. Even a
large suite running daily in CI produces tens of thousands of rows over months — trivial for SQLite.

**Zero infrastructure is the killer feature.** The current pipeline is:

1. Run `./gradlew test` — data appears in a file.
2. Run `pixi run app` — the Streamlit dashboard reads that file.

No server process, no Docker container, no credentials, no network configuration. PostgreSQL would replace that with:
install and run Postgres, create a database, manage credentials, configure JDBC connection strings on the Kotlin side
and connection parameters on the Python side, provision an instance in CI, and handle migrations through a server-aware
tool. Every developer and every CI runner would need a running PostgreSQL instance. That is a lot of operational
friction for a testing support tool.

**If a shared, always-on dashboard becomes necessary** — for example, a team-wide view aggregating CI runs from all
branches — Turso offers a cloud-hosted mode with HTTP access that bridges that gap without switching database engines.
PostgreSQL remains an option at that point, but the migration cost only makes sense once the single-file model is
genuinely outgrown.

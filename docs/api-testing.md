# API Testing

Playwright can test REST APIs without launching a browser, using `APIRequestContext`. The `oscarvarto.mx.api` package
demonstrates this with a GitHub Issues API test suite.

Reference: <https://playwright.dev/java/docs/api-testing>

## Architecture

```mermaid
classDiagram
    direction TB

    class OptionsFactory {
        <<interface>>
        +getOptions() Options
    }

    class Options {
        +apiRequestOptions
        +contextOptions
        +launchOptions
        +baseUrl
        +setApiRequestOptions() Options
    }

    class PlaywrightApiTest {
        <<abstract>>
        #createAuthenticatedBrowserContext(apiContext, browser) BrowserContext
    }

    class TestGitHubAPI {
        -REPO
        -USER
        -API_TOKEN
        +beforeAll(request)
        +afterAll(request)
        +shouldCreateBugReport(request)
        +shouldCreateFeatureRequest(request)
        +lastCreatedIssueShouldBeFirstInTheList(request, page)
    }

    class SecretsProvider {
        <<object>>
        +get(key) String?
        +require(key) String
        -fetchSecret(secretName) String
        -extractField(json, field) String?
        -getClient() SecretsManagerClient
    }

    class GitHubApiOptions {
        +getOptions() Options
    }

    class UsePlaywright {
        <<annotation>>
        value
    }

    PlaywrightApiTest <|-- TestGitHubAPI : extends
    OptionsFactory <|.. GitHubApiOptions : implements
    TestGitHubAPI *-- GitHubApiOptions : inner class
    GitHubApiOptions ..> Options : creates
    UsePlaywright ..> OptionsFactory : references
    TestGitHubAPI <.. UsePlaywright : annotates
    TestGitHubAPI ..> SecretsProvider : resolves secrets
```

The design separates concerns across four components:

- **`PlaywrightApiTest`** (abstract) — provides `createAuthenticatedBrowserContext()` for transferring API
  authentication state into a browser context. Annotated with `@TestInstance(PER_CLASS)` so `@BeforeAll` / `@AfterAll`
  can be instance methods with parameter injection.
- **`TestGitHubAPI`** — concrete test class. Uses `@UsePlaywright(GitHubApiOptions::class)` to wire GitHub-specific
  configuration, and extends `PlaywrightApiTest` for the auth-reuse utility.
- **`GitHubApiOptions`** — inner `OptionsFactory` that configures the `APIRequestContext` with GitHub's base URL and
  auth headers.
- **`SecretsProvider`** — Kotlin `object` that resolves test secrets such as `GITHUB_USER` and `GITHUB_API_TOKEN` via an
  env-var-first strategy with AWS Secrets Manager fallback. See [Secrets management](#secrets-management).

## Opt-in execution

`TestGitHubAPI` is a real external integration test, not a default unit-test suite.

It is skipped unless you explicitly enable it with:

```zsh
-Dplaywright.github.integration.enabled=true
```

This keeps `./gradlew build` safe by default on machines that do not have GitHub credentials configured.

### Browser configuration file

The `playwright.json` file lives on the test classpath (typically `src/test/resources/playwright.json`):

```json
{ "playwright": { "browser": "chromium", "headless": true } }
```

## How `@UsePlaywright` works

The `@UsePlaywright` annotation is a meta-annotation that registers six JUnit 5 extensions, each responsible for one
Playwright object's lifecycle and parameter injection:

| Extension                    | Creates             | Scope    |
| ---------------------------- | ------------------- | -------- |
| `OptionsExtension`           | `Options`           | class    |
| `PlaywrightExtension`        | `Playwright`        | class    |
| `BrowserExtension`           | `Browser`           | class    |
| `BrowserContextExtension`    | `BrowserContext`    | per-test |
| `PageExtension`              | `Page`              | per-test |
| `APIRequestContextExtension` | `APIRequestContext` | per-test |

The `OptionsFactory` (here `GitHubApiOptions`) feeds configuration to these extensions. Each extension implements
`ParameterResolver`, so test methods and `@BeforeAll` / `@AfterAll` receive Playwright objects as method parameters
rather than managing them manually.

```mermaid
sequenceDiagram
    participant JUnit as JUnit 5
    participant Opts as OptionsExtension
    participant PwExt as PlaywrightExtension
    participant BrExt as BrowserExtension
    participant ApiExt as APIRequestContextExtension
    participant BcExt as BrowserContextExtension
    participant PgExt as PageExtension
    participant Test as TestGitHubAPI

    Note over JUnit,Test: Class-level setup (once)
    JUnit->>Opts: Load OptionsFactory
    Opts->>Opts: GitHubApiOptions.getOptions()
    Note right of Opts: Headers, base URL

    JUnit->>PwExt: Create Playwright instance
    JUnit->>BrExt: Create Browser instance

    JUnit->>Test: @BeforeAll beforeAll(request)
    ApiExt->>ApiExt: Resolve APIRequestContext
    Note right of ApiExt: Created lazily with Options
    ApiExt-->>Test: Inject APIRequestContext
    Test->>Test: POST /user/repos (create repo)

    Note over JUnit,Test: Per-test setup (each test)
    rect rgb(240, 248, 255)
        JUnit->>BcExt: beforeEach()
        BcExt->>BcExt: Create BrowserContext

        JUnit->>Test: @Test method(request, page)
        ApiExt-->>Test: Inject APIRequestContext
        PgExt-->>Test: Inject Page
        Test->>Test: Run test logic
    end

    Note over JUnit,Test: Class-level teardown (once)
    JUnit->>Test: @AfterAll afterAll(request)
    ApiExt-->>Test: Inject APIRequestContext
    Test->>Test: DELETE /repos/user/repo

    JUnit->>ApiExt: afterAll cleanup
    JUnit->>BrExt: Close Browser
    JUnit->>PwExt: Close Playwright
```

Key takeaway: you never manually create or close `Playwright`, `Browser`, or `APIRequestContext`. The extensions handle
that entirely. Your test methods simply declare what they need as parameters.

## Authentication state reuse

`PlaywrightApiTest` provides `createAuthenticatedBrowserContext()` which bridges the API and browser layers. This is
useful for web apps that use cookie-based authentication: authenticate once via fast API calls, then transfer that
session to a browser context for UI assertions.

```mermaid
flowchart TD
    subgraph API["API Layer"]
        A[APIRequestContext] -->|POST /login| B[Authenticated session]
        B -->|storageState| C["Storage state<br/>(cookies, localStorage)"]
    end

    subgraph Bridge["State Transfer"]
        C -->|"createAuthenticatedBrowserContext()"| D[Storage state string]
    end

    subgraph UI["Browser Layer"]
        D -->|"setStorageState()"| E[BrowserContext]
        E --> F[Page]
        F -->|"Already logged in"| G["Navigate & assert"]
    end

    style API fill:#e8f4fd,stroke:#1a73e8
    style Bridge fill:#fff3e0,stroke:#e65100
    style UI fill:#e8f5e9,stroke:#2e7d32
```

The pattern in code:

```kotlin
@Test
fun verifyDashboardAfterApiLogin(api: APIRequestContext, browser: Browser) {
    // 1. Authenticate via API (fast, no browser needed)
    api.post("/login", RequestOptions.create().setData(credentials))

    // 2. Transfer auth state to a browser context
    createAuthenticatedBrowserContext(api, browser).use { ctx ->
        val page = ctx.newPage()
        page.navigate("https://example.com/dashboard")

        // 3. Page is already logged in — assert directly
        assertThat(page.locator("h1")).hasText("Welcome")
    }
}
```

> **Note:** this pattern works for services that store authentication as cookies. Token-based APIs such as GitHub's
> `Authorization` header do not set cookies, so the `TestGitHubAPI` tests use the injected `APIRequestContext` directly
> for API calls, and the injected `Page` for browser navigation on public pages.

## Secrets management

Test secrets such as `GITHUB_USER` and `GITHUB_API_TOKEN` are resolved through `SecretsProvider` (`oscarvarto.mx.aws`),
which uses an env-var-first strategy:

1. **Environment variable** — `System.getenv(key)`. If present, the value is returned immediately and no AWS call is
   made. This is the default path for local development.
2. **AWS Secrets Manager** — looked up via the mapping in `secrets.json` on the test classpath. Useful in CI or shared
   environments where secrets live in a central store.

```mermaid
flowchart TD
    A["SecretsProvider.require(key)"] --> B{env var set?}
    B -- yes --> C[Return env value]
    B -- no --> D{mapping in secrets.json?}
    D -- no --> E["throw IllegalStateException"]
    D -- yes --> F{cached?}
    F -- yes --> G[Return cached value]
    F -- no --> H["AWS Secrets Manager"]
    H --> I[Cache raw secret]
    I --> J{field specified?}
    J -- yes --> K[Extract JSON field]
    J -- no --> L[Return raw string]

    style C fill:#e8f5e9,stroke:#2e7d32
    style G fill:#e8f5e9,stroke:#2e7d32
    style K fill:#e8f5e9,stroke:#2e7d32
    style L fill:#e8f5e9,stroke:#2e7d32
    style E fill:#ffebee,stroke:#c62828
```

Configuration lives in `src/test/resources/secrets.json`:

```json
{
  "secrets": {
    "region": "us-east-1",
    "mappings": {
      "GITHUB_USER": {
        "secretName": "playwright-practice/github",
        "field": "username"
      },
      "GITHUB_API_TOKEN": {
        "secretName": "playwright-practice/github",
        "field": "token"
      }
    }
  }
}
```

Multiple logical keys can point to the same `secretName`. Only one AWS API call is made, and the result is cached
in-memory for the duration of the test run. The `SecretsManagerClient` is created lazily and is never instantiated if
all secrets come from environment variables.

**Running tests:**

```zsh
# Local dev — explicitly enable the GitHub integration suite
GITHUB_USER=x GITHUB_API_TOKEN=y \
  ./gradlew test \
  -Dplaywright.github.integration.enabled=true \
  --tests "*.TestGitHubAPI"

# CI with secrets configured through SecretsProvider
./gradlew test \
  -Dplaywright.github.integration.enabled=true \
  --tests "*.TestGitHubAPI"
```

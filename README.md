# DevSecOps Practice Project — Angular + Spring Boot + SonarQube + Dependency-Track

This project is a hands-on practice environment for integrating **Static
Application Security Testing (SAST)** via SonarQube and **Software
Composition Analysis (SCA)** via Dependency-Track into a real dev workflow.

```
devsecops-demo/
├── docker-compose.yml       → starts SonarQube + Dependency-Track
├── springboot-backend/      → Spring Boot 2.7.5 REST API
│   ├── outdated jackson-databind (2.9.10) → real CVEs, for Dependency-Track
│   └── AdminController.java  → hardcoded password, SQL injection via string
│                                concatenation, empty catch block → for SonarQube
└── angular-frontend/         → Angular 14 app
    ├── outdated lodash (4.17.15) → real CVEs, for Dependency-Track
    └── app.component.ts/html     → [innerHTML] raw binding, XSS-prone pattern
                                     → for SonarQube
```

Both projects have real, intentionally planted issues so a first scan
actually shows meaningful findings.

---

## Prerequisites (installed locally, confirmed working)

- Docker Desktop (includes `docker compose`)
- Java 17 (`java -version`)
- Maven (`mvn -version`)
- Node 18 + npm (`node -v`, `npm -v`)
- Angular CLI (`ng version`)

---

## PART A — One-time environment setup

### A1. Start SonarQube + Dependency-Track

```bash
cd devsecops-demo
docker compose up -d
```

Wait 1–2 minutes, then confirm both load:
- SonarQube: http://localhost:9000 (`admin` / `admin`, forces password reset)
- Dependency-Track: http://localhost:8080 (`admin` / `admin`)

> Linux hosts only: if the sonarqube container keeps restarting, run
> `sudo sysctl -w vm.max_map_count=262144` before `docker compose up`.

### A2. Get a SonarQube token

Avatar (top-right) → **My Account** → **Security** → **Generate Tokens** →
copy it immediately (shown once).

### A3. Get a Dependency-Track API key

**Administration** → **Access Management** → **Teams** → **Automation** →
copy the API key (or generate one if none exists).

### A4. Create two projects in Dependency-Track

**Projects** → **Create Project** → make:
- `spring-backend`
- `angular-frontend`

Open each project and copy its **UUID** from the URL/overview page.

### A5. Confirm the vulnerability database has synced

**Administration** → **Analyzers** → check the NVD / OSS Index sync status.
If it's still syncing, wait — scanning before this finishes returns zero
findings even on genuinely vulnerable dependencies.

---

## PART B — Backend: Spring Boot

### B1. Build, test, and generate the SBOM

```bash
cd springboot-backend
mvn clean verify
```

This runs tests, has Jacoco instrument coverage, and (via the
`cyclonedx-maven-plugin`) writes `target/bom.xml` — a full list of every
resolved dependency in CycloneDX format.

### B2. Run the SonarQube scan

```bash
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=<YOUR_SONAR_TOKEN> \
  -Dsonar.projectKey=spring-backend
```

Should end with `BUILD SUCCESS` and a dashboard URL.

### B3. Push the SBOM to Dependency-Track

```bash
curl -X POST "http://localhost:8081/api/v1/bom" \
  -H "X-Api-Key: <YOUR_DTRACK_API_KEY>" \
  -F "project=<SPRING_BACKEND_PROJECT_UUID>" \
  -F "bom=@target/bom.xml"
```

---

## PART C — Frontend: Angular

> **Note on the Angular 14 test config:** the default `ng new` scaffold
> normally wires this up automatically. Because this project's config
> files were hand-written, three files needed explicit changes to make
> `ng test` runnable in headless/CI mode. They're already correct in this
> repo — documented here so you understand *why* they exist:
> - `src/test.ts` — entry point that loads all `*.spec.ts` files via
>   webpack's `require.context`
> - `src/polyfills-test.ts` — loads `zone.js` and `zone.js/testing`
>   together (must be a single file, not split across two imports, or
>   you'll hit a `ProxyZone` error)
> - `angular.json` (`architect.test.options`) — needs `main`,
>   `karmaConfig`, and a **string path** `polyfills` (not an array — Angular
>   14's builder rejects an array here, unlike the `build` target)
> - `tsconfig.spec.json` — must list `test.ts` and `polyfills-test.ts`
>   under `"files"` or TypeScript won't compile them

### C1. Install dependencies

```bash
cd angular-frontend
npm install
```

### C2. Run tests with coverage

Headless Chrome is required. If you don't use Chrome as a daily browser,
use puppeteer's bundled Chromium instead of a system install:

```bash
npm install --save-dev puppeteer
export CHROME_BIN=$(node -e "console.log(require('puppeteer').executablePath())")
npm run test:ci
```

This writes `coverage/demo-frontend/lcov.info`. SonarQube only needs this
file to exist and contain data — individual test pass/fail status doesn't
block the scan.

### C3. Generate the SBOM

```bash
npx @cyclonedx/cyclonedx-npm --output-file bom.json
```

### C4. Run the SonarQube scan

Runs in Docker, so it needs `host.docker.internal` to reach SonarQube on
the host machine:

```bash
docker run --rm \
  -e SONAR_HOST_URL=http://host.docker.internal:9000 \
  -e SONAR_TOKEN=<YOUR_SONAR_TOKEN> \
  -v "$(pwd):/usr/src" \
  sonarsource/sonar-scanner-cli
```

Reads `sonar-project.properties` in this folder. Should end with
`EXECUTION SUCCESS`.

### C5. Push the SBOM to Dependency-Track

```bash
curl -X POST "http://localhost:8081/api/v1/bom" \
  -H "X-Api-Key: <YOUR_DTRACK_API_KEY>" \
  -F "project=<ANGULAR_FRONTEND_PROJECT_UUID>" \
  -F "bom=@bom.json"
```

---

## PART D — Where to look at results

- **SonarQube** (`localhost:9000`) → open each project → **Security
  Hotspots** tab specifically for the planted hardcoded password / SQL
  concatenation (backend) and `[innerHTML]` binding (frontend).
- **Dependency-Track** (`localhost:8080`) → open each project →
  **Components** tab → `jackson-databind 2.9.10` (backend) and
  `lodash 4.17.15` (frontend) should each list CVEs with a severity
  rating.

If either shows zero findings: SonarQube — check you didn't create a
second project by mistake (wrong `sonar.projectKey`); Dependency-Track —
recheck **Administration → Analyzers** sync status.

---

## Standard workflow, generalized (for any future project)

This is the same shape regardless of stack (Java, Angular, Python, Go):

```
1. Bring servers up once        → docker compose up -d
2. Get credentials once         → Sonar token, DT API key, DT project UUIDs
3. Per project, per scan cycle:
     a. Run the build            (this is also what generates the SBOM)
     b. Run the Sonar scan       → uploads straight to SonarQube
     c. curl the SBOM            → uploads to Dependency-Track
4. Check both dashboards
```

Steps 3a–3c are exactly what a CI/CD pipeline (GitHub Actions, GitLab CI,
Jenkins) automates on every push — same four commands, just triggered
automatically instead of run by hand.

---

## Cleanup — stop and remove everything

Stop and remove containers, and delete the volumes (Postgres data,
SonarQube data, Dependency-Track data) for a full reset:

```bash
docker compose down -v
```

Remove local build artifacts if you want a clean working tree:

```bash
# from springboot-backend/
rm -rf target

# from angular-frontend/
rm -rf node_modules dist coverage .angular bom.json
```
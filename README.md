# DevSecOps Tools — Angular + Spring Boot + Nexus (+ SonarQube / Dependency-Track, + JFrog coming next)

This project is a hands-on practice environment for a full DevSecOps
toolchain: **SAST** (SonarQube), **SCA** (Dependency-Track), **artifact
management** (Nexus Repository), and — coming next — **container registry
+ image scanning** (JFrog Artifactory).

> **Current status:** SonarQube and Dependency-Track are temporarily
> commented out in `docker-compose.yml` while the Nexus leg is being
> practiced. Steps for both are kept below for when they're re-enabled.

```
devsecops-tools/                 (repo, formerly Sonarqube_and_DT)
├── docker-compose.yml           → starts Nexus (SonarQube + DT commented out)
├── settings.xml                 → Maven credentials/mirror config for Nexus
├── springboot-backend/          → Spring Boot 2.7.5 REST API (artifactId: demo-backend)
│   ├── outdated jackson-databind (2.9.10) → real CVEs, for Dependency-Track
│   └── AdminController.java      → hardcoded password, SQL injection via string
│                                    concatenation, empty catch block → for SonarQube
├── other-service/                → second Spring Boot service (artifactId: order-service)
│   └── same codebase, different artifactId — for practicing multi-microservice
│       deploys into one shared Nexus instance
└── angular-frontend/              → Angular 14.2 app
    ├── .npmrc                     → points npm at Nexus npm-public group
    ├── outdated lodash (4.17.15)  → real CVEs, for Dependency-Track
    └── app.component.ts/html      → [innerHTML] raw binding, XSS-prone pattern
                                      → for SonarQube
```

Both backend services and the frontend have real, intentionally planted
issues so a first scan actually shows meaningful findings.

---

## Prerequisites (installed locally, confirmed working)

- Docker Desktop (includes `docker compose`)
- No local Maven/Node/npm required — all builds run inside Docker
  containers (`maven:3.9-eclipse-temurin-17`, `node:18`)

---

## PART A — Environment setup

### A1. Start Nexus

```bash
cd devsecops-tools
docker compose up -d nexus
docker logs -f nexus     # wait for "Started Sonatype Nexus", ~1-3 min first boot
```

Get the generated admin password on first boot:
```bash
docker exec nexus cat /nexus-data/admin.password
```

Open `http://localhost:8081`, log in as `admin`, set a new password.

> SonarQube (`http://localhost:9000`) and Dependency-Track
> (`http://localhost:8080`) are commented out in `docker-compose.yml`
> right now. Uncomment those services and run `docker compose up -d` to
> bring them back when needed — Parts C, D, F below still apply as-is
> once they're running again.

### A2. Confirm the Docker network name

Compose auto-generates the network name from the folder name — don't
assume it, always check:
```bash
docker network ls | grep -i devsecops
```
Use whatever it prints (e.g. `sqdt_devsecops-net`) in place of
`<network-name>` in every command below.

### A3. Required Nexus security fix (one-time)

Fresh Nexus installs can return `403 Forbidden` on authenticated requests
even with correct credentials, if role/authorization isn't fully wired
up. If any `mvn deploy` or `npm install` below returns 403 with a
verified-correct password:
1. UI → **Settings → Security → Users** → `admin` → confirm `nx-admin`
   is in the **Granted** roles list
2. UI → **Settings → Security → Anonymous Access** → toggle on if reads
   still fail
3. Retest with:
   ```bash
   curl -u admin:<password> -I http://localhost:8081/repository/maven-public/
   ```
   Expect `200 OK` before moving on.

---

## PART B — Nexus: create the repositories

UI → ⚙️ **Settings → Repository → Repositories → Create repository**

| Format | Type | Name |
|---|---|---|
| maven2 | proxy | `maven-central-proxy` — Remote URL: `https://repo1.maven.org/maven2/` |
| maven2 | group | `maven-public` — Members: releases + snapshots + proxy |
| maven2 | hosted | `maven-releases` — Version policy: Release |
| maven2 | hosted | `maven-snapshots` — Version policy: Snapshot |
| npm | proxy | `npm-proxy` — Remote URL: `https://registry.npmjs.org` |
| npm | group | `npm-public` — Members: hosted + proxy |
| npm | hosted | `npm-hosted` |

> Names must match exactly what's referenced in `pom.xml` /
> `settings.xml` / `.npmrc` below — repo name typos are the #1 cause of
> 404s during deploy.

---

## PART C — Backend: build + deploy to Nexus (per service)

Each Spring Boot service is fully independent — its own folder, its own
`pom.xml`, deployed with its own command. None of them list each other
as a `<dependency>`.

`pom.xml` (each service) has:
```xml
<distributionManagement>
    <repository>
        <id>nexus-releases</id>
        <url>http://nexus:8081/repository/maven-releases/</url>
    </repository>
    <snapshotRepository>
        <id>nexus-snapshots</id>
        <url>http://nexus:8081/repository/maven-snapshots/</url>
    </snapshotRepository>
</distributionManagement>
```
(note: `nexus:8081`, not `localhost:8081` — Maven runs inside a
container on the same Docker network as Nexus, so it resolves Nexus by
its service/container name, not `localhost`)

`settings.xml` (project root, bind-mounted into the Maven container)
holds the `admin` credentials for `nexus-releases`, `nexus-snapshots`,
**and** `nexus-maven-public` (the mirror used for reads — needs its own
matching `<server>` block or dependency resolution 403s).

### C1. Deploy `springboot-backend` (demo-backend)

```bash
cd devsecops-tools
docker run --rm \
  -v "$(pwd)/springboot-backend":/app \
  -v "$(pwd)/settings.xml":/root/.m2/settings.xml \
  -w /app \
  --network <network-name> \
  maven:3.9-eclipse-temurin-17 mvn clean deploy -DskipTests
```

### C2. Deploy `other-service` (order-service)

Same command, different source folder — nothing else changes:
```bash
docker run --rm \
  -v "$(pwd)/other-service":/app \
  -v "$(pwd)/settings.xml":/root/.m2/settings.xml \
  -w /app \
  --network <network-name> \
  maven:3.9-eclipse-temurin-17 mvn clean deploy -DskipTests
```

### C3. Verify

UI → **Browse** → `maven-snapshots` → should show two sibling folders:
```
com/example/demo-backend/0.0.1-SNAPSHOT/
com/example/order-service/0.0.1-SNAPSHOT/
```

### C4. Manually uploading a pre-built `.jar` (no `mvn deploy`)

For a jar you already have (built elsewhere, or a one-off artifact):
1. UI → **Upload** → pick a hosted repo (`maven-releases` or
   `maven-snapshots`)
2. Fill in **Group** / **Artifact** / **Version**, attach the `.jar`
3. Click **Upload**

Note: `maven-releases` rejects a version ending in `-SNAPSHOT`;
`maven-snapshots` expects one.

---

## PART D — Frontend: Angular deps through Nexus

`angular-frontend/.npmrc`:
```
registry=http://nexus:8081/repository/npm-public/
always-auth=false
```

```bash
cd angular-frontend
docker run --rm \
  -v "$(pwd)":/app \
  -w /app \
  --network <network-name> \
  node:18 npm install
```

Verify: UI → **Browse** → `npm-proxy` → cached packages (`@angular/*`,
`lodash`, `rxjs`, `zone.js`, etc.) appear as they're pulled. `npm-hosted`
stays empty unless you explicitly `npm publish` your own package there.
Also sanity-check `package-lock.json` — `"resolved"` fields should point
at `http://nexus:8081/repository/npm-public/...`, not
`https://registry.npmjs.org/...`.

---

## PART E — SonarQube (currently disabled — uncomment in docker-compose.yml to use)

### E1. Get a SonarQube token
Avatar (top-right) → **My Account** → **Security** → **Generate Tokens**

### E2. Backend scan
```bash
cd springboot-backend
mvn clean verify
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=<YOUR_SONAR_TOKEN> \
  -Dsonar.projectKey=spring-backend
```

### E3. Frontend scan
```bash
cd angular-frontend
docker run --rm \
  -e SONAR_HOST_URL=http://host.docker.internal:9000 \
  -e SONAR_TOKEN=<YOUR_SONAR_TOKEN> \
  -v "$(pwd):/usr/src" \
  sonarsource/sonar-scanner-cli
```

---

## PART F — Dependency-Track (currently disabled — uncomment in docker-compose.yml to use)

### F1. Get a DT API key
**Administration → Access Management → Teams → Automation**

### F2. Create projects
**Projects → Create Project** → `spring-backend`, `angular-frontend` →
copy each UUID

### F3. Push SBOMs

Backend (`target/bom.xml` generated by `mvn clean verify` via the
CycloneDX Maven plugin):
```bash
curl -X POST "http://localhost:8081/api/v1/bom" \
  -H "X-Api-Key: <YOUR_DTRACK_API_KEY>" \
  -F "project=<SPRING_BACKEND_PROJECT_UUID>" \
  -F "bom=@target/bom.xml"
```

Frontend:
```bash
npx @cyclonedx/cyclonedx-npm --output-file bom.json
curl -X POST "http://localhost:8081/api/v1/bom" \
  -H "X-Api-Key: <YOUR_DTRACK_API_KEY>" \
  -F "project=<ANGULAR_FRONTEND_PROJECT_UUID>" \
  -F "bom=@bom.json"
```

---

## PART G — JFrog Artifactory (Docker registry) — *placeholder, next up*

This section will cover pushing the Spring Boot backend image to
Artifactory as a Docker repo. To be filled in once that leg is done.

---

## Where to look at results

- **Nexus** (`localhost:8081`) → **Browse** → repo → confirm artifacts
  landed in the right `groupId/artifactId` (Maven) or package (npm) path
- **SonarQube** (`localhost:9000`, when enabled) → project → **Security
  Hotspots** tab for the planted hardcoded password / SQL concatenation
  (backend) and `[innerHTML]` binding (frontend)
- **Dependency-Track** (`localhost:8080`, when enabled) → project →
  **Components** tab → `jackson-databind 2.9.10` / `lodash 4.17.15`
  should list CVEs with severity

---

## Troubleshooting notes learned so far

- **`403 Forbidden` with correct credentials** → Nexus authorization
  gap, not a config file problem — check the `admin` user's granted
  roles (Part A3) before touching `settings.xml`/`.npmrc` further
- **Network name mismatches** → Compose sanitizes special characters in
  the folder name unpredictably (`SQ&DT` → `sqdt_devsecops-net`); always
  confirm with `docker network ls`, never assume
- **`localhost` vs. service name** → containers on the same Docker
  network reach Nexus via `nexus:8081`; your host browser uses
  `localhost:8081`. Mixing these up is the most common cause of
  connection-refused vs. 401/403 confusion
- **Repo name mismatches** → `pom.xml`'s `distributionManagement` URLs
  must exactly match the repo names actually created in the Nexus UI —
  a typo here manifests as a deploy-time 404, after the build itself
  already succeeded

---

## Standard workflow, generalized (for any future service)

```
1. Bring Nexus up once           → docker compose up -d nexus
2. Create repos once             → Maven + npm hosted/proxy/group
3. Per service, per build cycle:
     a. Write/verify pom.xml distributionManagement + settings.xml creds
     b. mvn clean deploy          → uploads jar to Nexus
     c. (if enabled) sonar scan + SBOM push to SonarQube/DT
4. Check Nexus Browse view for each new artifact
```

This is exactly what a CI/CD pipeline (GitHub Actions, GitLab CI,
Jenkins) automates on every push — same commands, triggered
automatically instead of run by hand.

---

## Cleanup — stop and remove everything

```bash
docker compose down -v
```

Remove local build artifacts:
```bash
# from springboot-backend/ and other-service/
rm -rf target

# from angular-frontend/
rm -rf node_modules dist coverage .angular bom.json
```
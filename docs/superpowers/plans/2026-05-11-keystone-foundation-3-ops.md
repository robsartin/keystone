# Keystone Foundation — Plan 3: Local Infra + CI + Repo Governance

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the foundation: one-command local infrastructure (Postgres + Prometheus + Grafana), CI that runs the full `./mvnw verify -Pmutation,openapi-gate` plus pushes the Docker image to GHCR on every push to `main`, ADR-0009 capturing trunk-based-dev + squash-merge + signed-commits, and a Repository Ruleset that enforces it.

**Architecture:** Three phases, each shipping as a separate PR against `main`. Phase A lands the Dockerfile, `docker-compose.yml`, Prometheus + Grafana provisioning, and updates README / CLAUDE.md. Phase B lands `.github/workflows/ci.yml` with a `build` job (runs every verify gate) and a `docker` job (only on push to main). Phase C lands ADR-0009, the ruleset JSON, and applies the ruleset via `gh api` — gated on explicit user confirmation.

**Tech Stack:** Docker + Compose, Postgres 16, `prom/prometheus`, `grafana/grafana`, GitHub Actions (Ubuntu runners, Temurin JDK 25, Maven cache action), GitHub Container Registry (`ghcr.io`), GitHub Repository Rulesets.

**Pre-condition:** On `main` at `/Users/sartin/code/keystone`, post-Plan-2-merged state. Phase A starts on a fresh branch off `main`. Each phase has its own branch and its own PR.

**Definition of done (whole plan):**

1. `docker compose up -d` brings up `keystone-postgres`, `keystone-prometheus`, `keystone-grafana`, and `keystone-app` containers.
2. The app accepts a balanced `POST /journal-entries` from inside the compose network.
3. Prometheus scrapes the app's `/actuator/prometheus`; the "Keystone Overview" Grafana dashboard auto-loads at `http://localhost:3000` with panels populated.
4. A new commit to `main` (via a PR) runs `./mvnw -B verify -Pmutation,openapi-gate` in CI and goes green.
5. On the same `main` push, the `docker` job builds and pushes the image to `ghcr.io/robsartin/keystone:latest` and `:sha-<sha>`.
6. The Repository Ruleset on `main` requires PRs, the `build` status check, linear history, squash-only merges, and signed commits.
7. ADR-0009 is committed; ADR README updated.
8. Issue #12 closes when Phase C merges.

---

## Commit signing — enforced in Phase C

Signing is configured locally (`git config commit.gpgsign true`, `gpg.format ssh`, `user.signingkey ~/.ssh/id_ed25519.pub`) and the SSH key is registered as a signing key on GitHub. Every commit from this point is signed.

**Plan 3's ruleset includes `required_signatures` from day one.** Phase C will enforce signed commits on `main` — every commit on every PR branch must be signed by a key GitHub can verify against the author's account.

**Pre-Plan-3 commits on `main` are grandfathered** — the rule only blocks *new* unsigned commits going forward. Historical merges (which were unsigned via GitHub's merge-commit identity) stay as they are.

---

## File Structure (all paths relative to `~/code/keystone`)

**Created or modified in Plan 3:**

| Path | Responsibility | Phase |
|---|---|---|
| `Dockerfile` | Multi-stage build of the Spring Boot fat jar runtime image | A |
| `.dockerignore` | Keep image small; exclude `.git`, `target/`, `node_modules/` etc. | A |
| `docker-compose.yml` | Postgres 16 + Prometheus + Grafana + keystone-app | A |
| `compose/prometheus/prometheus.yml` | Scrape `keystone-app:8080/actuator/prometheus` every 10s | A |
| `compose/grafana/provisioning/datasources/prometheus.yml` | Wire Prometheus as the default datasource | A |
| `compose/grafana/provisioning/dashboards/dashboard.yml` | Tell Grafana to auto-load dashboards from `/var/lib/grafana/dashboards` | A |
| `compose/grafana/dashboards/keystone-overview.json` | "Keystone Overview" dashboard panels | A |
| `README.md` | Update "Quick start" to `docker compose up -d` | A |
| `CLAUDE.md` | Add compose to Quick Reference | A |
| `.github/workflows/ci.yml` | `build` + `docker` jobs | B |
| `docs/adr/0009-trunk-based-development.md` | Governance decision | C |
| `docs/adr/README.md` | Flip 0009 from Reserved to Accepted | C |
| `docs/ruleset-main.json` | Repository ruleset spec for `gh api` | C |

**Not changed in Plan 3:** all `src/**` (the walking skeleton is done); all other ADRs; the four OpenAPI files; the `dora-metrics.yml` and `auto-label-incidents.yml` workflows.

---

## Phase order (each phase = one PR against `main`)

- **Phase A — Local infrastructure** (Tasks 1–6) — Dockerfile, compose, prometheus.yml, Grafana provisioning + dashboard, README + CLAUDE.md updates. Acceptance: `docker compose up -d && curl localhost:3000` shows the dashboard.
- **Phase B — CI workflow** (Tasks 7–9) — `.github/workflows/ci.yml` with `build` (every gate) + `docker` (push to GHCR on main). Acceptance: a test PR runs the build job green; a push to main builds and pushes the image.
- **Phase C — Governance** (Tasks 10–12) — ADR-0009, ruleset JSON, apply ruleset (gated on user confirmation). Acceptance: a test PR can't be merged without the `build` check passing; closes issue #12 on merge.

---

# Phase A — Local infrastructure

---

## Task 1: Dockerfile + `.dockerignore`

Multi-stage build: the first stage compiles the jar with the same Java 25 + Maven the project uses; the second stage runs only the JRE + the layered jar.

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: Write `.dockerignore`**

```gitignore
.git
.github
.idea
target/
node_modules/
docs/
*.md
.DS_Store
.mvn/wrapper/maven-wrapper.jar
```

(Wrapper jar gets re-downloaded by the build stage; everything else is build noise.)

- [ ] **Step 2: Write `Dockerfile`**

```Dockerfile
# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
COPY checkstyle.xml ./
RUN ./mvnw -B -q -DskipTests package spring-boot:repackage
RUN java -Djarmode=tools -jar target/keystone-0.1.0-SNAPSHOT.jar extract --layers --launcher

FROM eclipse-temurin:25-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=default \
    JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75"
RUN useradd --system --uid 1001 --create-home --shell /usr/sbin/nologin keystone \
    && mkdir -p /app && chown -R keystone:keystone /app
USER keystone
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/dependencies/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/spring-boot-loader/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/snapshot-dependencies/ ./
COPY --from=build --chown=keystone:keystone /workspace/keystone-0.1.0-SNAPSHOT/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

- [ ] **Step 3: Test the build locally**

```bash
docker build -t keystone:local . 2>&1 | tail -10
```

Expected: `Successfully built` / `Successfully tagged keystone:local`. The build will take ~3-5 minutes the first time (downloads JDK + deps). Subsequent rebuilds are faster thanks to layer caching.

Confirm the image is reasonable in size:

```bash
docker images keystone:local --format "{{.Size}}"
```

Expected: 250-400 MB (the JRE alone is ~200 MB).

- [ ] **Step 4: Smoke test the image runs**

This needs a Postgres available so the app can connect. Start one (Task 2 will land docker-compose for the full story; this is just to verify the image itself):

```bash
docker run -d --name kp-smoke -p 5434:5432 \
  -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
  -e POSTGRES_DB=keystone postgres:16
sleep 5
docker run --rm --name keystone-smoke --network host \
  -e DATABASE_URL=jdbc:postgresql://localhost:5434/keystone \
  -e DATABASE_USER=keystone -e DATABASE_PASSWORD=keystone \
  keystone:local &
sleep 20
curl -fsSL http://localhost:8080/actuator/health | head
docker kill keystone-smoke 2>/dev/null
docker rm -f kp-smoke
```

Expected: `{"status":"UP"}`. The `--network host` is macOS-friendly; on Linux it makes `localhost:5434` reachable.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "$(cat <<'EOF'
feat(docker): multi-stage Dockerfile for keystone

Build stage: temurin:25-jdk + Maven wrapper. dependency:go-offline
runs first so dep layers cache across rebuilds. spring-boot:repackage
produces the layered fat jar.

Runtime stage: temurin:25-jre, non-root user, layered jar extracted
into four directories (dependencies, loader, snapshot-deps,
application) so Spring's classloader doesn't re-expand on every boot.
Image ~300 MB.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `docker-compose.yml`

Postgres + Prometheus + Grafana + the app. Compose network so the app can reach Postgres by hostname.

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: Write `docker-compose.yml`**

```yaml
name: keystone

services:
  postgres:
    image: postgres:16
    container_name: keystone-postgres
    environment:
      POSTGRES_USER: keystone
      POSTGRES_PASSWORD: keystone
      POSTGRES_DB: keystone
    ports:
      - "5434:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "keystone", "-d", "keystone"]
      interval: 5s
      timeout: 3s
      retries: 10

  app:
    build:
      context: .
    image: keystone:local
    container_name: keystone-app
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: default
      DATABASE_URL: jdbc:postgresql://postgres:5432/keystone
      DATABASE_USER: keystone
      DATABASE_PASSWORD: keystone
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 6
      start_period: 30s

  prometheus:
    image: prom/prometheus:latest
    container_name: keystone-prometheus
    volumes:
      - ./compose/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    depends_on:
      app:
        condition: service_healthy

  grafana:
    image: grafana/grafana:latest
    container_name: keystone-grafana
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
      GF_AUTH_DISABLE_LOGIN_FORM: "true"
      GF_USERS_DEFAULT_THEME: dark
    volumes:
      - ./compose/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./compose/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    ports:
      - "3000:3000"
    depends_on:
      - prometheus

volumes:
  postgres-data:
  prometheus-data:
  grafana-data:
```

Notes:
- `app.build.context: .` builds from the Dockerfile committed in Task 1.
- The `app` service exposes `8080:8080`; from your host, `curl localhost:8080` works. Inside the network, Prometheus reaches the app at `http://app:8080`.
- Grafana is open to localhost with anonymous Admin access for dev convenience (Plan 5+ will tighten auth).

- [ ] **Step 2: Validate compose syntax**

```bash
docker compose config 2>&1 | head -20
```

Expected: prints the resolved configuration without errors.

- [ ] **Step 3: Commit (compose won't run yet — Prometheus/Grafana config files arrive in Tasks 3–5)**

```bash
git add docker-compose.yml
git commit -m "$(cat <<'EOF'
feat(compose): docker-compose for postgres + prometheus + grafana + app

Four services, healthchecks on postgres + app so dependents wait
appropriately. Prometheus and Grafana volume-mount config from
compose/ (lands in subsequent tasks). Grafana opens with anonymous
Admin access for dev convenience.

Ports: 5434 postgres, 8080 app, 9090 prometheus, 3000 grafana.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Prometheus scrape config

**Files:**
- Create: `compose/prometheus/prometheus.yml`

- [ ] **Step 1: Write the scrape config**

```yaml
global:
  scrape_interval: 10s
  evaluation_interval: 10s
  external_labels:
    cluster: keystone-local

scrape_configs:
  - job_name: keystone
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["app:8080"]
        labels:
          service: keystone
          env: local
```

- [ ] **Step 2: Commit**

```bash
git add compose/prometheus/prometheus.yml
git commit -m "$(cat <<'EOF'
feat(compose): prometheus scrape config for keystone-app

10s scrape interval against /actuator/prometheus. Static target
points at the compose-network DNS name 'app:8080'. External label
cluster=keystone-local distinguishes from any future deployments.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Grafana datasource + dashboard provisioning

**Files:**
- Create: `compose/grafana/provisioning/datasources/prometheus.yml`
- Create: `compose/grafana/provisioning/dashboards/dashboard.yml`

- [ ] **Step 1: Datasource**

Create `compose/grafana/provisioning/datasources/prometheus.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

- [ ] **Step 2: Dashboard provisioning manifest**

Create `compose/grafana/provisioning/dashboards/dashboard.yml`:

```yaml
apiVersion: 1

providers:
  - name: keystone
    orgId: 1
    folder: ""
    type: file
    disableDeletion: true
    updateIntervalSeconds: 30
    allowUiUpdates: false
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 3: Commit (the actual dashboard JSON is Task 5)**

```bash
git add compose/grafana/provisioning/
git commit -m "$(cat <<'EOF'
feat(compose): grafana datasource + dashboard provisioning

Prometheus declared as the default datasource, non-editable. Dashboard
provider auto-loads everything under /var/lib/grafana/dashboards.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: "Keystone Overview" dashboard JSON

**Files:**
- Create: `compose/grafana/dashboards/keystone-overview.json`

The dashboard's panels follow the foundation design spec §8:

- HTTP RPS (counter rate from `http_server_requests_seconds_count`)
- p50/p95/p99 latency (histogram quantile on `http_server_requests_seconds_bucket`)
- JVM heap (used vs max) — `jvm_memory_used_bytes{area="heap"}`
- JVM GC pause (`jvm_gc_pause_seconds_sum` rate)
- Journal entries posted rate (custom counter) — `keystone_journal_entries_posted_total` rate, split by `result`
- Postgres pool in-use vs idle — `hikaricp_connections_active`, `hikaricp_connections_idle`

- [ ] **Step 1: Write the dashboard JSON**

Create `compose/grafana/dashboards/keystone-overview.json`:

```json
{
  "annotations": { "list": [] },
  "editable": false,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "id": null,
  "links": [],
  "liveNow": false,
  "panels": [
    {
      "title": "HTTP RPS",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "targets": [
        {
          "expr": "sum by (status) (rate(http_server_requests_seconds_count{application=\"keystone\"}[1m]))",
          "legendFormat": "{{status}}",
          "refId": "A"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "reqps" } }
    },
    {
      "title": "HTTP latency (p50/p95/p99)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "targets": [
        { "expr": "histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"keystone\"}[1m])))", "legendFormat": "p50", "refId": "A" },
        { "expr": "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"keystone\"}[1m])))", "legendFormat": "p95", "refId": "B" },
        { "expr": "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"keystone\"}[1m])))", "legendFormat": "p99", "refId": "C" }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    },
    {
      "title": "Journal entries posted",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "targets": [
        {
          "expr": "sum by (result) (rate(keystone_journal_entries_posted_total[1m]))",
          "legendFormat": "{{result}}",
          "refId": "A"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "reqps" } }
    },
    {
      "title": "Journal entry post duration (p95)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "targets": [
        {
          "expr": "histogram_quantile(0.95, sum by (le) (rate(keystone_journal_entries_post_duration_bucket[1m])))",
          "legendFormat": "p95",
          "refId": "A"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    },
    {
      "title": "JVM heap used vs max",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 16 },
      "targets": [
        { "expr": "sum(jvm_memory_used_bytes{application=\"keystone\", area=\"heap\"})", "legendFormat": "used", "refId": "A" },
        { "expr": "sum(jvm_memory_max_bytes{application=\"keystone\", area=\"heap\"})", "legendFormat": "max", "refId": "B" }
      ],
      "fieldConfig": { "defaults": { "unit": "bytes" } }
    },
    {
      "title": "JVM GC pause (rate)",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 16 },
      "targets": [
        {
          "expr": "sum by (action) (rate(jvm_gc_pause_seconds_sum{application=\"keystone\"}[1m]))",
          "legendFormat": "{{action}}",
          "refId": "A"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    },
    {
      "title": "Postgres connection pool",
      "type": "timeseries",
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 24 },
      "targets": [
        { "expr": "hikaricp_connections_active{application=\"keystone\"}", "legendFormat": "active", "refId": "A" },
        { "expr": "hikaricp_connections_idle{application=\"keystone\"}", "legendFormat": "idle", "refId": "B" }
      ]
    }
  ],
  "refresh": "10s",
  "schemaVersion": 39,
  "tags": ["keystone"],
  "templating": { "list": [] },
  "time": { "from": "now-15m", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "Keystone Overview",
  "uid": "keystone-overview",
  "version": 1,
  "weekStart": ""
}
```

- [ ] **Step 2: Boot the whole compose stack and verify**

```bash
docker compose up -d --build
sleep 30
curl -fsSL http://localhost:8080/actuator/health
curl -fsSL "http://localhost:9090/api/v1/targets" | python3 -c "import sys,json; [print(t['labels']['service'], t['health']) for t in json.load(sys.stdin)['data']['activeTargets']]"
```

Expected:
- `{"status":"UP"}` from the app.
- `keystone up` from the Prometheus targets endpoint.

Open Grafana in a browser:
```bash
open http://localhost:3000/d/keystone-overview/keystone-overview
```

Expected: the "Keystone Overview" dashboard loads. Most panels say "No data" because no traffic yet — fire some by POSTing entries:

```bash
for i in $(seq 1 30); do
  curl -fsSL -X POST http://localhost:8080/journal-entries \
    -H "Content-Type: application/json" \
    -d "{\"occurredOn\":\"2026-05-11\",\"description\":\"smoke $i\",\"currency\":\"USD\",\"postings\":[{\"account\":\"1000\",\"side\":\"DEBIT\",\"minorUnits\":100},{\"account\":\"3000\",\"side\":\"CREDIT\",\"minorUnits\":100}]}" > /dev/null
done
```

After ~30 seconds, refresh Grafana — panels populate.

Tear down before committing:

```bash
docker compose down -v
```

- [ ] **Step 3: Commit**

```bash
git add compose/grafana/dashboards/
git commit -m "$(cat <<'EOF'
feat(compose): Keystone Overview Grafana dashboard

Seven panels covering HTTP RPS, p50/p95/p99 latency, journal-entries
posted rate by result, post-duration p95, JVM heap, GC pause rate,
and Postgres connection pool active/idle.

Verified locally: docker compose up + 30 POST /journal-entries
populates every panel.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Update README + CLAUDE.md to use compose

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update README "Quick start"**

In `README.md`, find the "Quick start" section and replace the existing `docker run … postgres:16` block with:

```markdown
## Quick start

```bash
docker compose up -d --build
```

Brings up Postgres, the app, Prometheus, and Grafana. Wait ~30 seconds for the app to boot, then:

```bash
curl -i -X POST http://localhost:8080/journal-entries \
  -H "Content-Type: application/json" \
  -d '{
    "occurredOn": "2026-05-11",
    "description": "opening balance",
    "currency": "USD",
    "postings": [
      { "account": "1000", "side": "DEBIT",  "minorUnits": 10000 },
      { "account": "3000", "side": "CREDIT", "minorUnits": 10000 }
    ]
  }'
```

Open the Grafana dashboard at [http://localhost:3000/d/keystone-overview](http://localhost:3000/d/keystone-overview). To shut down:

```bash
docker compose down       # keep volumes
docker compose down -v    # nuke volumes too
```
```

Also update the Status section to:

```markdown
## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton (POST /journal-entries, JPA + Postgres + Flyway, observability, OpenAPI gates)
- [x] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning
```

(The Plan 3 box is checked only after Phase C merges — keep it unchecked here and update again at Plan 3's final task.)

For Phase A's PR specifically, leave the box unchecked.

- [ ] **Step 2: Update CLAUDE.md Quick Reference**

Add a new bullet to the Quick Reference list in `CLAUDE.md` (just after `./mvnw spring-boot:run`):

```markdown
- `docker compose up -d --build` — bring up the full local stack (Postgres + app + Prometheus + Grafana). Dashboard at http://localhost:3000.
- `docker compose down -v` — tear it all down including volumes.
```

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: switch Quick start to docker compose

README's Quick start now uses `docker compose up -d --build` (brings
up postgres + app + prometheus + grafana). CLAUDE.md Quick Reference
gains compose up/down entries. The Plan 3 status box stays unchecked
until Phase C merges.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase A acceptance

6 commits on the branch. `docker compose up -d --build` brings the stack live; Grafana dashboard renders with populated panels after some smoke traffic. PR closes nothing — Phase A is infrastructure plumbing.

Branch name: `plan-3-phase-a-local-infra`. PR title: "Plan 3 Phase A: local infrastructure (Docker compose + Grafana dashboard)".

---

# Phase B — CI workflow

---

## Task 7: ADR-0009 — Trunk-based development decision

ADR-0009 captures the *governance* model. The ruleset that enforces it lands in Phase C; the workflow that backs the required `build` check lands here. Writing the ADR first means subsequent reviewers can see the rationale.

**Files:**
- Create: `docs/adr/0009-trunk-based-development.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Write ADR-0009**

```markdown
# ADR-0009: Trunk-based development with squash-merged PRs and required CI

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

We need a development model that:

- Keeps `main` always-shippable (CI green, every commit a single coherent
  change).
- Makes review explicit (no direct commits to `main`).
- Keeps `git log` readable (no merge bubbles, no per-commit churn).
- Provides a clean audit trail for every change.

Plan 1 + Plan 2 already implicitly followed this — every phase landed as
a separate PR with a squash merge. This ADR codifies the practice.

## Decision

- **Trunk-based:** `main` is the only long-lived branch. Feature work
  lives on short-lived branches named `<issue-number>-<slug>` (single
  issue) or `plan-N-phase-X-<slug>` (multi-task plan phase).
- **PR required:** no direct pushes to `main`. The Repository Ruleset in
  Phase C enforces this.
- **Required check:** the `build` job in `.github/workflows/ci.yml` must
  pass on every PR before merge. The check runs `./mvnw -B verify
  -Pmutation,openapi-gate` — every quality gate green every time.
- **Squash-merge only:** the PR becomes one commit on `main` with the
  PR's title as the commit subject and the body as the message. Merge
  commits and rebase merges are disabled.
- **Linear history:** force-pushes to `main` and merge commits on `main`
  are forbidden by the ruleset.
- **Signed commits:** required by the ruleset. The developer has SSH
  signing (`ed25519`) configured locally and the key registered on
  GitHub. Every new commit on every PR must be signed.

## Consequences

- A PR is the only path to `main` — easier auditing, harder mistakes.
- Every commit on `main` corresponds to a PR with a stable URL, a CI
  run, and a reviewable diff.
- We accept that no-CI commits (one-off doc tweaks) still need a PR;
  the friction is the point.
- `gh repo` and `gh pr` are part of the daily tool set; CONTRIBUTING.md
  documents the workflow.
- Reverting a change is a revert PR, never a force-push.
```

- [ ] **Step 2: Update `docs/adr/README.md`**

Replace the 0009 row from "Reserved" with:

```
| [0009](0009-trunk-based-development.md) | Trunk-based development with squash-merged PRs and required CI | Accepted |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0009-trunk-based-development.md docs/adr/README.md
git commit -m "$(cat <<'EOF'
docs(adr): 0009 trunk-based development with squash-merged PRs

Captures the workflow Plans 1 and 2 already followed: trunk-based,
PR-required, squash-merge-only, linear history, required build check,
signed commits enforced via the ruleset in the next task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `.github/workflows/ci.yml` — `build` job

The `build` job is the required status check. It runs every quality gate the project has.

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow with just the build job**

```yaml
name: ci

on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 25
    env:
      JAVA_TOOL_OPTIONS: -Dorg.slf4j.simpleLogger.defaultLogLevel=info
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
          cache: maven

      - name: Set up Node (for Spectral via npx)
        uses: actions/setup-node@v4
        with:
          node-version: "20"

      - name: Verify
        run: ./mvnw -B clean verify -Pmutation,openapi-gate

      - name: Upload JaCoCo report
        if: ${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-html
          path: target/site/jacoco
          if-no-files-found: ignore
          retention-days: 14

      - name: Upload OpenAPI spec
        if: ${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: openapi.yaml
          path: target/openapi.yaml
          if-no-files-found: ignore
          retention-days: 14

      - name: Upload PIT mutation report
        if: ${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: pit-report
          path: target/pit-reports
          if-no-files-found: ignore
          retention-days: 14
```

Notes:
- `cache: maven` on `setup-java@v4` is the modern Maven cache (no separate `actions/cache` step needed).
- The concurrency group cancels prior PR runs but not main runs.
- Spectral runs via npx during `verify -Popenapi-gate`, so Node 20 is on the runner.
- Three artifacts uploaded `if: always()` so they're available even when verify fails.

- [ ] **Step 2: Push the workflow on a PR; observe it run**

This step is part of *this* task's PR — the workflow file is committed, the PR opens, and CI runs against the PR itself (it triggers on `pull_request`). Verify the run succeeds before merging.

If the run fails, the failure mode tells you what to fix:
- `Spectral: ENOENT` → check Node is actually on the runner (the `setup-node@v4` step should handle it).
- `openapi-diff: 404 fetching old spec` → the spec is on main as of Phase E, so this should work; if it doesn't, the URL might be wrong (check `https://raw.githubusercontent.com/robsartin/keystone/main/docs/openapi/openapi.yaml`).
- `Mutation coverage below 60%` → PIT score has drifted; investigate.
- `Testcontainers cannot connect to Docker` → GitHub Actions Ubuntu runners include Docker; should not happen. If it does, `services` block is needed.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
feat(ci): GitHub Actions workflow with build job

Runs ./mvnw -B clean verify -Pmutation,openapi-gate on every PR and
every push to main. Temurin JDK 25 with maven cache; Node 20 for
Spectral via npx. Three artifacts uploaded on every run (success or
failure): JaCoCo HTML, target/openapi.yaml, PIT mutation report.

Concurrency cancels stale PR runs but lets main runs complete.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Add the `docker` job — push image to GHCR on `main`

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Append the `docker` job**

In `.github/workflows/ci.yml`, replace the top-level `permissions: contents: read` with the broader block:

```yaml
permissions:
  contents: read
  packages: write
```

And append the new job at the end:

```yaml
  docker:
    if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 20
    permissions:
      contents: read
      packages: write
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build, smoke test, and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ghcr.io/robsartin/keystone:latest
            ghcr.io/robsartin/keystone:sha-${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          provenance: false

      - name: Smoke test image
        run: |
          docker run -d --name kp-smoke -p 5434:5432 \
            -e POSTGRES_USER=keystone -e POSTGRES_PASSWORD=keystone \
            -e POSTGRES_DB=keystone postgres:16
          for i in $(seq 1 20); do
            if docker exec kp-smoke pg_isready -U keystone -d keystone > /dev/null 2>&1; then break; fi
            sleep 1
          done
          docker run -d --name keystone-smoke --network host \
            -e DATABASE_URL=jdbc:postgresql://localhost:5434/keystone \
            -e DATABASE_USER=keystone -e DATABASE_PASSWORD=keystone \
            ghcr.io/robsartin/keystone:sha-${{ github.sha }}
          for i in $(seq 1 30); do
            if curl -fsSL http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
              echo "Image healthy"
              break
            fi
            sleep 2
          done
          curl -fsSL http://localhost:8080/actuator/health | grep -q '"status":"UP"'
          docker rm -f keystone-smoke kp-smoke
```

Notes:
- `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` — only runs on main pushes, not PRs.
- `needs: build` — won't run unless `build` is green.
- `packages: write` — required to push to GHCR.
- `cache-from/to: type=gha` reuses Docker layer cache between runs.
- The smoke test post-push proves the published image actually boots before declaring success.

- [ ] **Step 2: Final verify on the PR**

Push and watch the run. The `docker` job is conditional on `push` to main, so it won't appear on the PR build itself — only after merge.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
feat(ci): docker job pushes image to GHCR on push to main

Builds via docker/build-push-action@v6 with GHA cache; tags :latest
and :sha-<sha>; smoke-tests the published image against a temporary
Postgres before declaring success. Only runs on push to main (not PRs)
and only after the build job is green.

Image visibility: public, matches the repo (per foundation design
spec §11 — confirmed default).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B acceptance

3 commits. PR runs `build` against itself; on merge to main, the `docker` job builds and publishes the image. Branch: `plan-3-phase-b-ci-workflow`. PR title: "Plan 3 Phase B: GitHub Actions CI workflow".

After merge, verify the image is on GHCR:

```bash
gh api user/packages/container/keystone/versions --jq '.[].metadata.container.tags'
```

Expected output includes `latest` and `sha-<commit-sha>`.

---

# Phase C — Governance: ruleset

---

## Task 10: Repository Ruleset JSON

**Files:**
- Create: `docs/ruleset-main.json`

- [ ] **Step 1: Write the ruleset spec**

```json
{
  "name": "main-protection",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/main"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "required_signatures" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "build", "integration_id": 15368 }
        ]
      }
    }
  ],
  "bypass_actors": []
}
```

Notes:
- `integration_id: 15368` is the GitHub Actions app — it's what supplies the `build` status. Found via `gh api orgs/<org>/installations` or constant from GitHub docs.
- `required_approving_review_count: 0` — solo developer; raise when there's another reviewer.
- `allowed_merge_methods: ["squash"]` — enforces squash-only per ADR-0009.
- **`required_signatures` rule** — enforces signed commits on every PR. SSH signing is set up locally; GitHub verifies via the registered signing key.
- `bypass_actors: []` — nobody bypasses, including the repo owner. Tighten governance from day one.

- [ ] **Step 2: Validate JSON syntax**

```bash
python3 -m json.tool docs/ruleset-main.json > /dev/null && echo OK
```

- [ ] **Step 3: Commit**

```bash
git add docs/ruleset-main.json
git commit -m "$(cat <<'EOF'
feat(governance): repository ruleset spec for main

Six rules: deletion + non-fast-forward + linear history +
required_signatures + PR (squash-only, dismiss stale reviews on
push) + required status check (the 'build' job from ci.yml). No
bypass actors.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Apply the ruleset — gated on user confirmation

The previous ten tasks make changes that can be reverted with a revert PR. Applying the ruleset is **the** governance action that affects all future contributions. The plan stops and asks before executing.

**Files:** none (action only)

- [ ] **Step 1: Verify the ruleset isn't already applied**

```bash
gh api repos/robsartin/keystone/rulesets --jq '.[].name'
```

Expected: empty output (no rulesets configured), or a list that doesn't contain `main-protection`.

If `main-protection` already exists, skip Step 2 (it's already applied). Update with `gh api -X PUT repos/robsartin/keystone/rulesets/<id> --input docs/ruleset-main.json` if the spec has changed.

- [ ] **Step 2: STOP — request explicit user confirmation**

This step requires a human decision. **Do NOT execute Step 3 without explicit user `go` for ruleset application.** State clearly to the user:

> "Phase C Task 11 is the ruleset apply step. After this, every change to `main` requires a PR + green `build` check + linear history + squash merge + a signed commit. No bypass, even for me. Confirm to proceed with `gh api -X POST repos/robsartin/keystone/rulesets --input docs/ruleset-main.json`?"

Wait for the user's response. If anything other than explicit `yes`/`go`/`apply`, abort and report back to the user without running Step 3.

- [ ] **Step 3: Apply (only after Step 2 confirmation)**

```bash
gh api -X POST repos/robsartin/keystone/rulesets --input docs/ruleset-main.json
```

Expected: JSON response with the new ruleset's `id`, `enforcement: "active"`, and the rules array.

- [ ] **Step 4: Verify by attempting a forbidden action**

Try to push directly to `main` from a throwaway commit:

```bash
git checkout main
git commit --allow-empty -m "should-be-blocked"
git push origin main 2>&1 | tail -5
# expect: "remote: GH008: Cannot create commit ..." or similar ruleset-violation message
git reset --hard HEAD^
```

Expected: push is rejected with a clear ruleset message. Local commit is then reset so the throwaway is gone.

- [ ] **Step 5: Commit nothing (Task 11 is action, not file change)**

No commit. The next task is final cleanup.

---

## Task 12: Final cleanup + close #12

**Files:**
- Modify: `README.md` (update Plan 3 status checkbox)

- [ ] **Step 1: Flip Plan 3 to done in README**

In `README.md`, update the Status section:

```markdown
## Status

- [x] Plan 1 — build skeleton + domain + application layer
- [x] Plan 2 — Spring Boot walking skeleton (POST /journal-entries, JPA + Postgres + Flyway, observability, OpenAPI gates)
- [x] Plan 3 — local infra (Docker compose), GitHub Actions CI, repo provisioning
```

- [ ] **Step 2: Add repo topics via gh**

```bash
gh repo edit robsartin/keystone --add-topic spring-boot,java,general-ledger,hexagonal-architecture,archunit,prometheus,grafana,tdd,double-entry,ports-and-adapters
```

(Idempotent; can be re-run.)

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs: Plan 3 complete — flip status checkbox

Foundation done: local infra (docker compose) + CI workflow with
required build check + repository ruleset enforcing trunk-based dev
with squash-only PRs and signed commits.

Closes #12

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C acceptance

2 commits + 1 ruleset apply. Branch: `plan-3-phase-c-governance`. PR title: "Plan 3 Phase C: governance — ADR-0009 + ruleset (closes #12)".

After merge, attempt a direct push to main:

```bash
git checkout main
git commit --allow-empty -m "should-be-blocked"
git push origin main
```

Expected: rejected with a ruleset-violation message.

---

# Plan 3 acceptance (overall)

1. `docker compose up -d --build` brings up four containers; Grafana dashboard renders.
2. `./mvnw -B clean verify -Pmutation,openapi-gate` runs in CI on every PR.
3. `docker` job pushes `ghcr.io/robsartin/keystone:{latest,sha-<sha>}` on push to main.
4. Ruleset blocks direct pushes to `main`; PRs require the `build` check.
5. ADR-0009 + ADR README updated.
6. Issue #12 closes when Phase C PR merges.
7. Signed commits enforced from day one — every PR commit must be signed by a key GitHub can verify.

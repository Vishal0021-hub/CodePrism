# Phase 00: Architecture & Repository Audit Report

**Date:** 2026-10-17  
**Branch:** `deploy/aws`  
**Auditor:** Senior DevOps & Backend Engineering Assistant  
**Target Environment:** AWS Single-Instance EC2 (Docker Compose + Nginx Single-Origin + CloudFront Default Domain)

---

## 1. Executive Summary & Architecture Overview

CodePrism is a full-stack AI repository intelligence system that integrates GitHub repositories, indexes code structure and semantic chunks using pgvector and JavaParser, and serves interactive, streaming RAG (Retrieval-Augmented Generation) chat sessions with verified code citations.

### System Topology on AWS
In accordance with budget and architectural constraints:
- **Hosting Strategy:** A single EC2 instance (e.g., `t4g.small` or `t3.small` / `t3a.small` or `t4g.medium` depending on RAM needs) running **Docker Compose**.
- **Reverse Proxy / Single Origin:** An **Nginx** reverse proxy exposed on ports 80/443:
  - Route `/` and static assets $\rightarrow$ Next.js frontend container (port 3000).
  - Route `/api/` and `/oauth2/` $\rightarrow$ Spring Boot backend container (port 8081).
- **Edge CDN & TLS:** AWS CloudFront distribution in front of the EC2 instance, using the default CloudFront SSL certificate (`*.cloudfront.net`). Zero custom domains and zero ACM costs.
- **Database:** PostgreSQL 16 + `pgvector` container running on the same EC2 instance with persistent Docker volume storage (`codeprism_pgdata`). No costly AWS RDS or NAT Gateways.
- **Monthly Cost Target:** ~$15–$25/month out of $100–$200 credit budget for 6 months.

---

## 2. Runtime & Component Version Audit

| Component | Detected Version | Configured Target | Notes |
|---|---|---|---|
| **Java Runtime** | `25.0.3 LTS` (local) | `21` (in `pom.xml`) | Docker image will use Eclipse Temurin 21 JRE for stable production runtime |
| **Spring Boot** | `4.1.1` (parent) | `4.1.1` | Uses Spring AI `2.0.1`, Spring Security 7.x, Spring Data JPA |
| **Node.js** | `v22.20.0` (local) | `^20` (in `@types/node`) | Next.js build runs cleanly on Node 20 / 22 |
| **npm** | `10.9.3` | `10.x` | Standard package manager |
| **Next.js** | `16.3.4` (Turbopack) | `16.3.4` | App Router with React 19.2.8 |
| **PostgreSQL** | `16` (`pgvector/pgvector:pg16`) | `16` | Standard Alpine-based pgvector container |
| **pgvector** | `0.7+` | Dimension: `3072` | Configured for `gemini-embedding-001` (3072 dimensions) |

---

## 3. CI/CD & Deployment Setup Audit

### GitHub Workflows & Cloud Triggers
- **`.github/workflows/`:** **Does NOT exist**. No automated GitHub Actions workflows are present.
- **Deployment Manifests:** No `render.yaml`, `Procfile`, `fly.toml`, `vercel.json`, `apprunner.yaml`, or AWS CDK/CloudFormation files exist.
- **Workflow Execution Risk:** Pushing branch `deploy/aws` to GitHub will **NOT** trigger any build, test, or automated deployment pipeline.
- **Recommended Safeguards:**
  - When CI workflows are introduced later, ensure branch filters explicitly require manual approvals or only trigger on `main` tag releases.

---

## 4. Frontend & Next.js Runtime Audit

### App Structure & Features Inspected
- **Pages:**
  - `/` (Home landing page) - Static (`○`)
  - `/login` - Static (`○`)
  - `/auth/callback` - Static (`○`)
  - `/dashboard`, `/dashboard/overview`, `/dashboard/settings` - Static (`○`)
  - `/chat/[repoId]` - Dynamic (`ƒ`)
- **Directives:** Every single page in `client/app/` begins with `"use client"`. The entire UI operates as a Client-Side Single Page Application (SPA) driven by React Query (`@tanstack/react-query`).
- **Data Fetching:** Zero `getServerSideProps`, zero React Server Actions (`"use server"`), zero route handlers (`route.ts`). All data fetching occurs via client-side `fetch` in `client/lib/api.ts` and `client/lib/stream-chat.ts`.
- **Image Optimization:** Zero `<Image />` tags from `next/image`. Standard SVG icons and CSS are used.
- **Middleware / Proxy:** File `client/proxy.ts` is recognized by Next.js 16 as middleware (`ƒ Proxy`). It checks cookie `codeprism_auth` for route protection. Client components also implement `<RequireAuth>`.

### Static Export Feasibility & Hosting Recommendation
1. **Static Export (`output: 'export'`):**
   - **Not viable without code modification.** The dynamic route `/chat/[repoId]` does not define `generateStaticParams()` (and cannot know arbitrary UUID repository IDs at build time). Attempting `output: 'export'` causes Next.js build failure on dynamic paths unless rewrites are implemented. Furthermore, Next.js middleware is ignored in static exports.
2. **Hosting Evaluation (Amplify vs S3+CloudFront vs Standalone Node on EC2):**
   - **AWS Amplify Hosting:** Overkill and expensive (~$0.01/build min + $0.15/GB bandwidth after free tier); introduces a split-origin architecture requiring cross-site cookies and CORS.
   - **S3 + CloudFront:** Extremely cheap for static files, but breaks Next.js dynamic routing unless converted to an SPA hash/catch-all or standalone server.
   - **Recommended Choice (Standalone Node Container on Single EC2):**
     Using Next.js `output: 'standalone'` inside a lightweight Docker container (`node:20-alpine`) behind Nginx on the single EC2 instance:
     - Guarantees 100% feature compatibility (middleware, dynamic routes `/chat/[repoId]`).
     - Fits squarely within the Single Origin architecture (`/` to Next.js, `/api/` to Spring Boot).
     - Keeps operating costs at **$0 additional AWS charges** since the EC2 instance is already running.

---

## 5. API Endpoints, Authentication & Security Audit

### Complete API Surface

| Method | Path | Auth Required | Description |
|---|---|---|---|
| `GET` | `/api/auth/login-url` | Public | Returns `/oauth2/authorization/github` |
| `GET` | `/api/auth/me` | Authenticated | Returns current authenticated user profile |
| `POST` | `/api/auth/logout` | Authenticated | Invalidates session and clears `CODEPRISM_SESSION` cookie |
| `GET` | `/api/repos` | Authenticated | Lists repositories (with optional sync from GitHub) |
| `GET` | `/api/repos/{id}` | Authenticated | Fetches single repository metadata |
| `POST` | `/api/repos/{id}/index` | Authenticated | Triggers asynchronous background repository indexing |
| `GET` | `/api/repos/{id}/status` | Authenticated | Returns indexing status and progress counts |
| `POST` | `/api/repos/{id}/auto-sync`| Authenticated | Toggles webhook automatic reindexing |
| `GET` | `/api/repos/{id}/find` | Authenticated | Queries symbol relationships (graph search) |
| `POST` | `/api/chat/sessions` | Authenticated | Creates a new chat session for a repository |
| `GET` | `/api/chat/sessions` | Authenticated | Lists chat sessions for a repository |
| `GET` | `/api/chat/sessions/{id}` | Authenticated | Retrieves chat message history |
| `POST` | `/api/chat/sessions/{id}/messages` | Authenticated | Sends message and returns SSE token stream |
| `POST` | `/api/webhooks/github` | Public (HMAC Verified) | Receives GitHub push events and triggers incremental reindex |
| `GET` | `/actuator/health` | Public | Spring Boot Actuator health probe (`{"status":"UP"}`) |
| `GET` | `/actuator/metrics` | Public | Micrometer application metrics |

### Authentication & Token Flow
1. **GitHub OAuth2 Flow:**
   - User initiates login via `/oauth2/authorization/github`.
   - Spring Security handles exchange at `/login/oauth2/code/github`.
   - `GithubOAuth2UserService` captures the access token with scopes (`read:user,repo`).
   - `UserService.upsertFromGitHub` encrypts the GitHub token using `TextEncryptor` (`AES-256` or PBKDF2 with salt) before persisting to table `users`.
   - Session cookie `CODEPRISM_SESSION` is created (`HttpOnly`, `SameSite=Lax`, 7-day lifespan).
   - `oauth2SuccessHandler` redirects to `${app.frontend-url}/auth/callback`.

---

## 6. AI, RAG & Indexing Architecture

### Gemini AI & Embeddings
- **Model:** `gemini-3.6-flash` (Chat) and `gemini-embedding-001` (Embeddings via OpenAI-compatible endpoint).
- **Custom Client:** `GeminiEmbeddingModel` bypasses the Spring AI OpenAI client schema issue (Gemini does not return synthetic `index` fields in vectors).
- **Embedding Dimensions:** `3072` float dimensions.
- **Rate Limit & Retry Handling:**
  - `GeminiEmbeddingModel` detects HTTP 429 and `RESOURCE_EXHAUSTED` errors.
  - Automatically parses `"Please retry in Xs"` regex and sleeps with backoff up to 5 attempts.
  - GitHub API requests use `GitHubRateLimiter` (default 50ms pause) to prevent secondary rate limits.

### Indexing Engine
- **Execution Mode:** **Completely Asynchronous (Background)**.
  - `RepoController.index` returns `202 ACCEPTED` immediately.
  - Spring task executor `indexingExecutor` (2 core, 4 max threads, 50 queue capacity) processes indexing in the background.
- **Disk Usage:** **Zero disk storage**. Repositories are fetched in-memory via GitHub Tree & Blob APIs, chunked via `CodeChunker`, graph relationships extracted via `CodeGraphExtractor`, and stored into PostgreSQL.

### Streaming Chat
- **Protocol:** **Server-Sent Events (SSE)** via HTTP POST (`produces = text/event-stream`).
- **Spring Mechanism:** `SseEmitter` with timeout configured in `RagSettings`.
- **Event Sequence:**
  1. `user_message`: Confirms user prompt persistence.
  2. `metadata`: Emits `{ sourcesCount: N }`.
  3. `token`: Streams individual text chunks.
  4. `assistant_message`: Finalized response payload with structured citation references.
  5. `done`: Emits `[DONE]` signal and closes connection.
- **Nginx Consideration:** Reverse proxy must disable proxy buffering (`proxy_buffering off;`) to allow tokens to reach the browser in real time.

---

## 7. Database & Schema State

- **Database Engine:** PostgreSQL 16 + `pgvector/pgvector:pg16`.
- **Migrations:**
  - Currently managed by **Hibernate `ddl-auto=update`**.
  - Flyway dependencies are present in `pom.xml` but commented out.
  - Schema includes: `users`, `repositories`, `chat_sessions`, `chat_messages`, `code_relationships`, and `vector_store`.
  - Init script `docker/postgres/init-extensions.sql` enables `vector`, `hstore`, and `"uuid-ossp"`.

---

## 8. Hardcoded URLs & Configuration Analysis

### Findings
1. **Frontend `getApiBaseUrl()` ([client/lib/api.ts:84](file:///e:/Projects/GitHub/DevPilot/client/lib/api.ts#L84)):**
   ```ts
   return process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8081";
   ```
   *Issue:* For single-origin deployment behind Nginx, relative URLs (`""`) must be used so that requests hit `/api/...` directly without pointing to localhost.
2. **Backend URLs ([application.properties](file:///e:/Projects/GitHub/DevPilot/backend/src/main/resources/application.properties)):**
   - `app.frontend-url=${FRONTEND_URL:http://localhost:3000}`
   - `app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:3000}`
   - `spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5433/codeprism}`
   *Note:* All backend endpoints support environment variable overrides (`FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `DB_URL`), making them production ready.

---

## 9. Verification & Test Evidence

### Backend Build & Tests
- **Command:** `mvn test`
- **Results:**
  ```text
  [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 1
  [INFO] BUILD SUCCESS
  [INFO] Total time: 13.096 s
  ```
  *(1 test intentionally skipped: `BackendApplicationTests` which requires a running PostgreSQL instance on port 5433).*

### Frontend Production Build
- **Command:** `npm run build`
- **Results:**
  ```text
  ▲ Next.js 16.3.4 (Turbopack)
  ✓ Compiled successfully in 9.6s
  ✓ Running TypeScript ... Finished in 32.4s
  ✓ Generating static pages (9/9)
  Route (app)
  ┌ ○ /
  ├ ○ /_not-found
  ├ ○ /auth/callback
  ├ ƒ /chat/[repoId]
  ├ ○ /dashboard
  ├ ○ /dashboard/overview
  ├ ○ /dashboard/settings
  └ ○ /login
  ƒ Proxy (Middleware)
  ```

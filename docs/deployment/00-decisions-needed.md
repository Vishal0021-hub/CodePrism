# Phase 00: Architecture Decisions (Approved)

All architectural decisions for the CodePrism AWS deployment have been finalized and approved by the project owner.

---

### Decision 1: EC2 Instance Sizing & Architecture [APPROVED]

* **Approved Choice:** **`t3.medium` on x86_64 architecture.**
  - **Architecture:** `x86_64` (standard Intel/AMD, no ARM cross-compilation).
  - **Sizing:** `t3.medium` (2 vCPUs, 4.0 GiB RAM, up to 5 Gbps network).
  - **Cost:** ~$0.0416/hour $\approx$ **$30.37/month** ($1.01/day).
    - Fits within the $100–$200 credit budget for ~3.5 to 6 months.
    - Plan: Measure real memory usage under active load using `docker stats`. If memory headroom allows, we will downsize to `t3.small` (~$15.18/month) to stretch credits beyond 10 months.
  - **Comprehensive Memory Breakdown (4,096 MiB Total):**
    | Component | Estimated Active RAM | Sizing / Limit Details |
    |---|---|---|
    | **Host OS & Kernel Buffers** | ~350 MiB | Amazon Linux 2023 / Ubuntu 24.04 base |
    | **PostgreSQL 16 + pgvector** | ~650–800 MiB | `shared_buffers = 256MB`, `work_mem = 64MB`, vector indexing buffers |
    | **Spring Boot (Java 21)** | ~512–768 MiB | JVM heap capped at `-Xms256m -Xmx512m` + Metaspace ~150 MiB |
    | **Next.js Standalone (Node 20)** | ~200–300 MiB | Minimal standalone runtime behind Nginx |
    | **Nginx Reverse Proxy** | ~30–50 MiB | Worker connections and client buffers |
    | **Prometheus (Scraper & TSDB)** | ~300–400 MiB | Storage retention: 7 days, scrape interval: 15s |
    | **Grafana** | ~150–200 MiB | Dashboards & alerting engine |
    | **Node Exporter** | ~25–40 MiB | Host system metrics collector |
    | **Safety Headroom & Page Cache**| ~1,200 MiB | Available for Linux OS filesystem cache |
    | **Swapfile Backup** | *2,048 MiB* | Host swapfile configured for burst protection |
    | **Total Projected Footprint** | **~2,200–2,900 MiB** | **Fits safely inside 4,096 MiB** |

---

### Decision 2: Frontend Hosting Topology [APPROVED]

* **Approved Choice:** **Next.js Standalone Node container in Docker Compose behind Nginx.**
  - Multi-stage Docker build utilizing `output: 'standalone'` in `next.config.ts`.
  - Next.js runs on internal port 3000 inside the Docker network.
  - Nginx terminates traffic and forwards `/` to `http://frontend:3000`.
  - Preserves full support for App Router dynamic routes (`/chat/[repoId]`), SSR middleware (`proxy.ts`), and client-side streaming chat.
  - Incurred AWS Cost: **$0.00 additional** (runs within the single EC2 instance).

---

### Decision 3: API Base URL Configuration & Server-Side Backend Calls [APPROVED]

* **Approved Choice:** **Default to empty string `""` for relative URLs; keep `.env.local` for local dev; configure internal backend URL for server-side code.**
  - **Client Browser Requests:**
    - In [client/lib/api.ts](file:///e:/Projects/GitHub/DevPilot/client/lib/api.ts), `getApiBaseUrl()` will default to `process.env.NEXT_PUBLIC_API_BASE_URL || ""`.
    - In production, empty string produces relative paths (e.g., `/api/repos`, `/api/chat/sessions`), ensuring requests naturally hit the single-origin CloudFront/Nginx domain without CORS issues.
    - In local development, `client/.env.local` with `NEXT_PUBLIC_API_BASE_URL=http://localhost:8081` keeps local dev working seamlessly.
  - **Server-Side Next.js Code Confirmation:**
    - **Verification:** Audited all files in `client/app/`, `client/proxy.ts`, `client/hooks/`, and `client/lib/`.
    - **Result:** **No server-side Next.js code currently calls the backend.**
      - `client/proxy.ts` only inspects browser cookies (`request.cookies.get("codeprism_auth")`) and executes client redirects; it makes no HTTP fetch calls.
      - There are zero React Server Actions (`"use server"`), zero route handlers (`route.ts`), and zero `getServerSideProps`.
      - All API and SSE calls are made directly from browser client components via React Query and `stream-chat.ts`.
    - **Future-Proofing Guard:** To support any future server-side component or middleware backend calls, an environment variable `INTERNAL_API_URL` (defaulting to `http://backend:8081` inside Docker network) will be made available.

---

### Decision 4: GitHub OAuth & Nginx Reverse Proxy Routing [APPROVED]

* **Approved Choice:** **Dedicated Production GitHub OAuth App; Nginx routes `/api/`, `/oauth2/`, and `/login/oauth2/` to Spring Boot.**
  - **Dedicated OAuth App:**
    - The owner will create a separate GitHub OAuth App in GitHub Developer Settings (e.g., `CodePrism-Prod`) dedicated to AWS.
    - Prevents breaking the local `localhost:8081` development OAuth app.
  - **Route Conflict Check:**
    - **Confirmed:** There is **no frontend route conflict**.
    - Next.js has only `/login` ([client/app/login/page.tsx](file:///e:/Projects/GitHub/DevPilot/client/app/login/page.tsx)). It has no nested routes under `/login/oauth2/` or `/oauth2/`.
    - Nginx will cleanly route `/login/oauth2/*` and `/oauth2/*` to Spring Boot without intercepting frontend `/login`.
  - **Exhaustive List of Backend Paths Reachable Through Nginx:**
    1. **`/api/*`** $\rightarrow$ `http://backend:8081/api/*`
       - `/api/auth/login-url` (Public: returns GitHub auth entry path)
       - `/api/auth/me` (Authenticated: returns current user profile)
       - `/api/auth/logout` (Authenticated: clears session cookie)
       - `/api/repos` (Authenticated: list & sync repositories)
       - `/api/repos/{id}` (Authenticated: get repo details)
       - `/api/repos/{id}/index` (Authenticated: trigger background indexing)
       - `/api/repos/{id}/status` (Authenticated: poll indexing progress)
       - `/api/repos/{id}/auto-sync` (Authenticated: toggle webhook auto-sync)
       - `/api/repos/{id}/find` (Authenticated: symbol relationship graph search)
       - `/api/chat/sessions` (Authenticated: create & list sessions)
       - `/api/chat/sessions/{id}` (Authenticated: session message history)
       - `/api/chat/sessions/{id}/messages` (Authenticated: SSE token streaming)
       - `/api/webhooks/github` (Public: HMAC SHA-256 verified push event webhook)
    2. **`/oauth2/*`** $\rightarrow$ `http://backend:8081/oauth2/*`
       - `/oauth2/authorization/github` (Initiates OAuth2 login redirect to GitHub)
    3. **`/login/oauth2/*`** $\rightarrow$ `http://backend:8081/login/oauth2/*`
       - `/login/oauth2/code/github` (GitHub OAuth authorization code callback handler)
    4. **`/actuator/health`** $\rightarrow$ `http://backend:8081/actuator/health`
       - Health check endpoint for container probes and uptime monitoring.

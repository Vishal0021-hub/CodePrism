# Phase 00: Dependency Inventory

This document catalogs all system, backend, frontend, and infrastructure dependencies for CodePrism.

---

## 1. System & Container Runtimes

| Component | Target Version | Deployment Method | Purpose |
|---|---|---|---|
| **OS Base** | Amazon Linux 2023 or Ubuntu 24.04 LTS | AWS EC2 (Single instance) | Host operating system |
| **Container Engine**| Docker Engine 26.x + Docker Compose v2 | Installed via package manager | Multi-container application orchestrator |
| **Java JRE** | OpenJDK 21 (Eclipse Temurin) | Multi-stage Docker image (`eclipse-temurin:21-jre-alpine`) | Spring Boot backend runtime |
| **Node.js** | Node.js 20.x Alpine | Multi-stage Docker image (`node:20-alpine`) | Next.js standalone frontend runtime |
| **PostgreSQL** | 16.x + `pgvector` | `pgvector/pgvector:pg16` | Relational & vector database container |
| **Web Server** | Nginx Alpine (`nginx:alpine`) | Docker container | Reverse proxy, static asset cache, SSL termination |

---

## 2. Backend Dependencies (`backend/pom.xml`)

| Group & Artifact | Version | Scope | Purpose |
|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-parent` | `4.1.1` | Parent | Core Spring Boot application framework |
| `org.springframework.boot:spring-boot-starter-webmvc` | `4.1.1` | Compile | REST API controllers & MVC dispatching |
| `org.springframework.boot:spring-boot-starter-security` | `4.1.1` | Compile | Security filters, authentication, session management |
| `org.springframework.boot:spring-boot-starter-security-oauth2-client` | `4.1.1` | Compile | GitHub OAuth2 authentication integration |
| `org.springframework.boot:spring-boot-starter-data-jpa` | `4.1.1` | Compile | JPA & Hibernate data access |
| `org.springframework.boot:spring-boot-starter-jdbc` | `4.1.1` | Compile | Direct JDBC support for batch queries and pgvector |
| `org.springframework.boot:spring-boot-starter-actuator` | `4.1.1` | Compile | Health probes (`/actuator/health`) and monitoring |
| `org.springframework.ai:spring-ai-bom` | `2.0.1` | Import | Spring AI Bill of Materials |
| `org.springframework.ai:spring-ai-starter-model-openai` | Managed (`2.0.1`) | Compile | Chat client abstraction used with Gemini endpoint |
| `org.springframework.ai:spring-ai-starter-vector-store-pgvector` | Managed (`2.0.1`) | Compile | PgVector integration and vector table initialization |
| `org.postgresql:postgresql` | Managed | Runtime | PostgreSQL JDBC driver |
| `com.github.javaparser:javaparser-core` | `3.26.3` | Compile | AST parser for extracting Java code graph relationships |
| `jakarta.validation:jakarta.validation-api` | `3.1.1` | Compile | DTO request validation constraints |
| `io.micrometer:micrometer-core` | Managed | Compile | Application metrics instrumentation |
| `org.projectlombok:lombok` | Managed | Optional | Boilerplate reduction (getters, setters, builders) |

### Backend Test Dependencies
| Group & Artifact | Scope | Purpose |
|---|---|---|
| `spring-boot-starter-data-jpa-test` | Test | Repository slice testing |
| `spring-boot-starter-security-test` | Test | Security context testing |
| `spring-boot-starter-security-oauth2-client-test` | Test | OAuth2 flow testing |
| `spring-boot-starter-webmvc-test` | Test | Controller slice testing (`MockMvc`) |

---

## 3. Frontend Dependencies (`client/package.json`)

| Package | Version | Purpose |
|---|---|---|
| `next` | `16.3.4` | Next.js App Router framework |
| `react` | `19.2.8` | Core UI library |
| `react-dom` | `19.2.8` | React DOM renderer |
| `@tanstack/react-query` | `^5.102.8` | Client-side asynchronous server state management |
| `tailwindcss` | `^4` | Utility-first CSS styling framework |
| `@tailwindcss/postcss` | `^4` | PostCSS plugin for Tailwind CSS v4 |
| `streamdown` | `^2.6.0` | Streaming markdown renderer for AI chat output |
| `@streamdown/code` | `^1.1.1` | Syntax highlighting extension for streamdown |
| `lucide-react` | `^1.41.0` | Icon set for UI elements |
| `@hugeicons/react` | `^1.1.10` | Supplementary UI icons |
| `next-themes` | `^0.4.6` | Dark/light theme provider |
| `clsx` & `tailwind-merge` | `^2.1.1` / `^3.6.0` | Conditional CSS class merging utility |
| `recharts` | `^3.8.0` | Analytics and dashboard charts |
| `react-resizable-panels`| `^4.12.3` | Split panes for chat and code citation view |
| `cmdk` | `^1.1.1` | Command menu / symbol search palette |
| `date-fns` | `^4.4.0` | Date formatting utilities |

---

## 4. External Services & Cloud APIs

| Service | Protocol / Format | Purpose | Authentication |
|---|---|---|---|
| **Google Gemini API** | HTTPS REST / SSE | Embeddings (`gemini-embedding-001`) & Chat Completion (`gemini-3.6-flash`) | API Key (`GEMINI_API_KEY`) |
| **GitHub REST API** | HTTPS REST (`api.github.com`) | Repository listing, tree fetching, blob content retrieval | OAuth User Token (`read:user,repo`) |
| **GitHub OAuth Service** | OAuth2 Authorization Code | User login and access delegation | Client ID & Client Secret |
| **AWS CloudFront** | HTTPS (`*.cloudfront.net`) | Global CDN caching & free TLS certificate | IAM / AWS Console |

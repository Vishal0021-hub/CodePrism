# 🚀 CODEPRISM

<div align="center">

![CodePrism](https://img.shields.io/badge/CodePrism-AI%20Code%20Navigator-blueviolet?style=for-the-badge&logo=github)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?style=for-the-badge&logo=springboot)
![Next.js](https://img.shields.io/badge/Next.js-16-black?style=for-the-badge&logo=next.js)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?style=for-the-badge&logo=postgresql)
![Gemini](https://img.shields.io/badge/Google_Gemini-AI-4285F4?style=for-the-badge&logo=google)

**An AI-powered code navigation assistant that indexes your GitHub repositories and lets you chat with your codebase using RAG (Retrieval Augmented Generation).**

[Features](#-features) • [Architecture](#-architecture) • [Getting Started](#-getting-started) • [Tech Stack](#-tech-stack) • [API Reference](#-api-reference) • [Documentation](file:///e:/Projects/GitHub/CodePrism/TECHNICAL_DOCS.md) • [Architecture Guide (PDF)](file:///e:/Projects/GitHub/CodePrism/docs/CodePrism_Architecture_and_Workflow.pdf) • [Git Roadmap](file:///e:/Projects/GitHub/CodePrism/GIT_COMMITS.md) • [License](#-license)

</div>

---

## ✨ Key Features & Capabilities

- **🔐 GitHub OAuth2 Authentication with AES-256 Encryption**
  - Seamless one-click sign-in via GitHub OAuth2 (`read:user`, `repo` scopes).
  - User OAuth access tokens are securely **AES-256 encrypted at rest** using configurable PBKDF2 keys and salt before database persistence.

- **📦 Automated Repository Discovery & Synchronization**
  - Automatically fetches and synchronizes public, private, organization, and collaborative repositories from GitHub.
  - One-click manual re-syncing with live database status updates.

- **🧠 Intelligent AST Code Filtering & Ingestion**
  - Smart recursive file tree traversal (`/git/trees/{branch}?recursive=1`).
  - Automated exclusion of vendor directories (`node_modules`, `dist`, `build`, `.git`), binary files, lockfiles, and assets.
  - Enforces a 100 KB safety file limit with automated programming language syntax detection.

- **🧩 Context-Aware Semantic Code Chunking**
  - Built with Spring AI `TokenTextSplitter` splitting code into optimal ~200 token segments.
  - Automatically prepends file path headers (`// File: path/to/file.ext`) to every chunk, ensuring LLM understanding remains grounded even when functions are split across boundaries.
  - Rich metadata tagging per chunk: `repoId`, `filePath`, `language`, and `chunkIndex`.

- **💾 PgVector Similarity Store (3072 Dimensions)**
  - Embedded using Google Gemini 3072-dimensional vector models (`gemini-embedding-001`).
  - High-performance cosine distance (`<=>`) vector similarity indexing directly in PostgreSQL via the `pgvector` extension.
  - Strict tenant and repository isolation ensuring searches only query the target repository.

- **💬 Real-Time RAG Chat with Live Code Citations**
  - Ask natural language questions about architecture, algorithms, dependencies, and functions across the codebase.
  - Dynamic system prompt construction augmenting retrieved top-K code snippets into the LLM context window.
  - Interactive **citation badges** linking directly to the cited source files and line ranges.

- **⚡ Low-Latency Server-Sent Events (SSE) Streaming**
  - Instant token-by-token response streaming via `SseEmitter` (`event: token`, `event: citations`, `event: assistant_message`, `event: done`).
  - Smooth typing experience on the frontend with zero blocking or page reloads.

- **🛡️ Adaptive Rate Limiting & 429 Exponential Backoff**
  - Integrated delay controls to prevent triggering GitHub REST API rate limits.
  - Automatic error inspection and retry backoff for Gemini API quota limits (429 Too Many Requests).

- **📊 Live Indexing Dashboard & Modern UI**
  - Real-time progress tracking displaying indexed file count, chunk count, and status badges (`PENDING` → `INDEXING` → `READY` / `FAILED`).
  - Built with Next.js 16 (Turbopack), React 19, Tailwind CSS v4, Lucide icons, and full dark/light theme support.

---

## 🏗 Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      FRONTEND (Next.js 16)               │
│  React 19 · TanStack Query · Tailwind CSS · SSE Client   │
│  Port: 3000                                              │
└──────────────────────┬───────────────────────────────────┘
                       │  HTTP / SSE (proxied via proxy.ts)
                       ▼
┌──────────────────────────────────────────────────────────┐
│                   BACKEND (Spring Boot 4.1.1)            │
│  Java 21 · Spring AI 2.0.1 · Spring Security · JPA      │
│  Port: 8081                                              │
│                                                          │
│  ┌────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │ Auth/OAuth │  │ Repo Sync &    │  │ RAG Chat       │  │
│  │ Controller │  │ Indexing       │  │ Pipeline       │  │
│  └────────────┘  └────────────────┘  └────────────────┘  │
└──────────┬───────────────┬────────────────┬──────────────┘
           │               │                │
     ┌─────▼─────┐  ┌─────▼──────┐  ┌──────▼──────┐
     │  GitHub    │  │ PostgreSQL │  │ Gemini API  │
     │  API       │  │ + pgvector │  │ (OpenAI     │
     │            │  │ Port: 5433 │  │ compatible) │
     └───────────┘  └────────────┘  └─────────────┘
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **Java** | 21+ | Backend runtime |
| **Maven** | 3.9+ | Build tool (wrapper included) |
| **Node.js** | 18+ | Frontend runtime |
| **Docker** | Latest | PostgreSQL with pgvector |
| **Git** | Latest | Version control |

### 1. Clone the Repository

```bash
git clone https://github.com/Vishal0021-hub/CodePrism.git
cd CodePrism
```

### 2. Start PostgreSQL (Docker)

```bash
docker compose up -d
```

This spins up `pgvector/pgvector:pg16` on port **5433** with the `vector` extension pre-installed.

### 3. Configure Environment

**Backend** — edit `backend/src/main/resources/application.properties`:

```properties
# Gemini API Key (free tier works!)
spring.ai.openai.api-key=YOUR_GEMINI_API_KEY

# GitHub OAuth2 (create at github.com/settings/developers)
spring.security.oauth2.client.registration.github.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.github.client-secret=YOUR_CLIENT_SECRET
```

**Frontend** — create `client/.env.local`:

```env
NEXT_PUBLIC_API_URL=http://localhost:8081
```

### 4. Start the Backend

```bash
cd backend
./mvnw spring-boot:run
```

Backend starts on **http://localhost:8081**.

### 5. Start the Frontend

```bash
cd client
npm install
npm run dev
```

Frontend starts on **http://localhost:3000**.

### 6. Use CodePrism

1. Open **http://localhost:3000**
2. Click **"Sign in with GitHub"**
3. Your repositories will sync automatically
4. Click **"Index"** on any repository
5. Once indexing completes (status: **Ready**), click to start chatting!

---

## 🛠 Tech Stack

### Backend
| Technology | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 4.1.1 | Application framework |
| Spring AI | 2.0.1 | AI/LLM integration layer |
| Spring Security | 7.x | OAuth2 + session management |
| Spring Data JPA | 7.x | Database ORM |
| PostgreSQL | 16 | Primary database |
| pgvector | Latest | Vector similarity search |
| Lombok | Latest | Boilerplate reduction |
| Google Gemini | gemini-3.6-flash | Chat completions |
| Gemini Embeddings | gemini-embedding-001 | 3072-dim code embeddings |

### Frontend
| Technology | Version | Purpose |
|-----------|---------|---------|
| Next.js | 16.3.4 | React framework (Turbopack) |
| React | 19.2.8 | UI library |
| TanStack Query | 5.x | Server state management |
| Tailwind CSS | 4.x | Styling |
| Lucide React | 1.x | Icons |
| Streamdown | 1.x | Markdown + code rendering |
| next-themes | 0.4.6 | Dark/light mode |

### Infrastructure
| Technology | Purpose |
|-----------|---------|
| Docker Compose | Container orchestration |
| pgvector/pgvector:pg16 | PostgreSQL with vector support |

---

## 📡 API Reference

### Authentication
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/auth/login-url` | Returns GitHub OAuth2 login URL |
| `GET` | `/api/auth/me` | Returns current authenticated user |
| `POST` | `/api/auth/logout` | Ends session |

### Repositories
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/repos` | Sync from GitHub & list all repos |
| `POST` | `/api/repos/{id}/index` | Start indexing a repository |
| `GET` | `/api/repos/{id}/status` | Get indexing progress |

### Chat
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/chat/sessions` | Create a new chat session |
| `GET` | `/api/chat/sessions?repositoryId={id}` | List sessions for a repo |
| `GET` | `/api/chat/sessions/{id}` | Get session message history |
| `POST` | `/api/chat/sessions/{id}/messages` | Stream an AI reply with code citations (SSE) |

---

## 📁 Project Structure

```
CodePrism/
├── backend/                          # Spring Boot application
│   └── src/main/java/devPilot/backend/
│       ├── config/                   # Security, CORS, crypto configs
│       ├── controller/               # REST controllers
│       ├── dto/                      # Request/response DTOs
│       ├── entity/                   # JPA entities
│       ├── exceptions/               # Custom exceptions & handlers
│       ├── repository/               # Spring Data repositories
│       ├── security/                 # OAuth2 user service & auth
│       └── services/
│           ├── ai/                   # RAG pipeline components
│           │   ├── GeminiEmbeddingModel.java
│           │   ├── CodeContextRetriever.java
│           │   ├── ChatPromptBuilder.java
│           │   ├── ChatStreamHandler.java
│           │   └── CitationMapper.java
│           ├── github/               # GitHub API client
│           └── indexing/             # Code chunking & vectorization
│               ├── IndexingService.java
│               ├── CodeChunker.java
│               └── CodeFileFilter.java
├── client/                           # Next.js frontend
│   ├── app/                          # Pages & routing
│   │   ├── auth/                     # OAuth callback
│   │   ├── chat/                     # Chat interface
│   │   ├── dashboard/                # Repository dashboard
│   │   └── login/                    # Login page
│   ├── components/                   # React components
│   │   ├── chat/                     # Chat UI components
│   │   ├── dashboard/                # Dashboard components
│   │   ├── layouts/                  # App shell & sidebar
│   │   └── ui/                       # Base UI components
│   ├── hooks/                        # Custom React hooks
│   └── lib/                          # Utilities & API client
├── docker/                           # Docker configurations
│   └── postgres/init-extensions.sql  # pgvector init
└── docker-compose.yml                # Container orchestration
```

---

## 🔒 Security

- GitHub OAuth2 tokens are **AES-256 encrypted** before storage using a configurable encryption key.
- All `/api/**` endpoints require authentication (session-based).
- CORS is configured to allow only the frontend origin.
- Session cookies are `HttpOnly` and `SameSite=Lax`.

---

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License.

---


</div>

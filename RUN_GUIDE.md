# 🚀 CodePrism - Application Run & Setup Guide

This guide details how to configure, start, and verify both the backend and frontend services of CodePrism across different operating systems.

---

## 📋 System Architecture & Port Mapping

| Service | Port | Description |
|---|---|---|
| **PostgreSQL + pgvector** | `5433` | Vector database & metadata store |
| **Backend (Spring Boot)** | `8081` | Core REST APIs, OAuth2, and RAG pipeline |
| **Frontend (Next.js)** | `3000` | Web application UI |

---

## ⚙️ Step 1: Start the Database

Ensure Docker is running, then execute from the project root:

```bash
docker compose up -d
```

Verify the PostgreSQL container is healthy and listening on port `5433`:
```bash
docker compose ps
```

---

## 🔐 Step 2: Configure Environment Variables

To keep your credentials secure from public git leaks, never commit your raw keys to repository files. Use environment variables instead.

### Required Credentials:
- `GEMINI_API_KEY`: Google Gemini API Key (from [Google AI Studio](https://aistudio.google.com/app/apikey))
- `GITHUB_CLIENT_ID`: GitHub OAuth App Client ID (from GitHub Developer Settings)
- `GITHUB_CLIENT_SECRET`: GitHub OAuth App Client Secret

### Option A: Set Permanently on Windows (Recommended for Windows users)
Run this once in PowerShell so all future terminal sessions and IDEs retain the credentials without re-entering them:

```powershell
[System.Environment]::SetEnvironmentVariable('GEMINI_API_KEY', 'your_gemini_api_key', 'User')
[System.Environment]::SetEnvironmentVariable('GITHUB_CLIENT_ID', 'your_github_client_id', 'User')
[System.Environment]::SetEnvironmentVariable('GITHUB_CLIENT_SECRET', 'your_github_client_secret', 'User')
```
*(After running these commands, restart your terminal or IDE to apply the new environment variables).*

### Option B: Set per Terminal Session

**Windows PowerShell:**
```powershell
$env:GEMINI_API_KEY="your_gemini_api_key"
$env:GITHUB_CLIENT_ID="your_github_client_id"
$env:GITHUB_CLIENT_SECRET="your_github_client_secret"
```

**Linux / macOS (Bash / Zsh):**
```bash
export GEMINI_API_KEY="your_gemini_api_key"
export GITHUB_CLIENT_ID="your_github_client_id"
export GITHUB_CLIENT_SECRET="your_github_client_secret"
```

### Option C: Configure in IDE (IntelliJ IDEA / VS Code)
- **IntelliJ IDEA**: Run Configuration → Edit Configurations → Environment variables → Add `GEMINI_API_KEY`, `GITHUB_CLIENT_ID`, and `GITHUB_CLIENT_SECRET`.
- **VS Code**: Add to `.vscode/launch.json` under `env`.

---

## 🖥️ Step 3: Start the Backend (Spring Boot)

Navigate to the `backend` directory and run the Maven wrapper:

### Windows (PowerShell / Command Prompt):
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

### Linux / macOS:
```bash
cd backend
./mvnw spring-boot:run
```

### Verify Backend Startup:
Once initialized, the backend will listen on **`http://localhost:8081`**. You can verify via:
- Health check:
  ```bash
  curl http://localhost:8081/actuator/health
  ```
  Expected output: `{"status":"UP", ...}`
- OAuth authorization URL:
  ```bash
  curl http://localhost:8081/api/auth/login-url
  ```

---

## 🌐 Step 4: Start the Frontend (Next.js)

In a new terminal window, navigate to the `client` directory:

```bash
cd client
npm install
npm run dev
```

The frontend will start on **`http://localhost:3000`**.

---

## 🛠️ Troubleshooting

### 1. `Client id of registration 'github' must not be empty`
- **Cause**: Spring Security requires a non-empty OAuth2 client ID. This error occurs when `GITHUB_CLIENT_ID` is not present in the current terminal environment.
- **Solution**: Set the environment variable using Option A or Option B in Step 2 before starting the backend.

### 2. `Connection to localhost:5433 refused`
- **Cause**: The PostgreSQL Docker container is not running.
- **Solution**: Run `docker compose up -d` in the root folder.

### 3. Port already in use (`Port 8081 was already in use`)
- **Cause**: A previous backend instance is still running in the background.
- **Solution**: Terminate the existing Java process using Task Manager (Windows) or:
  ```powershell
  Stop-Process -Name java -Force
  ```

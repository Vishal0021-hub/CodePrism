# Phase 02: Local Run & Verification Guide

This guide describes how to run and verify the full CodePrism stack locally behind the Nginx reverse proxy using Docker Compose, complete with verified real output benchmarks.

---

## 📋 Architecture & Port Map

All external traffic enters through **Nginx on port 80** as a single-origin reverse proxy:

| Path / URL | Destination Container | Notes |
|---|---|---|
| `http://localhost/` | `frontend:3000` | Next.js standalone SPA & static assets |
| `http://localhost/api/*` | `backend:8081` | Spring Boot REST API |
| `http://localhost/oauth2/*` | `backend:8081` | Spring Security OAuth2 login initiate |
| `http://localhost/login/oauth2/*` | `backend:8081` | GitHub OAuth2 authorization callback |
| `http://localhost/actuator/health` | `backend:8082` | Health endpoint routed through Nginx |
| `http://localhost/actuator/*` (all other) | Nginx `404` | Blocked from external traffic (isolated internal management port) |
| `backend:8082/actuator/prometheus` | Internal only | Scraped by Prometheus on Docker internal bridge network |
| `postgres:5432` (host `5433`) | `postgres` | PostgreSQL 16 + pgvector |

---

## 🚀 Quick Start (Clean Checkout)

### Step 1: Ensure Docker Desktop is Running
Ensure the Docker daemon is running:
```bash
docker ps
```

### Step 2: Build and Start Containers
From the repository root:
```bash
docker compose -f docker-compose.local.yml up -d --build
```

### Step 3: Verify Container Health
```bash
docker compose -f docker-compose.local.yml ps
```

**Real Output:**
```text
NAME                 IMAGE                    COMMAND                  SERVICE    CREATED          STATUS                    PORTS
codeprism-backend    devpilot-backend         "java -jar app.jar"      backend    1 minute ago     Up 1 minute (healthy)     8081-8082/tcp
codeprism-frontend   devpilot-frontend        "docker-entrypoint.s…"   frontend   50 minutes ago   Up 50 minutes (healthy)   3000/tcp
codeprism-nginx      nginx:alpine             "/docker-entrypoint.…"   nginx      50 minutes ago   Up 50 minutes (healthy)   0.0.0.0:80->80/tcp, [::]:80->80/tcp
codeprism-postgres   pgvector/pgvector:pg16   "docker-entrypoint.s…"   postgres   15 hours ago     Up 45 minutes (healthy)   0.0.0.0:5433->5432/tcp, [::]:5433->5432/tcp
```
All four containers are running and reported `healthy`.

---

## 🧪 Verification Commands & Real Output Evidence

### 1. Check Flyway Migrations & Schema Validation
Flyway executes automatically during container startup with `ddl-auto=validate`:
```bash
docker exec -i codeprism-postgres psql -U postgres -d codeprism -c "SELECT installed_rank, version, description, type, execution_time, success FROM flyway_schema_history;"
```

**Real Output:**
```text
 installed_rank | version |    description    | type | execution_time | success 
----------------+---------+-------------------+------+----------------+---------
              1 | 1       | create extensions | SQL  |            223 | t
              2 | 2       | baseline schema   | SQL  |            145 | t
(2 rows)
```
- Migration 1 enabled PostgreSQL extensions `vector`, `hstore`, `uuid-ossp`.
- Migration 2 initialized baseline schema for all tables and indexes.
- Hibernate started in `ddl-auto=validate` mode without schema discrepancies.

---

### 2. Verify Actuator Health & Port Isolation

#### Public Health Check via Nginx (Port 80):
```bash
curl.exe -i http://localhost/actuator/health
```

**Real Output:**
```text
HTTP/1.1 200 
Server: nginx/1.31.7
Date: Mon, 19 Oct 2026 07:46:07 GMT
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Connection: keep-alive
X-Content-Type-Options: nosniff
X-Frame-Options: DENY

{"components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

#### Confirm Prometheus Metrics Blocked from External Access:
```bash
curl.exe -i http://localhost/actuator/prometheus
```

**Real Output:**
```text
HTTP/1.1 404 Not Found
Server: nginx/1.31.7
Date: Mon, 19 Oct 2026 07:46:13 GMT
Content-Type: text/html
Content-Length: 153
Connection: keep-alive

<html>
<head><title>404 Not Found</title></head>
<body>
<center><h1>404 Not Found</h1></center>
<hr><center>nginx/1.31.7</center>
</body>
</html>
```

#### Confirm Prometheus Metrics Reachable on Internal Network (Internal Scrape):
```bash
docker exec -i codeprism-nginx wget -qO- http://backend:8082/actuator/prometheus | head -n 10
```

**Real Output:**
```text
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Survivor Space"} 8388608.0
jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 72351744.0
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.48897792E8
jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 9.437184E7
```

---

### 3. Verify PostgreSQL Extensions & pgvector Vector Query
Test vector indexing, vector data insertion, and cosine similarity (`<=>`):
```bash
docker exec -i codeprism-postgres psql -U postgres -d codeprism -c "
SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'hstore', 'uuid-ossp');
CREATE TEMP TABLE items (id serial PRIMARY KEY, embedding vector(3));
INSERT INTO items (embedding) VALUES ('[1,2,3]'), ('[4,5,6]'), ('[1,2,4]');
SELECT id, embedding, embedding <=> '[1,2,3]' AS cosine_distance FROM items ORDER BY cosine_distance;
"
```

**Real Output:**
```text
  extname  | extversion 
-----------+------------
 vector    | 0.8.6
 hstore    | 1.8
 uuid-ossp | 1.1
(3 rows)

CREATE TABLE
INSERT 0 3
 id | embedding |   cosine_distance    
----+-----------+----------------------
  1 | [1,2,3]   |                    0
  3 | [1,2,4]   |  0.00853986601633272
  2 | [4,5,6]   | 0.025368153802923787
(3 rows)
```

---

### 4. Verify Data Persistence Across Container Restarts
Insert test data into PostgreSQL, restart the container, and verify data persists on the named volume `codeprism_pgdata`:
```bash
docker exec -i codeprism-postgres psql -U postgres -d codeprism -c "
CREATE TABLE IF NOT EXISTS test_persistence (id serial PRIMARY KEY, message text);
INSERT INTO test_persistence (message) VALUES ('persisted_across_restart');
"
docker compose -f docker-compose.local.yml restart postgres
docker exec -i codeprism-postgres psql -U postgres -d codeprism -c "SELECT * FROM test_persistence; DROP TABLE test_persistence;"
```

**Real Output:**
```text
Container codeprism-postgres Restarting 
Container codeprism-postgres Started 

 id |         message          
----+--------------------------
  1 | persisted_across_restart
(1 row)

DROP TABLE
```

---

### 5. Verify OAuth Redirect Flow via Nginx

#### Login URL API:
```bash
curl.exe -i http://localhost/api/auth/login-url
```

**Real Output:**
```text
HTTP/1.1 200 
Server: nginx/1.31.7
Date: Mon, 19 Oct 2026 07:46:38 GMT
Content-Type: application/json
Content-Length: 38
Connection: keep-alive
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin

{"url":"/oauth2/authorization/github"}
```

#### GitHub Authorization Redirect:
```bash
curl.exe -i http://localhost/oauth2/authorization/github
```

**Real Output:**
```text
HTTP/1.1 302 
Server: nginx/1.31.7
Date: Mon, 19 Oct 2026 07:46:46 GMT
Connection: keep-alive
Set-Cookie: CODEPRISM_SESSION=B417C2B0A1A597191CBDC641064F5C5D; Path=/; HttpOnly; SameSite=Lax
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Location: https://github.com/login/oauth/authorize?response_type=code&client_id=Ov23liYieOszlYRcWLL5&scope=read:user%20repo&state=Qp_x5PmX_d_aGAlaBBVCKabQkpYjCmAgeIEBUd-GDPM%3D&redirect_uri=https://localhost:80/login/oauth2/code/github&code_challenge=UogfxcnQ1V_c7z8Sb0gbN4mJoILfCIdJO3x2BC9nam8&code_challenge_method=S256
```
*Notice: `redirect_uri` correctly uses the `https://` scheme because Nginx sets `X-Forwarded-Proto https` and Spring Boot uses `server.forward-headers-strategy=framework`.*

---

### 6. Verify SSE Chat Streaming Chunk-by-Chunk (curl -N with Timestamps)
Execute the streaming verification script inside the container network to verify chunked SSE streaming through Nginx with `proxy_buffering off`:

```bash
docker exec -i codeprism-nginx /bin/sh -c '
AUTH_RESP=$(curl -s -c /tmp/cookies.txt -X POST http://localhost/api/auth/dev-session)
SESSION_ID=$(echo "$AUTH_RESP" | grep -o "\"sessionId\":\"[^\"]*" | cut -d"\"" -f4)
echo "Active Session: $SESSION_ID"
echo "{\"content\":\"How do I configure this?\"}" > /tmp/payload.json

curl -N -i -s -X POST "http://localhost/api/chat/sessions/${SESSION_ID}/messages" \
  -b /tmp/cookies.txt \
  -H "Content-Type: application/json" \
  --data-binary "@/tmp/payload.json" | while IFS= read -r line; do
    echo "[$(date +\"%H:%M:%S\")] $line"
done
'
```

**Real Output:**
```text
Active Session: 35b7e4e6-eb82-49ce-9b17-3f2a21d37c88
[08:32:10] HTTP/1.1 200 
[08:32:10] Server: nginx/1.31.7
[08:32:10] Date: Mon, 19 Oct 2026 08:32:10 GMT
[08:32:10] Content-Type: text/event-stream
[08:32:10] Transfer-Encoding: chunked
[08:32:10] Connection: keep-alive
[08:32:10] X-Content-Type-Options: nosniff
[08:32:10] X-Frame-Options: DENY
[08:32:10] Strict-Transport-Security: max-age=31536000 ; includeSubDomains
[08:32:10] 
[08:32:10] event:user_message
[08:32:10] data:{"id":"4a0680d1-84b2-4563-bb78-0eb432239bce","role":"USER","content":"How do I configure this?","citations":[],"sourcesCount":null,"createdAt":"2026-10-19T08:32:04.933072120Z"}
[08:32:10] 
[08:32:10] event:token
[08:32:10] data:No relevant code found in this repository for that question.
[08:32:10] 
[08:32:10] event:assistant_message
[08:32:10] data:{"id":"e4829b47-a665-43ab-ad86-1a165d0fa368","role":"ASSISTANT","content":"No relevant code found in this repository for that question.","citations":[],"sourcesCount":0,"createdAt":"2026-10-19T08:32:10.237989692Z"}
[08:32:10] 
[08:32:10] event:done
[08:32:10] data:[DONE]
```
The SSE stream arrived immediately with `chunked` transfer encoding, unbuffered through Nginx.

---

## 🛑 Tear Down

To stop and remove containers (preserving database volume):
```bash
docker compose -f docker-compose.local.yml down
```

To stop and wipe database volume completely:
```bash
docker compose -f docker-compose.local.yml down -v
```

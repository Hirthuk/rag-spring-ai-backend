# FinsightAI — Complete AWS Infrastructure Reference

This document covers every AWS service used in the FinsightAI production deployment: what it is, why we use it, how it works in this application, and how it connects to every other service.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [VPC — Virtual Private Cloud](#2-vpc--virtual-private-cloud)
3. [Subnets](#3-subnets)
4. [Internet Gateway](#4-internet-gateway)
5. [Route Tables](#5-route-tables)
6. [Security Groups](#6-security-groups)
7. [S3 — Simple Storage Service](#7-s3--simple-storage-service)
8. [CloudFront — CDN](#8-cloudfront--cdn)
9. [ACM — Certificate Manager](#9-acm--certificate-manager)
10. [Route 53 — DNS](#10-route-53--dns)
11. [ALB — Application Load Balancer](#11-alb--application-load-balancer)
12. [ECR — Elastic Container Registry](#12-ecr--elastic-container-registry)
13. [ECS — Elastic Container Service (Fargate)](#13-ecs--elastic-container-service-fargate)
14. [EFS — Elastic File System](#14-efs--elastic-file-system)
15. [AWS Bedrock](#15-aws-bedrock)
16. [Amazon Cognito](#16-amazon-cognito)
17. [AWS Pinpoint](#17-aws-pinpoint)
18. [Secrets Manager](#18-secrets-manager)
19. [IAM Roles](#19-iam-roles)
20. [CloudWatch Logs](#20-cloudwatch-logs)
21. [Service Connection Map](#21-service-connection-map)
22. [Traffic Flow — End to End](#22-traffic-flow--end-to-end)

---

## 1. Architecture Overview

```
User Browser
    │
    ▼
CloudFront (d1h0nwxur4kyhe.cloudfront.net)
    │  serves React SPA from S3
    │
    ▼
S3 (finsightdestinationbucket)
    │  static frontend assets (HTML, JS, CSS)
    │
    │  [API calls from browser]
    ▼
Route 53 (finsight-lb-prod.app)
    │  A-alias → ALB
    ▼
ALB (finsight-alb)
    │  HTTPS :443 → ECS Task
    │  HTTP  :80  → redirect to HTTPS
    ▼
VPC: finsight-ai-vpc (10.0.0.0/16)
    │
    ├── Subnet AZ-1 (10.0.2.0/24 / us-east-1a)
    └── Subnet AZ-2 (10.0.1.0/24 / us-east-1b)
            │
            ▼
        ECS Fargate Task (finsight-task:16)
            ├── Container: finsight-backend  (Spring Boot :8080)
            │       ├── → AWS Bedrock      (LLM + Embeddings)
            │       ├── → S3               (document storage + hash tracking)
            │       ├── → Cognito          (auth verification)
            │       ├── → Secrets Manager  (runtime secrets)
            │       └── → Pinpoint         (welcome emails)
            │
            └── Container: chromadb        (:8000, internal only)
                    └── → EFS              (persistent vector data)
```

---

## 2. VPC — Virtual Private Cloud

**What it is:** A VPC is a logically isolated section of the AWS cloud where all your resources live. It defines the private IP address space, controls routing, and acts as a network boundary. Nothing inside the VPC is reachable from the internet unless you explicitly open it.

**Why we use it:** Every ECS task, ALB, and EFS mount target lives inside this VPC. It ensures the backend containers and database are isolated from other tenants on AWS.

**Our configuration:**

| Property | Value |
|---|---|
| Name | finsight-ai-vpc |
| VPC ID | vpc-0d327e156b5250ff1 |
| CIDR Block | 10.0.0.0/16 |
| Region | us-east-1 |

The `/16` block gives us 65,536 private IP addresses (10.0.0.1 – 10.0.255.254). We currently use two `/24` subnets (512 IPs total), leaving room to add more subnets if the application grows.

**How it connects:** Every other network resource in this project (subnets, Internet Gateway, route tables, security groups, ALB, ECS tasks, EFS mount targets) belongs to this VPC.

---

## 3. Subnets

**What they are:** Subnets divide the VPC CIDR block into smaller ranges. Each subnet is bound to a single Availability Zone (AZ). Resources launched into a subnet inherit that AZ. Placing resources in multiple AZs provides fault tolerance — if one AZ goes down, the other keeps serving traffic.

**Why we use two:** The ALB requires at least two subnets in different AZs to work. ECS tasks land in whichever subnet has capacity.

**Our configuration:**

| Name | Subnet ID | CIDR | AZ | Role |
|---|---|---|---|---|
| finsight-subnet-az1 | subnet-099421d4c04934d3f | 10.0.2.0/24 | us-east-1a | ALB + ECS |
| finsight-subnet-az2 | subnet-088fb9aa33a0719fe | 10.0.1.0/24 | us-east-1b | ALB + ECS |

Both subnets are associated with the same route table that has a default route pointing to the Internet Gateway. This is what makes them "public-capable" — ECS tasks get public IPs so they can pull images from ECR and call Bedrock, Cognito, and other AWS APIs over the internet.

**EFS mount targets** are deployed inside both subnets so that whichever AZ the ECS task lands in, it has a local NFS endpoint to attach the persistent volume from.

---

## 4. Internet Gateway

**What it is:** The Internet Gateway (IGW) is the bridge between the VPC and the public internet. It performs Network Address Translation (NAT) — mapping private IP addresses inside the VPC to public IPs when traffic leaves, and routing inbound responses back to the right private IP.

**Why we use it:** Without the IGW, resources inside the VPC have no internet access at all. Our ECS tasks need internet access to:
- Pull Docker images from ECR (public registry endpoint)
- Call AWS Bedrock APIs
- Call AWS Cognito APIs
- Call AWS Pinpoint APIs
- Reach Secrets Manager

**Our configuration:**

| Property | Value |
|---|---|
| Name | finsight-igw |
| ID | igw-012a38eb008996a1c |
| Attached to | vpc-0d327e156b5250ff1 |

**How it connects:** The IGW is referenced in the route table as the target for `0.0.0.0/0` (all internet-bound traffic). Without this route, ECS tasks could not reach any external AWS service.

---

## 5. Route Tables

**What they are:** A route table is a set of rules that determines where network traffic is directed. Every subnet in the VPC is associated with a route table. When a packet leaves a resource, the VPC looks up the destination IP in the route table and forwards it to the matching target.

**Our configuration:**

| Route Table ID | Destination | Target | Meaning |
|---|---|---|---|
| rtb-082ce42e715c20f6a | 10.0.0.0/16 | local | Internal VPC traffic stays inside |
| rtb-082ce42e715c20f6a | 0.0.0.0/0 | igw-012a38eb008996a1c | All other traffic exits through IGW |

This single route table is associated with both `finsight-subnet-az1` and `finsight-subnet-az2`.

**How it connects:** When an ECS task calls `bedrock.us-east-1.amazonaws.com`, the route table sends that packet to the IGW. When another ECS task calls the chromadb container on `localhost:8000` (same task network namespace), it stays within the task — no route table involved.

---

## 6. Security Groups

**What they are:** Security groups are stateful virtual firewalls attached to resources (ALB, ECS tasks, EFS). They define which inbound traffic is allowed and all outbound traffic is allowed by default. "Stateful" means if you allow an inbound connection, the response traffic is automatically allowed out without a separate rule.

**Why we use them:** They enforce the principle of least privilege at the network level. The backend port (8080) is never exposed to the internet — only the ALB can reach it.

**Our security groups:**

### finsight-alb-sg (sg-0637b0bca26b45bb1)
Attached to: ALB

| Direction | Protocol | Port | Source | Reason |
|---|---|---|---|---|
| Inbound | TCP | 80 | 0.0.0.0/0 | Accept HTTP from internet (redirected to HTTPS) |
| Inbound | TCP | 443 | 0.0.0.0/0 | Accept HTTPS from internet |
| Outbound | All | All | 0.0.0.0/0 | ALB forwards to ECS tasks |

### finsight-ecs-sg (sg-096e50971cf94d631)
Attached to: ECS Fargate tasks

| Direction | Protocol | Port | Source | Reason |
|---|---|---|---|---|
| Inbound | TCP | 8080 | sg-0637b0bca26b45bb1 | Only the ALB can reach the app — no direct internet access |
| Outbound | All | All | 0.0.0.0/0 | App calls Bedrock, Cognito, S3, Pinpoint, etc. |

This is a security group reference (not a CIDR) — meaning only traffic that originates from a resource attached to `finsight-alb-sg` is allowed in on port 8080. This is tighter than using a CIDR range.

**How it connects:** The chain is: Internet → ALB (via `finsight-alb-sg`) → ECS task (via `finsight-ecs-sg`). The ECS task port is never directly reachable from the internet.

---

## 7. S3 — Simple Storage Service

**What it is:** S3 is AWS's object storage service. It stores files (objects) in named containers called buckets. Objects can be any file type and any size. S3 is infinitely scalable, highly durable (11 nines), and can serve as both a private data store and a public static web host.

**Why we use it:** We use S3 for two completely different purposes in this project.

**Our buckets:**

### Bucket 1: finsight-ai-project (Backend Data)
Region: us-east-1

This bucket is the backend's data layer. It stores:

| Object/Prefix | Purpose |
|---|---|
| `TCS_Combined_Data.txt`, `HDFC Bank_Combined_Data.txt`, etc. | Base financial documents that are embedded into ChromaDB as the RAG knowledge base |
| `User_Upload_Documents/{userId}/` | Documents uploaded by individual users via the upload API |
| `.document-hashes` | A plain-text file listing SHA-256 hashes of every document already loaded into ChromaDB — used for deduplication across container restarts |

**How `.document-hashes` works:** Every time the Spring Boot app loads a document into ChromaDB, it writes that document's hash to this S3 file. On restart, the app reads this file before scanning S3 — if a document's hash is already there, it skips re-embedding it. This prevents the ChromaDB vector store from filling up with duplicate entries every time the ECS task restarts.

### Bucket 2: finsightdestinationbucket (Frontend Hosting)
This bucket holds the compiled React + Vite SPA build output (`index.html`, `assets/*.js`, `assets/*.css`). It is not configured as a static website — instead, CloudFront serves files directly from S3 using the S3 REST API. The `403` and `404` error handling is done at the CloudFront layer (returns `index.html` with HTTP 200) to support client-side routing.

**How it connects:**
- `finsight-ai-project` ← read/written by Spring Boot via the AWS SDK S3 client (credentials from ECS task role)
- `finsightdestinationbucket` ← read by CloudFront as its origin; written to by the developer (`aws s3 sync`) when deploying a new frontend build

---

## 8. CloudFront — CDN

**What it is:** CloudFront is AWS's Content Delivery Network. It has 400+ edge locations worldwide. When a user requests a file, CloudFront serves it from the nearest edge location rather than your S3 bucket in us-east-1 — this dramatically reduces latency for users outside the US. It also terminates HTTPS at the edge, so the user always gets a secure connection.

**Why we use it:** Two reasons:
1. **Performance** — React assets are served from the edge closest to the user.
2. **SPA routing** — A React app uses client-side routing (e.g., `/dashboard`, `/chat`). Those paths don't map to real files in S3. CloudFront is configured to return `index.html` for any 403/404, letting React Router take over.

**Our configuration:**

| Property | Value |
|---|---|
| Distribution ID | E1I0LZSJI2U0YN |
| Domain | d1h0nwxur4kyhe.cloudfront.net |
| Origin | finsightdestinationbucket.s3.us-east-1.amazonaws.com |
| Viewer Protocol | HTTPS only (HTTP redirected) |
| Error pages | 403 → /index.html (200), 404 → /index.html (200) |

**Important for the backend:** The frontend is served from `d1h0nwxur4kyhe.cloudfront.net`. When the React app makes API calls to `https://finsight-lb-prod.app`, it is making a cross-origin request. The backend must return the correct `Access-Control-Allow-Origin: https://d1h0nwxur4kyhe.cloudfront.net` header, and cookies set by the backend must use `SameSite=None; Secure` so the browser allows them to be sent cross-domain.

**How it connects:** CloudFront → S3 (frontend assets). The frontend itself then makes API calls to `finsight-lb-prod.app` (which resolves via Route 53 → ALB).

---

## 9. ACM — Certificate Manager

**What it is:** ACM is AWS's managed TLS/SSL certificate service. It issues free certificates, handles renewals automatically (every 13 months), and integrates natively with ALB and CloudFront so you never deal with private keys directly.

**Why we use it:** HTTPS is required everywhere — browsers block mixed-content HTTP/HTTPS, `SameSite=None` cookies require `Secure`, and users expect the padlock. ACM removes all certificate management overhead.

**Our configuration:**

| Property | Value |
|---|---|
| Certificate ARN | arn:aws:acm:us-east-1:877969058937:certificate/c97be18e-03a8-4ec8-8ec2-9098f37c617d |
| Domain | finsight-lb-prod.app |
| Status | ISSUED |
| Validation | DNS (CNAME record in Route 53) |

**DNS Validation:** ACM proves you own the domain by asking you to create a CNAME record. That record (`_0c80cbb6603c2d18f8fc0b5f68bafeb0.finsight-lb-prod.app`) lives in Route 53 and ACM checks it periodically to auto-renew.

**How it connects:** The ACM certificate is attached to the ALB HTTPS listener (port 443). When a browser connects to `https://finsight-lb-prod.app`, the ALB presents this certificate to establish the TLS handshake.

---

## 10. Route 53 — DNS

**What it is:** Route 53 is AWS's managed DNS service. DNS (Domain Name System) translates human-readable domain names like `finsight-lb-prod.app` into IP addresses that computers use to route traffic.

**Why we use it:** Without Route 53, users would have to type the ALB's raw domain name (`finsight-alb-1019456411.us-east-1.elb.amazonaws.com`) which is both unusable and changes if the ALB is replaced. Route 53 gives us a stable, branded domain.

**Our configuration:**

| Property | Value |
|---|---|
| Hosted Zone | finsight-lb-prod.app |
| Zone ID | Z0854792W5V06W15K8FJ |
| Type | Public |

**DNS Records:**

| Record | Type | Value | Purpose |
|---|---|---|---|
| finsight-lb-prod.app | A (Alias) | finsight-alb-1019456411.us-east-1.elb.amazonaws.com | Routes domain to the ALB |
| _0c80cbb6603c2d18f8fc0b5f68bafeb0.finsight-lb-prod.app | CNAME | (ACM validation value) | Proves domain ownership for TLS cert |
| finsight-lb-prod.app | NS | (AWS nameservers) | Delegates DNS authority to Route 53 |
| finsight-lb-prod.app | SOA | (auto-managed) | Standard DNS metadata |

**Alias record vs. regular A record:** An Alias record is AWS-specific. It points directly to an AWS resource (the ALB) and automatically updates if the ALB's underlying IPs change. It also has no TTL cost — Route 53 resolves it in real time. A regular A record with ALB IPs would break whenever AWS changes those IPs.

**How it connects:** Browser resolves `finsight-lb-prod.app` → Route 53 returns the ALB IP → browser connects to ALB on port 443.

---

## 11. ALB — Application Load Balancer

**What it is:** An ALB is a Layer-7 (HTTP/HTTPS-aware) load balancer. It receives incoming HTTP/HTTPS requests, terminates TLS, and forwards requests to backend targets (our ECS tasks). It also performs health checks to stop sending traffic to unhealthy instances.

**Why we use it:** ECS Fargate tasks get ephemeral IP addresses that change on every deployment. The ALB provides a stable endpoint. It also handles:
- TLS termination (so the Spring Boot app runs plain HTTP internally)
- Health checking (waits for `/actuator/health` to return 200 before routing traffic)
- HTTP → HTTPS redirect
- Multi-AZ distribution across our two subnets

**Our configuration:**

| Property | Value |
|---|---|
| Name | finsight-alb |
| DNS | finsight-alb-1019456411.us-east-1.elb.amazonaws.com |
| Scheme | Internet-facing |
| Subnets | finsight-subnet-az1 (us-east-1a), finsight-subnet-az2 (us-east-1b) |
| Security Group | finsight-alb-sg |

**Listeners:**

| Port | Protocol | Action |
|---|---|---|
| 80 | HTTP | Redirect → HTTPS (301) |
| 443 | HTTPS | Forward → finsight-tg (with ACM cert) |

**Target Group: finsight-tg**

| Property | Value |
|---|---|
| Protocol | HTTP |
| Port | 80 (ALB speaks HTTP to ECS task) |
| Health Check Path | /actuator/health |
| Health Check Interval | 30 seconds |

Spring Boot's `/actuator/health` endpoint returns `{"status":"UP"}` when the application is ready. During a rolling deployment, the ALB waits for the new task to pass health checks before deregistering the old one — zero-downtime deploys.

**How it connects:** Route 53 → ALB (via Alias record). ALB → ECS task port 8080 via `finsight-ecs-sg`. The ALB strips TLS and forwards plain HTTP to the Spring Boot container.

---

## 12. ECR — Elastic Container Registry

**What it is:** ECR is AWS's managed Docker container registry. It stores Docker images and makes them available to ECS for deployment. Think of it as Docker Hub but private and inside AWS.

**Why we use it:** ECS Fargate needs to pull the container image from somewhere. ECR is the natural choice — it's integrated with IAM for access control and in the same region as ECS, so pulls are fast and free.

**Our configuration:**

| Property | Value |
|---|---|
| Repository | finsight-ai-backend |
| URI | 877969058937.dkr.ecr.us-east-1.amazonaws.com/finsight-ai-backend |
| Image Tag | :latest |
| Region | us-east-1 |

**Build and push process:**
```bash
# Build for linux/amd64 (ECS is Intel/AMD, not ARM like Apple Silicon)
docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  --sbom=false \
  -t 877969058937.dkr.ecr.us-east-1.amazonaws.com/finsight-ai-backend:latest \
  --load .

# Authenticate and push
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 877969058937.dkr.ecr.us-east-1.amazonaws.com
docker push 877969058937.dkr.ecr.us-east-1.amazonaws.com/finsight-ai-backend:latest
```

`--provenance=false --sbom=false` is required because Docker buildx by default adds Notary attestation manifests that ECS cannot handle, causing `CannotPullContainerError`.

**How it connects:** ECS Fargate pulls the image from ECR at task startup. The ECS Execution Role (`finsight-ecs-execution-role`) has `AmazonECSTaskExecutionRolePolicy` which includes ECR read permissions.

---

## 13. ECS — Elastic Container Service (Fargate)

**What it is:** ECS is AWS's container orchestration service. It runs Docker containers without you managing servers. Fargate is the serverless launch type — AWS provisions the underlying compute, you only define CPU/memory requirements and the container image.

**Why we use it:** Fargate eliminates EC2 instance management, OS patching, and capacity planning. You pay per-second for the CPU and memory your containers actually use, and AWS handles scaling, placement, and recovery.

**Our configuration:**

### Cluster
| Property | Value |
|---|---|
| Name | finsight-cluster |
| Type | Fargate (serverless) |
| Region | us-east-1 |

### Service
| Property | Value |
|---|---|
| Name | finsight-task-service-ntzunda5 |
| Desired Tasks | 1 |
| Launch Type | FARGATE |
| Subnets | finsight-subnet-az1, finsight-subnet-az2 |
| Security Groups | finsight-ecs-sg + default |
| Public IP | Enabled (needed for ECR/Bedrock API calls) |

### Task Definition: finsight-task:16

| Property | Value |
|---|---|
| Total CPU | 2048 (2 vCPU) |
| Total Memory | 7168 MB (7 GB) |
| Execution Role | finsight-ecs-execution-role |
| Task Role | finsight-ecs-task-role |

**Container 1: finsight-backend**

| Property | Value |
|---|---|
| Image | 877969058937.dkr.ecr.us-east-1.amazonaws.com/finsight-ai-backend:latest |
| CPU | 1024 (1 vCPU) |
| Memory | 5120 MB |
| Port | 8080 |
| Log Group | /ecs/finsight-task |

Environment variables (set directly in task definition):

| Variable | Value | Purpose |
|---|---|---|
| TESSERACT_LIB_PATH | /usr/lib | OCR library path in Linux container |
| FORWARD_HEADERS_STRATEGY | FRAMEWORK | Makes Spring respect X-Forwarded-Proto from ALB so it knows it's behind HTTPS |
| COOKIE_SECURE | true | Sets `Secure` flag on auth cookies (required for `SameSite=None`) |
| SPRING_AI_VECTORSTORE_CHROMA_INITIALIZE_SCHEMA | true | Auto-creates ChromaDB schema on startup |

Secrets (pulled from Secrets Manager at startup):

| Variable | Secret Key | Purpose |
|---|---|---|
| COGNITO_CLIENT_SECRET | finsight/prod/secrets → COGNITO_CLIENT_SECRET | Cognito HMAC signing |
| TAVILY_API_KEY | finsight/prod/secrets → TAVILY_API_KEY | Web search API key |
| ALLOWED_ORIGINS | finsight/prod/secrets → ALLOWED_ORIGINS | CORS whitelist |

**Container 2: chromadb**

| Property | Value |
|---|---|
| Image | chromadb/chroma:latest |
| CPU | 1024 (1 vCPU) |
| Memory | 6144 MB |
| Port | 8000 (internal only, not exposed to ALB) |
| Mount Point | /chroma/chroma → EFS volume `chroma-data` |

Both containers share the same network namespace (they're in the same task). The Spring Boot app reaches ChromaDB at `localhost:8000` — no security group needed, no internet hop.

**Volume:**

| Name | Type | File System |
|---|---|---|
| chroma-data | EFS | fs-012cf308c1f34672d (finsight-chroma-efs) |

**Rolling Deployments:** When a new task definition is registered and the service is updated, ECS starts a new task, waits for it to pass ALB health checks, then drains and stops the old task. This gives zero-downtime deployments. The command is:
```bash
aws ecs update-service \
  --cluster finsight-cluster \
  --service finsight-task-service-ntzunda5 \
  --task-definition finsight-task:16 \
  --force-new-deployment
```

**How it connects:** ECS task ← ECR (image pull) | ALB → ECS task port 8080 | ECS task → Bedrock, S3, Cognito, Pinpoint (via IGW) | ECS task ↔ EFS (via mount target in same subnet).

---

## 14. EFS — Elastic File System

**What it is:** EFS is AWS's managed network file system (NFS). Unlike S3 (object storage), EFS behaves like a regular Linux filesystem — you mount it to a path and read/write files normally. It is shared, persistent, and accessible from multiple containers or instances simultaneously.

**Why we use it:** ChromaDB stores its vector data on the local filesystem at `/chroma/chroma`. If that path is inside the container, the data is wiped every time the task is replaced (on deployment, crash, or scale). Mounting EFS at that path makes the data survive container restarts permanently.

**Without EFS:** Every restart would wipe all embedded vectors. The `DocumentLoaderService` would re-embed all documents from scratch on every boot, taking several minutes and causing duplicate vectors to accumulate.

**Our configuration:**

| Property | Value |
|---|---|
| Name | finsight-chroma-efs |
| File System ID | fs-012cf308c1f34672d |
| Encryption | Enabled (at rest) |
| Transit Encryption | Enabled (in transit, via TLS) |
| State | Available |

**Mount Targets (one per AZ):**

| AZ | Subnet | Mount Target IP |
|---|---|---|
| us-east-1a | subnet-099421d4c04934d3f | 10.0.2.76 |
| us-east-1b | subnet-088fb9aa33a0719fe | 10.0.1.236 |

A mount target is an NFS endpoint inside the subnet. When the ECS task starts in us-east-1a, it mounts the EFS via the `10.0.2.76` endpoint. If it starts in us-east-1b, it uses `10.0.1.236`. Both mount targets serve the same filesystem — the data is always the same regardless of which AZ the task lands in.

**How it connects:** EFS mount targets sit in the same subnets as ECS tasks. The task definition maps the `chroma-data` EFS volume to `/chroma/chroma` inside the `chromadb` container. ChromaDB writes and reads its SQLite database and vector index files there as if it were a local disk.

---

## 15. AWS Bedrock

**What it is:** Amazon Bedrock is a managed API for foundation models (LLMs and embedding models). It gives you access to models from Anthropic, Amazon, Cohere, and others without managing any GPU infrastructure.

**Why we use it:** FinsightAI is an RAG (Retrieval-Augmented Generation) application. It needs two AI capabilities:
1. An LLM to generate answers from retrieved context
2. An embedding model to convert documents and queries into vectors for semantic search

**Our models:**

| Model | ID | Purpose |
|---|---|---|
| Claude 3 Sonnet | us.anthropic.claude-3-sonnet-20240229-v1:0 | Chat — generates answers from document context |
| Amazon Cohere Embed | (Cohere Embed) | Embeddings — converts documents and user queries to vectors |

**How it works in the application:**

- **Document loading:** When the app loads a financial document from S3, it splits it into chunks, calls the Cohere embedding model to get a vector for each chunk, and stores those vectors in ChromaDB.
- **Query time:** When a user asks a question, the app embeds the question using Cohere, searches ChromaDB for the most similar document chunks, injects those chunks as context into a prompt, and calls Claude Sonnet to generate an answer.
- **Tavily web search:** If the RAG results are insufficient, the app can optionally call the Tavily API for live web search results to augment the answer.

**How it connects:** Spring Boot (`finsight-backend` container) calls Bedrock via the AWS SDK over HTTPS. The ECS task role (`finsight-ecs-task-role`) has `AmazonBedrockFullAccess`. Traffic exits the VPC through the Internet Gateway to `bedrock.us-east-1.amazonaws.com`.

---

## 16. Amazon Cognito

**What it is:** Amazon Cognito is a managed user authentication service. It handles user registration, email verification, login, JWT token issuance, password reset, and token refresh — all without you building or running an auth server.

**Why we use it:** Building auth from scratch (password hashing, token management, OTP emails, refresh token rotation) is complex and security-critical. Cognito handles all of it and integrates directly with Spring Security via JWT validation.

**Our configuration:**

| Property | Value |
|---|---|
| User Pool Name | finsight user pool |
| User Pool ID | us-east-1_9WLoAw5x8 |
| App Client ID | 2sgi9nhq7e1b21ovav4dqggtkk |
| Sign-in method | Email (as username) |
| Email auto-verification | Enabled |
| MFA | Disabled |
| PreventUserExistenceErrors | ENABLED |

**Auth flows enabled:**
- `USER_PASSWORD_AUTH` — standard email + password login
- `REFRESH_TOKEN_AUTH` — exchange a refresh token for new access/id tokens
- `USER_SRP_AUTH` — Secure Remote Password (available but not used)

**How authentication works in FinsightAI:**

1. **Register:** `POST /auth/register` → Spring calls Cognito `signUp` → Cognito sends OTP to the user's email.
2. **Confirm:** `POST /auth/confirm` → Spring calls Cognito `confirmSignUp` with the OTP → Cognito marks the user as confirmed → Spring calls Pinpoint to send a welcome email.
3. **Login:** `POST /auth/login` → Spring calls Cognito `initiateAuth` → Cognito returns an Access Token (JWT), ID Token (JWT), and Refresh Token. Spring sets the Refresh Token in an HTTP-only `SameSite=None; Secure` cookie and returns Access/ID tokens in the response body.
4. **API calls:** The frontend sends the Access Token as `Authorization: Bearer <token>`. Spring Security validates it against the Cognito JWKS endpoint (`https://cognito-idp.us-east-1.amazonaws.com/us-east-1_9WLoAw5x8/.well-known/jwks.json`).
5. **Token refresh:** `POST /auth/refresh` → Spring reads the Refresh Token cookie → calls Cognito `initiateAuth` with `REFRESH_TOKEN_AUTH` → returns new Access/ID tokens.

**PreventUserExistenceErrors:** When ENABLED, Cognito returns `NotAuthorizedException` for both wrong passwords AND non-existent users (instead of `UserNotFoundException`). This prevents attackers from enumerating which emails are registered. Our login handler compensates by calling `adminGetUser` after a `NotAuthorizedException` to determine whether it was a wrong password or a missing account — so users get accurate error messages without leaking information to attackers via the public API.

**How it connects:** Spring Boot calls Cognito via the AWS SDK. The ECS task role has `AmazonCognitoReadOnly` for `adminGetUser` lookups. The Spring Security `JwtDecoder` bean fetches Cognito's public keys automatically to validate tokens on every request — no Cognito SDK call needed for that step.

---

## 17. AWS Pinpoint

**What it is:** Amazon Pinpoint is a customer engagement and messaging service. It can send transactional emails, SMS, push notifications, and run multi-channel marketing campaigns.

**Why we use it:** We use it purely for transactional email — sending a welcome email to the user after they confirm their account. It gives us a managed email-sending infrastructure without setting up an SMTP server.

**Our configuration:**

| Property | Value |
|---|---|
| Application Name | finsightAI |
| Application ID | 3aece72f809e41f498f60c539cf34278 |
| Region | us-east-1 |
| Channel | Email |

**When it fires:** `CognitoAuthService.confirmRegistration()` — immediately after `cognitoClient.confirmSignUp()` succeeds. If Pinpoint fails (misconfigured email address, service issue), the failure is logged as a warning and the confirmation still succeeds — the welcome email is best-effort, not critical to auth.

**How it connects:** Spring Boot calls Pinpoint via the `PinpointClient` SDK bean. The ECS task role permissions cover Pinpoint calls (via the broader IAM policies). Traffic exits through the IGW.

---

## 18. Secrets Manager

**What it is:** AWS Secrets Manager stores sensitive values (API keys, passwords, connection strings) encrypted at rest and provides them to applications securely at runtime. It supports automatic secret rotation and fine-grained IAM access control.

**Why we use it:** Hardcoding secrets in environment variables in task definitions is a security risk — they appear in plaintext in the AWS console and in CloudTrail logs. Secrets Manager stores them encrypted and injects them at container startup, keeping them out of the task definition JSON.

**Our configuration:**

| Secret Name | ARN | Contains |
|---|---|---|
| finsight/prod/secrets | arn:aws:secretsmanager:us-east-1:877969058937:secret:finsight/prod/secrets-yps1sZ | COGNITO_CLIENT_SECRET, TAVILY_API_KEY, ALLOWED_ORIGINS |

The secret is a JSON object. ECS injects individual keys using the `valueFrom` syntax:
```
arn:aws:...:secret:finsight/prod/secrets-yps1sZ:KEY_NAME::
```

This means the container sees `COGNITO_CLIENT_SECRET` as a regular environment variable, but it was never stored in the task definition in plaintext.

**How it connects:** ECS Execution Role (`finsight-ecs-execution-role`) fetches secrets from Secrets Manager before the container starts and injects them as environment variables. The app reads them via `${COGNITO_CLIENT_SECRET}` Spring property placeholders.

---

## 19. IAM Roles

**What they are:** IAM (Identity and Access Management) roles define what AWS API actions a service is allowed to perform. ECS uses two distinct roles per task.

**Why two roles:** The Execution Role is used by the ECS control plane (to pull images and fetch secrets before the container starts). The Task Role is used by the application code running inside the container (to call Bedrock, S3, Cognito, etc. at runtime). Separating them follows least-privilege — the app code doesn't need ECR pull permissions, and the ECS agent doesn't need Bedrock access.

### finsight-ecs-execution-role

Used by: ECS agent (before container starts)

| Attached Policy | Purpose |
|---|---|
| AmazonECSTaskExecutionRolePolicy | Pull images from ECR, fetch secrets from Secrets Manager, write logs to CloudWatch |

### finsight-ecs-task-role

Used by: Application code running inside the container

| Attached Policy | What it allows |
|---|---|
| AmazonS3FullAccess | Read/write documents and the `.document-hashes` tracking file |
| AmazonBedrockFullAccess | Call Claude Sonnet (chat) and Cohere (embeddings) |
| AmazonCognitoReadOnly | Call `adminGetUser` to distinguish wrong-password vs. non-existent user |
| AmazonEC2ContainerRegistryReadOnly | Read-only ECR access (not strictly needed at runtime but keeps permissions consistent) |

**How they connect:** The ECS task definition specifies both role ARNs. AWS automatically provides short-lived credentials to the running container via the ECS Metadata endpoint (`169.254.170.2`) — the AWS SDK picks these up automatically with no configuration needed.

---

## 20. CloudWatch Logs

**What it is:** CloudWatch Logs is AWS's centralized log aggregation service. Containers (and most AWS services) can stream logs to CloudWatch in real time. You can search, filter, set alarms, and set retention policies.

**Why we use it:** ECS Fargate containers have no persistent disk for logs. Without CloudWatch, logs vanish when the container stops. CloudWatch persists them and makes them searchable.

**Our configuration:**

| Property | Value |
|---|---|
| Log Group | /ecs/finsight-task |
| Log Driver | awslogs |
| Stream Prefix | ecs |
| Region | us-east-1 |
| Retention | Indefinite (not set) |

Both the `finsight-backend` and `chromadb` containers log to the same log group but with different stream names (`ecs/finsight-backend/<task-id>` and `ecs/chromadb/<task-id>`).

**Useful queries:**
```bash
# Check startup
aws logs filter-log-events \
  --log-group-name /ecs/finsight-task \
  --filter-pattern "Started FinsightAiApplication"

# Check CORS issues
aws logs filter-log-events \
  --log-group-name /ecs/finsight-task \
  --filter-pattern "CORS"

# Check auth errors
aws logs filter-log-events \
  --log-group-name /ecs/finsight-task \
  --filter-pattern "Cognito"
```

**How it connects:** The ECS Execution Role (`AmazonECSTaskExecutionRolePolicy`) grants permission to create log streams and put log events. The `awslogs` log driver inside each container forwards stdout/stderr to CloudWatch automatically.

---

## 21. Service Connection Map

```
┌─────────────────────────────────────────────────────────────────┐
│  DEVELOPER MACHINE                                              │
│  docker buildx build → push → ECR                              │
│  aws ecs update-service → ECS                                  │
│  aws s3 sync → S3 (frontend)                                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  USER BROWSER                                                   │
│  https://d1h0nwxur4kyhe.cloudfront.net  (React SPA)            │
│       ↓ serves assets                                           │
│  S3: finsightdestinationbucket                                  │
│                                                                 │
│  API calls → https://finsight-lb-prod.app                       │
│       ↓ DNS                                                     │
│  Route 53 (A alias → ALB)                                       │
│       ↓ TLS + HTTP routing                                      │
│  ACM cert on ALB (finsight-lb-prod.app)                         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  VPC: finsight-ai-vpc (10.0.0.0/16)                            │
│                                                                 │
│  ALB (finsight-alb)         ← finsight-alb-sg (80, 443)        │
│       ↓ :8080 HTTP                                              │
│  ECS Task (finsight-task:16) ← finsight-ecs-sg (8080 from ALB) │
│  ┌────────────────────────┐  ┌──────────────────────────────┐  │
│  │  finsight-backend      │  │  chromadb                    │  │
│  │  Spring Boot :8080     │←→│  :8000 (localhost only)      │  │
│  │                        │  │       ↓                      │  │
│  │  → Bedrock (internet)  │  │  EFS: finsight-chroma-efs    │  │
│  │  → S3 (internet)       │  │  /chroma/chroma              │  │
│  │  → Cognito (internet)  │  └──────────────────────────────┘  │
│  │  → Pinpoint (internet) │                                     │
│  │  → Secrets Mgr (startup)│                                    │
│  └────────────────────────┘                                     │
│       ↓ all internet traffic                                    │
│  Internet Gateway (finsight-igw)                                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  MANAGED AWS SERVICES (outside VPC)                             │
│                                                                 │
│  Cognito ── JWT issuer ──────→ Spring Security validates tokens │
│  Cognito ── User store ──────→ login / register / confirm OTP  │
│  Bedrock ── Claude Sonnet ───→ LLM chat responses              │
│  Bedrock ── Cohere Embed ────→ document + query vectors        │
│  S3 ──────── finsight-ai-project → documents + hash tracking   │
│  Pinpoint ── Email channel ──→ welcome email after confirm     │
│  Secrets Mgr ─ finsight/prod/secrets → COGNITO_SECRET, etc.   │
│  CloudWatch ─ /ecs/finsight-task → all container logs          │
│  ECR ──────── finsight-ai-backend:latest → container image     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 22. Traffic Flow — End to End

### User Loads the Application

1. Browser opens `https://d1h0nwxur4kyhe.cloudfront.net`
2. CloudFront checks its edge cache. Cache miss → fetches `index.html` from `finsightdestinationbucket` S3.
3. Browser downloads `index.html` + JS/CSS bundles (cached at CloudFront edge on first request).
4. React app boots in the browser.

### User Registers

1. React calls `POST https://finsight-lb-prod.app/auth/register`
2. DNS resolves `finsight-lb-prod.app` via Route 53 → ALB IP
3. TLS handshake: ALB presents ACM certificate for `finsight-lb-prod.app`
4. ALB forwards to ECS task port 8080 (Spring Boot)
5. Spring Boot calls Cognito `signUp` → Cognito sends OTP email to user
6. Response: "Check your email for the confirmation code"

### User Confirms OTP

1. React calls `POST /auth/confirm` with `{ email, confirmationCode }`
2. Spring calls Cognito `confirmSignUp` → Cognito verifies OTP, marks user CONFIRMED
3. Spring calls Pinpoint `sendMessages` → welcome email sent (failure is non-fatal)
4. Response: "Email confirmed. You can now log in."

### User Logs In

1. React calls `POST /auth/login` with `{ email, password }`
2. Spring calls Cognito `initiateAuth` with `USER_PASSWORD_AUTH`
3. Cognito returns Access Token (15 min), ID Token (15 min), Refresh Token (30 days)
4. Spring sets Refresh Token in HTTP-only cookie: `SameSite=None; Secure; HttpOnly`
5. Access Token + ID Token returned in JSON response body
6. React stores tokens in memory (not localStorage)

### User Asks a Financial Question

1. React calls `POST /api/chat` with `Authorization: Bearer <access_token>`
2. ALB → Spring Boot
3. Spring Security validates the JWT against Cognito's public JWKS endpoint
4. Spring embeds the user's question via Bedrock (Cohere) → vector
5. Spring searches ChromaDB (localhost:8000) for nearest document chunks
6. ChromaDB reads its index from EFS (`/chroma/chroma`) → returns top-K chunks
7. Spring builds a prompt with the retrieved chunks and calls Bedrock (Claude Sonnet)
8. Claude generates an answer → streamed back to the browser

### User Uploads a Document

1. React calls `POST /api/upload` with multipart file
2. Spring Boot saves file to `S3/User_Upload_Documents/{userId}/filename`
3. Spring embeds document chunks via Bedrock (Cohere) and stores in ChromaDB
4. Spring saves the document hash to `S3/finsight-ai-project/.document-hashes`
5. On next restart, the document is not re-embedded (hash already tracked)

### Token Refresh (Automatic)

1. Access token nears expiry, React calls `POST /auth/refresh`
2. Browser automatically sends the Refresh Token cookie (cross-domain allowed because `SameSite=None; Secure`)
3. Spring reads cookie → calls Cognito `initiateAuth` with `REFRESH_TOKEN_AUTH`
4. Cognito returns new Access Token + ID Token
5. React updates its in-memory token store

### New Backend Deployment

1. Developer pushes new code → builds Docker image → pushes to ECR
2. `aws ecs register-task-definition` → creates new revision
3. `aws ecs update-service --force-new-deployment` → ECS starts new task
4. New task pulls image from ECR → starts Spring Boot + ChromaDB containers
5. ChromaDB mounts EFS → existing vector data immediately available (no re-indexing)
6. Spring Boot calls Secrets Manager → gets COGNITO_CLIENT_SECRET, TAVILY_API_KEY, ALLOWED_ORIGINS
7. ALB health check polls `/actuator/health` every 30s → waits for 200
8. ALB registers new task → deregisters old task → zero downtime

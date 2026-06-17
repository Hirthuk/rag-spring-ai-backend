# AWS Cognito Integration — FinsightAI Backend

## Overview

Authentication is split into two layers:

| Layer | Responsibility | Spring component |
|---|---|---|
| **JWT Validation** | Verify every incoming request token is a real, unexpired Cognito JWT | `spring-boot-starter-oauth2-resource-server` + `SecurityConfig` |
| **Auth Operations** | Register, login, refresh, password reset, logout | AWS SDK `CognitoIdentityProviderClient` + `CognitoAuthService` |

---

## Step 1 — Create a Cognito User Pool

1. Open **AWS Console → Cognito → User Pools → Create user pool**
2. Select:
   - Sign-in option: **Email**
   - Password policy: as required
   - MFA: optional
   - Self-service account recovery: **Email**
3. **App client settings**:
   - App type: **Public client** (no client secret) — simpler for SPAs/mobile
     *OR* **Confidential client** (with client secret) — better for backend-to-backend
   - **Enable authentication flows:** tick **USER_PASSWORD_AUTH**
     > Without this the `/auth/login` endpoint returns `NotAuthorizedException`.
   - Token expiry: choose suitable values (e.g. Access 1h, Refresh 30d)
4. Note down:
   - **User Pool ID** (e.g. `us-east-1_9WLoAw5x8`)
   - **App Client ID**
   - **App Client Secret** (only if you created a confidential client)

---

## Step 2 — Configure the Backend

Edit `src/main/resources/application.properties`:

```properties
# JWT issuer — must match your User Pool region and ID
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/<USER_POOL_ID>

# Cognito SDK settings
aws.cognito.region=us-east-1
aws.cognito.user-pool-id=<USER_POOL_ID>
aws.cognito.client-id=<APP_CLIENT_ID>
aws.cognito.client-secret=<APP_CLIENT_SECRET>   # leave blank for public clients
```

**AWS credentials** — the Cognito SDK client reuses the same `DefaultCredentialsProvider`
chain that Bedrock uses. This means it respects:
- `AWS_PROFILE` environment variable
- `~/.aws/credentials` profile configured in `application.properties`
- EC2/ECS instance profile in production

---

## Step 3 — Dependencies Added

**`pom.xml`** additions (both managed by the AWS SDK BOM at `2.41.22`):

```xml
<!-- JWT validation via Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<!-- AWS SDK for Cognito auth operations (sign up, login, etc.) -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cognitoidentityprovider</artifactId>
</dependency>
```

---

## Implementation Map

```
src/main/java/com/finsight/finsight_ai/
├── Security/
│   └── SecurityConfig.java           — which routes are public vs protected
├── configuration/
│   ├── CognitoProperties.java        — @ConfigurationProperties(prefix="aws.cognito")
│   └── CognitoConfig.java            — creates CognitoIdentityProviderClient bean
├── model/auth/
│   ├── RegisterRequest.java
│   ├── ConfirmRegistrationRequest.java
│   ├── LoginRequest.java
│   ├── RefreshTokenRequest.java
│   ├── ForgotPasswordRequest.java
│   ├── ResetPasswordRequest.java
│   └── AuthResponse.java
├── service/
│   └── CognitoAuthService.java       — all Cognito SDK calls live here
└── controller/
    ├── auth/AuthController.java      — public auth REST endpoints
    └── User/UserController.java      — /me endpoint (authenticated)
```

---

## REST API Reference

### Public Endpoints (no token required)

#### `POST /auth/register`
Create a new Cognito user. Cognito sends a 6-digit verification code to the user's email.

```json
Request:
{
  "email": "user@example.com",
  "password": "MyPassword123!",
  "name": "Sharan Kumar"
}

Response 200:
{
  "message": "Registration successful. Check your email for the confirmation code."
}
```

---

#### `POST /auth/confirm`
Confirm registration with the emailed verification code.

```json
Request:
{
  "email": "user@example.com",
  "confirmationCode": "123456"
}

Response 200:
{
  "message": "Email confirmed. You can now log in."
}
```

---

#### `POST /auth/login`
Authenticate and receive JWT tokens.

```json
Request:
{
  "email": "user@example.com",
  "password": "MyPassword123!"
}

Response 200:
{
  "message": "Login successful.",
  "accessToken": "eyJra...",
  "idToken": "eyJra...",
  "refreshToken": "eyJjd...",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

> **Token usage:**
> - `accessToken` — use for calling Cognito API operations (e.g. `/auth/logout`)
> - `idToken` — use as `Authorization: Bearer <idToken>` for all protected backend endpoints
> - `refreshToken` — long-lived; use to get new access/id tokens without re-login

---

#### `POST /auth/refresh`
Get a new `accessToken` + `idToken` using a `refreshToken`.

```json
Request:
{
  "refreshToken": "eyJjd...",
  "email": "user@example.com"   // only required if app client has a client secret
}

Response 200:
{
  "accessToken": "eyJra...",
  "idToken": "eyJra...",
  "expiresIn": 3600,
  "tokenType": "Bearer",
  "message": "Token refreshed."
}
```

---

#### `POST /auth/forgot-password`
Initiate the password reset flow. Cognito emails a reset code.

```json
Request:
{ "email": "user@example.com" }

Response 200:
{ "message": "Password reset code sent to your email." }
```

---

#### `POST /auth/reset-password`
Complete the password reset using the emailed code.

```json
Request:
{
  "email": "user@example.com",
  "confirmationCode": "654321",
  "newPassword": "NewPassword456!"
}

Response 200:
{ "message": "Password reset successful. You can now log in with your new password." }
```

---

### Protected Endpoints (require `Authorization: Bearer <token>`)

#### `POST /auth/logout`
Invalidate **all tokens** for the user (global sign-out).
Send the **accessToken** in the Authorization header.

```
Authorization: Bearer <accessToken>
```

```json
Response 200:
{ "message": "Logged out from all devices." }
```

---

#### `GET /me`
Returns the authenticated user's profile decoded from the JWT.
Send the **idToken** in the Authorization header.

```
Authorization: Bearer <idToken>
```

```json
Response 200:
{
  "sub": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "email": "user@example.com",
  "emailVerified": true,
  "name": "Sharan Kumar",
  "username": "user@example.com",
  "tokenUse": "id",
  "issuedAt": "2026-06-16T10:00:00Z",
  "expiresAt": "2026-06-16T11:00:00Z"
}
```

---

## How Token Validation Works

Spring Boot auto-configures a JWT decoder from:
```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/<pool-id>
```

On every protected request:
1. Spring Security fetches the Cognito JWKS from `<issuer>/.well-known/jwks.json` (cached)
2. Validates the token signature using Cognito's public keys
3. Checks expiry (`exp`) and issuer (`iss`)
4. If valid, populates `SecurityContext` with a `JwtAuthenticationToken`
5. Controllers access it via `@AuthenticationPrincipal Jwt jwt`

---

## Security Rules (SecurityConfig)

| Route pattern | Access |
|---|---|
| `/auth/register`, `/auth/confirm`, `/auth/login` | Public |
| `/auth/refresh`, `/auth/forgot-password`, `/auth/reset-password` | Public |
| `/public/**` | Public |
| `/actuator/health`, `/actuator/info` | Public |
| `/auth/logout` | Authenticated (needs valid access token) |
| `/me` | Authenticated (needs valid id token) |
| Everything else | Authenticated |

---

## How SECRET_HASH Works

If your Cognito app client has a **client secret**, every auth API call must include a `SECRET_HASH`:

```
SECRET_HASH = Base64( HMAC-SHA256( username + clientId, clientSecret ) )
```

`CognitoAuthService` computes this automatically when `aws.cognito.client-secret` is set.
Leave it blank for public app clients.

---

## Common Errors

| Error | Cause | Fix |
|---|---|---|
| `NotAuthorizedException` on login | Wrong password, or `USER_PASSWORD_AUTH` not enabled | Enable the auth flow in the Cognito app client |
| `UserNotConfirmedException` on login | Email not confirmed yet | Call `/auth/confirm` first |
| `InvalidClientTokenId` | Wrong `clientId` | Check `aws.cognito.client-id` in properties |
| `NotAuthorizedException: Unable to verify secret hash` | Client has a secret but `client-secret` is empty | Set `aws.cognito.client-secret` |
| 401 on protected endpoints | JWT expired or wrong token type | Refresh the token or use the idToken |
| CORS errors in browser | `allowedOrigins` mismatch | Update `CorsConfig.java` with the frontend URL |

---

## Testing with curl

```bash
# Register
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test1234!","name":"Test User"}'

# Confirm
curl -X POST http://localhost:8080/auth/confirm \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","confirmationCode":"123456"}'

# Login
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test1234!"}'

# Use the idToken from login response:
TOKEN="<idToken>"

# Get current user
curl http://localhost:8080/me \
  -H "Authorization: Bearer $TOKEN"

# Logout (use accessToken)
ACCESS_TOKEN="<accessToken>"
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

# Secure Token Architecture — FinsightAI

## Overview

FinsightAI uses AWS Cognito for authentication. A successful login produces **three tokens**. Each token has a distinct purpose and a distinct security posture.

---

## Token Reference

| Token | Purpose | Lifetime | Where stored |
|---|---|---|---|
| **accessToken** | Authorises every API call (`Authorization: Bearer <token>`) | 1 hour | JS memory (variable) |
| **idToken** | Contains user profile claims (email, sub, cognito:groups) | 1 hour | JS memory (variable) |
| **refreshToken** | Issues new access/id tokens without re-entering credentials | 30 days | HttpOnly cookie |

### Why three tokens?

Cognito follows the OAuth 2.0 / OIDC standard:

- **accessToken** — a JWT the backend validates on every request. Scoped to `aws.cognito.signin.user.admin`. The backend reads the `sub` claim from this token to identify the user.
- **idToken** — another JWT carrying identity claims. Useful for the frontend to display the user's name/email without a separate profile API call.
- **refreshToken** — an opaque token (not a JWT). Used only on `POST /auth/refresh`. Never sent to protected API endpoints.

---

## The XSS Risk of localStorage

Before this change, all three tokens were stored in `localStorage`.

```
localStorage.getItem("accessToken")   // was readable
localStorage.getItem("refreshToken")  // was readable — the dangerous one
```

**localStorage is accessible to every JavaScript snippet running on the page.** If an attacker injects malicious JS (Cross-Site Scripting / XSS) — through a compromised npm package, a user-generated content field, a browser extension, or a supply-chain attack — they can silently read all tokens and exfiltrate them.

- A stolen **accessToken** gives an attacker one hour of API access.
- A stolen **refreshToken** gives an attacker **30 days** of silent, persistent access. They can keep refreshing the access token indefinitely until the refresh token expires or is revoked.

---

## New Architecture

```
┌────────────────────────────────────────────────────────────────┐
│  POST /auth/login                                              │
│  Body: { email, password }                                     │
│                                                                │
│  ← Response body:  { accessToken, idToken, expiresIn }        │
│  ← Set-Cookie:     finsight_refresh=<token>                   │
│                    HttpOnly; Secure; SameSite=Strict           │
│                    Path=/auth/refresh; Max-Age=2592000         │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  POST /auth/refresh                                            │
│  Body: { email }          ← only email needed (for SECRET_HASH)│
│  Cookie: finsight_refresh ← sent automatically by browser     │
│                                                                │
│  ← Response body:  { accessToken, idToken, expiresIn }        │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  POST /auth/logout                                             │
│  Header: Authorization: Bearer <accessToken>                   │
│                                                                │
│  ← Set-Cookie: finsight_refresh=; Max-Age=0   (clears cookie) │
└────────────────────────────────────────────────────────────────┘
```

### Cookie properties explained

| Property | Value | Why |
|---|---|---|
| `HttpOnly` | true | Cookie is invisible to JavaScript — `document.cookie` cannot read it. Eliminates the XSS theft vector. |
| `Secure` | true (prod) / false (local) | Browser sends cookie only over HTTPS in production. Prevents interception on unencrypted connections. |
| `SameSite` | Strict | Cookie is sent only on same-origin requests. Blocks CSRF: if an attacker's page tricks the browser into calling `/auth/refresh`, the cookie is withheld. |
| `Path` | `/auth/refresh` | Cookie is scoped to a single endpoint. The browser does **not** attach it to any other API call, reducing exposure. |
| `Max-Age` | 2592000 (30 days) | Matches Cognito refresh token lifetime. Set to 0 on logout for immediate expiry. |

---

## Token Flow Diagrams

### Login

```
Frontend                    Backend                     Cognito
   │                           │                           │
   │  POST /auth/login         │                           │
   │  { email, password }      │                           │
   │ ─────────────────────────>│                           │
   │                           │  InitiateAuth (SRP/USER_  │
   │                           │  PASSWORD_AUTH)           │
   │                           │ ─────────────────────────>│
   │                           │                           │
   │                           │  ← accessToken            │
   │                           │    idToken                │
   │                           │    refreshToken           │
   │                           │<──────────────────────────│
   │                           │                           │
   │  ← { accessToken,         │                           │
   │      idToken }            │                           │
   │  ← Set-Cookie:            │                           │
   │    finsight_refresh       │                           │
   │<──────────────────────────│                           │
   │                           │                           │
   │ Store accessToken         │                           │
   │ + idToken in JS memory    │                           │
   │ (NOT localStorage)        │                           │
```

### Token Refresh

```
Frontend                    Backend                     Cognito
   │                           │                           │
   │  POST /auth/refresh       │                           │
   │  Body: { email }          │                           │
   │  Cookie: finsight_refresh │ ← browser sends automatically
   │ ─────────────────────────>│                           │
   │                           │                           │
   │                           │  InitiateAuth             │
   │                           │  (REFRESH_TOKEN_AUTH)     │
   │                           │  refreshToken from cookie │
   │                           │ ─────────────────────────>│
   │                           │                           │
   │                           │  ← new accessToken        │
   │                           │    new idToken            │
   │                           │<──────────────────────────│
   │                           │                           │
   │  ← { accessToken,         │                           │
   │      idToken }            │                           │
   │<──────────────────────────│                           │
   │                           │                           │
   │ Update in-memory tokens   │                           │
```

### Logout

```
Frontend                    Backend                     Cognito
   │                           │                           │
   │  POST /auth/logout        │                           │
   │  Authorization: Bearer .. │                           │
   │ ─────────────────────────>│                           │
   │                           │  GlobalSignOut            │
   │                           │ ─────────────────────────>│
   │                           │  (invalidates all tokens) │
   │                           │<──────────────────────────│
   │                           │                           │
   │  ← Set-Cookie:            │                           │
   │    finsight_refresh=;     │                           │
   │    Max-Age=0              │ ← cookie deleted          │
   │<──────────────────────────│                           │
   │                           │                           │
   │ Clear in-memory tokens    │                           │
```

---

## Frontend Migration Guide

### What to change

**Before (insecure):**
```js
// After login
localStorage.setItem("accessToken",  response.accessToken);
localStorage.setItem("idToken",      response.idToken);
localStorage.setItem("refreshToken", response.refreshToken); // ← remove this

// On API calls
const token = localStorage.getItem("accessToken");
fetch("/api/...", { headers: { Authorization: `Bearer ${token}` } });

// On refresh
fetch("/auth/refresh", {
  method: "POST",
  body: JSON.stringify({ email, refreshToken: localStorage.getItem("refreshToken") })
});

// On logout
localStorage.clear();
```

**After (secure):**
```js
// Auth state lives in a module-level variable (or React context / Zustand store)
let authState = { accessToken: null, idToken: null };

// After login — store in memory only; cookie is set by backend automatically
const response = await fetch("/auth/login", { ... });
const data = await response.json();
authState.accessToken = data.accessToken;
authState.idToken     = data.idToken;
// No localStorage call. No refreshToken in the response.

// On API calls — same as before, just read from memory
fetch("/api/...", {
  headers: { Authorization: `Bearer ${authState.accessToken}` },
  credentials: "include"   // ← must include for the cookie to travel
});

// On refresh — send only email; cookie is sent automatically
const refreshed = await fetch("/auth/refresh", {
  method:      "POST",
  credentials: "include",          // ← critical: sends the HttpOnly cookie
  headers:     { "Content-Type": "application/json" },
  body:        JSON.stringify({ email: currentUserEmail })  // no refreshToken
});
const data = await refreshed.json();
authState.accessToken = data.accessToken;
authState.idToken     = data.idToken;

// On logout — clear memory; backend clears the cookie
await fetch("/auth/logout", {
  method:      "POST",
  credentials: "include",
  headers:     { Authorization: `Bearer ${authState.accessToken}` }
});
authState = { accessToken: null, idToken: null };
// No localStorage.clear() needed — nothing was stored there
```

### Key points for the frontend

1. **`credentials: "include"`** must be set on every `fetch()` call that needs cookie-based auth (i.e., all `/auth/*` calls and any future calls that rely on cookies). Without it the browser will not attach the cookie.

2. **Memory storage** (not localStorage) for `accessToken` and `idToken` means they are cleared on page reload. Implement an automatic silent-refresh on mount: call `/auth/refresh` (with `credentials: "include"`) when the app starts. If it succeeds, the user is still logged in. If it returns 401, redirect to login.

3. The refresh token is **never visible** in `document.cookie`, devtools Application → Cookies, or JavaScript. This is by design. Trust that the browser is sending it.

4. Remove any code that reads `refreshToken` from `localStorage`. It will no longer be present.

---

## Backend Configuration

### application.properties

```properties
# Set to true when deployed behind HTTPS (production)
finsight.cookie.secure=false
```

### CORS — already configured correctly

`CorsConfig.java` has:
- `allowCredentials(true)` — required for cookies to work cross-origin
- `setAllowedOrigins(List.of("http://localhost:5173"))` — explicit origin (wildcard `*` breaks credentials)

In production, replace `localhost:5173` with your actual frontend domain.

---

## Security Properties Summary

| Attack | Old (localStorage) | New (HttpOnly cookie) |
|---|---|---|
| XSS — malicious JS reads tokens | Refresh token stolen, 30 days of access | Refresh token inaccessible to JS |
| XSS — malicious JS calls `/auth/refresh` | Can do it using stolen token from localStorage | Cookie is sent, but attacker's JS cannot see the new access token |
| CSRF | Not applicable (tokens in headers, not cookies) | Mitigated by `SameSite=Strict` |
| Network interception (HTTP) | Access token exposed | Cookie not sent over HTTP (`Secure=true` in prod) |
| Token lifetime after logout | Tokens persist in localStorage until cleared | Cookie deleted server-side; Cognito global sign-out revokes refresh token |

---

## Production Checklist

- [ ] Set `finsight.cookie.secure=true` in production `application.properties` (or environment variable)
- [ ] Serve the backend over HTTPS
- [ ] Update `CorsConfig` allowed origins to the production frontend URL
- [ ] Set Cognito refresh token expiry to match your security policy (default: 30 days)
- [ ] Enable Cognito advanced security / token revocation if needed
- [ ] Remove `refreshToken` from any frontend localStorage reads/writes
- [ ] Add silent-refresh-on-app-mount logic in the frontend

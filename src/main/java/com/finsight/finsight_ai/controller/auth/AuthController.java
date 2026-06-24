package com.finsight.finsight_ai.controller.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.finsight_ai.model.auth.AuthResponse;
import com.finsight.finsight_ai.model.auth.ConfirmRegistrationRequest;
import com.finsight.finsight_ai.model.auth.ForgotPasswordInput;
import com.finsight.finsight_ai.model.auth.LoginRequest;
import com.finsight.finsight_ai.model.auth.RefreshTokenRequest;
import com.finsight.finsight_ai.model.auth.RegisterRequest;
import com.finsight.finsight_ai.model.auth.ResendCodeRequest;
import com.finsight.finsight_ai.model.auth.ResetPasswordRequest;
import com.finsight.finsight_ai.service.CognitoAuthService;
import com.finsight.finsight_ai.service.UserDocumentService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/**
 * Auth endpoints — register / login / refresh / logout / password reset.
 *
 * Security model:
 *   - accessToken  → returned in response body → frontend stores in JS memory (NOT localStorage)
 *   - idToken      → returned in response body → frontend stores in JS memory (NOT localStorage)
 *   - refreshToken → set as HttpOnly cookie    → JS cannot read it, invisible to XSS attacks
 *
 * The HttpOnly cookie is scoped to /auth/refresh so the browser sends it only on
 * token-renewal calls, minimising its exposure surface.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final CognitoAuthService authService;
    private final UserDocumentService userDocumentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cookie name used for the HttpOnly refresh token. */
    private static final String REFRESH_COOKIE = "finsight_refresh";

    /** 30 days — matches the default Cognito refresh token lifetime. */
    private static final Duration REFRESH_COOKIE_TTL = Duration.ofDays(30);

    /**
     * Set to true in production (requires HTTPS).
     * Controlled by finsight.cookie.secure in application.properties.
     */
    @Value("${finsight.cookie.secure:false}")
    private boolean cookieSecure;

    // -------------------------------------------------------------------------
    // Register / Confirm / Resend — unchanged, no cookie involvement
    // -------------------------------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/confirm")
    public ResponseEntity<AuthResponse> confirm(@RequestBody ConfirmRegistrationRequest req) {
        return ResponseEntity.ok(authService.confirmRegistration(req));
    }

    @PostMapping("/resend-code")
    public ResponseEntity<AuthResponse> resendCode(@RequestBody ResendCodeRequest req) {
        return ResponseEntity.ok(authService.resendConfirmationCode(req));
    }

    // -------------------------------------------------------------------------
    // Login — sets the refresh token as an HttpOnly cookie
    // -------------------------------------------------------------------------

    /**
     * Authenticates the user with Cognito.
     *
     * Response body contains accessToken + idToken only.
     * The refreshToken is written to an HttpOnly cookie and never exposed to JS.
     * Also triggers an async reload of the user's Chroma documents.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody LoginRequest req,
            HttpServletResponse httpResponse
    ) {
        AuthResponse full = authService.login(req);

        // Plant the refresh token in an HttpOnly cookie
        if (full.getRefreshToken() != null) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                    buildRefreshCookie(full.getRefreshToken(), REFRESH_COOKIE_TTL).toString());
        }

        // Async-load this user's documents into Chroma
        String userId = extractSubFromToken(full.getAccessToken());
        log.info("Login: userId (sub)={} email={}", userId, req.getEmail());
        userDocumentService.loadUserDocuments(userId);

        // Return tokens WITHOUT the refresh token — it now lives in the cookie
        return ResponseEntity.ok(AuthResponse.builder()
                .accessToken(full.getAccessToken())
                .idToken(full.getIdToken())
                .expiresIn(full.getExpiresIn())
                .tokenType(full.getTokenType())
                .message(full.getMessage())
                .build());
    }

    // -------------------------------------------------------------------------
    // Refresh — reads the refresh token from the HttpOnly cookie
    // -------------------------------------------------------------------------

    /**
     * Issues a new accessToken + idToken pair.
     *
     * The refreshToken is read from the HttpOnly cookie set at login — the request
     * body does NOT need to carry it.  Only `email` is needed in the body so the
     * backend can compute the Cognito SECRET_HASH.
     *
     * Backward-compatible: if the cookie is absent but the body contains a
     * refreshToken (old frontend), that value is used with a deprecation warning.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody RefreshTokenRequest req,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String cookieToken = extractRefreshCookie(httpRequest);

        if (cookieToken != null) {
            req.setRefreshToken(cookieToken);
        } else if (req.getRefreshToken() == null || req.getRefreshToken().isBlank()) {
            log.warn("Refresh attempt with no cookie and no body token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    AuthResponse.builder()
                            .message("Session expired. Please log in again.")
                            .build());
        } else {
            log.warn("Refresh using body token (deprecated — frontend should not send refreshToken in body)");
        }

        AuthResponse refreshed = authService.refreshToken(req);

        // Cognito REFRESH_TOKEN_AUTH does not rotate the refresh token, but if it ever
        // does (e.g., after enabling token rotation), re-plant it automatically.
        if (refreshed.getRefreshToken() != null) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                    buildRefreshCookie(refreshed.getRefreshToken(), REFRESH_COOKIE_TTL).toString());
        }

        return ResponseEntity.ok(AuthResponse.builder()
                .accessToken(refreshed.getAccessToken())
                .idToken(refreshed.getIdToken())
                .expiresIn(refreshed.getExpiresIn())
                .tokenType(refreshed.getTokenType())
                .message(refreshed.getMessage())
                .build());
    }

    // -------------------------------------------------------------------------
    // Forgot / Reset password — no cookie involvement
    // -------------------------------------------------------------------------

    @PostMapping("/forgot-password")
    public ResponseEntity<AuthResponse> forgotPassword(@RequestBody ForgotPasswordInput req) {
        return ResponseEntity.ok(authService.forgotPassword(req));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<AuthResponse> resetPassword(@RequestBody ResetPasswordRequest req) {
        return ResponseEntity.ok(authService.resetPassword(req));
    }

    // -------------------------------------------------------------------------
    // Logout — invalidates Cognito tokens + clears the cookie + removes Chroma docs
    // -------------------------------------------------------------------------

    /**
     * Performs a global sign-out via Cognito, removes the user's Chroma documents,
     * and clears the HttpOnly refresh cookie.
     * Requires a valid ACCESS token in the Authorization header.
     */
    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletResponse httpResponse
    ) {
        String userId = jwt.getSubject();
        log.info("Logout: userId (sub)={}", userId);

        AuthResponse response = authService.globalSignOut(jwt.getTokenValue());
        userDocumentService.removeUserDocuments(userId);

        // Expire the refresh cookie immediately
        httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                buildRefreshCookie("", Duration.ZERO).toString());

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Cookie helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the refresh-token cookie.
     * - HttpOnly   : JS cannot read it (XSS protection)
     * - Secure     : HTTPS-only (set via finsight.cookie.secure property)
     * - SameSite   : None (required for cross-domain: CloudFront → backend)
     * - Path       : /auth/refresh — browser sends cookie ONLY to this endpoint
     * - MaxAge     : 30 days on login, 0 on logout (instant expiry)
     */
    private ResponseCookie buildRefreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("None")
                .path("/auth/refresh")
                .maxAge(maxAge)
                .build();
    }

    /** Extracts the refresh token value from the incoming HttpOnly cookie, or null. */
    private String extractRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // JWT decode helper (no library — just Base64 the payload)
    // -------------------------------------------------------------------------

    /**
     * Decodes the JWT payload to extract the `sub` claim without verifying the
     * signature (Cognito already signed it; we just need the value here).
     */
    private String extractSubFromToken(String token) {
        try {
            if (token == null || !token.contains(".")) return null;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(decoded);
            return payload.has("sub") ? payload.get("sub").asText() : null;
        } catch (Exception e) {
            log.warn("Could not decode sub from access token: {}", e.getMessage());
            return null;
        }
    }
}

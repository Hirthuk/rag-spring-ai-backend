package com.finsight.finsight_ai.controller.auth;

import com.finsight.finsight_ai.model.auth.AuthResponse;
import com.finsight.finsight_ai.model.auth.ConfirmRegistrationRequest;
import com.finsight.finsight_ai.model.auth.ForgotPasswordInput;
import com.finsight.finsight_ai.model.auth.LoginRequest;
import com.finsight.finsight_ai.model.auth.RefreshTokenRequest;
import com.finsight.finsight_ai.model.auth.RegisterRequest;
import com.finsight.finsight_ai.model.auth.ResendCodeRequest;
import com.finsight.finsight_ai.model.auth.ResetPasswordRequest;
import com.finsight.finsight_ai.service.CognitoAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Public auth endpoints (register / login / refresh / password reset).
 * POST /auth/logout is the only endpoint here that requires a valid token.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CognitoAuthService authService;

    /** Step 1: Create a new Cognito user. Cognito emails a 6-digit verification code. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    /** Step 2: Confirm registration with the verification code emailed by Cognito. */
    @PostMapping("/confirm")
    public ResponseEntity<AuthResponse> confirm(@RequestBody ConfirmRegistrationRequest req) {
        return ResponseEntity.ok(authService.confirmRegistration(req));
    }

    /** Resend the email verification code if it was lost or expired. */
    @PostMapping("/resend-code")
    public ResponseEntity<AuthResponse> resendCode(@RequestBody ResendCodeRequest req) {
        return ResponseEntity.ok(authService.resendConfirmationCode(req));
    }

    /**
     * Step 3: Login — returns accessToken, idToken, refreshToken.
     * Requires USER_PASSWORD_AUTH to be enabled in the Cognito app client settings.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    /** Obtain a new accessToken + idToken using a valid refreshToken. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(authService.refreshToken(req));
    }

    /** Initiate forgot-password flow — Cognito emails a reset code. */
    @PostMapping("/forgot-password")
    public ResponseEntity<AuthResponse> forgotPassword(@RequestBody ForgotPasswordInput req) {
        return ResponseEntity.ok(authService.forgotPassword(req));
    }

    /** Complete password reset with the code received by email. */
    @PostMapping("/reset-password")
    public ResponseEntity<AuthResponse> resetPassword(@RequestBody ResetPasswordRequest req) {
        return ResponseEntity.ok(authService.resetPassword(req));
    }

    /**
     * Invalidate all tokens for the authenticated user (global sign-out).
     * Requires a valid Cognito ACCESS token in the Authorization header:
     *   Authorization: Bearer <accessToken>
     */
    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(authService.globalSignOut(jwt.getTokenValue()));
    }
}

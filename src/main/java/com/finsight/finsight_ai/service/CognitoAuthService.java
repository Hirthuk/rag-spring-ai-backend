package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.configuration.CognitoProperties;
import com.finsight.finsight_ai.model.auth.AuthResponse;
import com.finsight.finsight_ai.model.auth.ConfirmRegistrationRequest;
import com.finsight.finsight_ai.model.auth.ForgotPasswordInput;
import com.finsight.finsight_ai.model.auth.LoginRequest;
import com.finsight.finsight_ai.model.auth.RefreshTokenRequest;
import com.finsight.finsight_ai.model.auth.RegisterRequest;
import com.finsight.finsight_ai.model.auth.ResendCodeRequest;
import com.finsight.finsight_ai.model.auth.ResetPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResendConfirmationCodeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmForgotPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ForgotPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CognitoAuthService {

    private final CognitoIdentityProviderClient cognitoClient;
    private final CognitoProperties props;
    private final PinPointService pinPointService;

    // -------------------------------------------------------------------------
    // Register a new user
    // -------------------------------------------------------------------------
    public AuthResponse register(RegisterRequest req) {
        try {
            var builder = SignUpRequest.builder()
                    .clientId(props.getClientId())
                    .username(req.getEmail())
                    .password(req.getPassword())
                    .userAttributes(
                            AttributeType.builder().name("email").value(req.getEmail()).build(),
                            AttributeType.builder().name("name").value(req.getName()).build()
                    );

            withSecretHash(builder, req.getEmail(), SignUpRequest.Builder::secretHash);

            cognitoClient.signUp(builder.build());

            log.info("User registered: {}", req.getEmail());
            return AuthResponse.builder()
                    .message("Registration successful. Check your email for the confirmation code.")
                    .build();

        } catch (UsernameExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        } catch (InvalidPasswordException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password does not meet requirements: " + e.getMessage());
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito register error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Confirm registration with email verification code
    // -------------------------------------------------------------------------
    public AuthResponse confirmRegistration(ConfirmRegistrationRequest req) {
        try {
            var builder = ConfirmSignUpRequest.builder()
                    .clientId(props.getClientId())
                    .username(req.getEmail())
                    .confirmationCode(req.getConfirmationCode());

            withSecretHash(builder, req.getEmail(), ConfirmSignUpRequest.Builder::secretHash);

            cognitoClient.confirmSignUp(builder.build());

            log.info("User confirmed: {}", req.getEmail());
            pinPointService.SendWelcomeEmail(req.getEmail());
            return AuthResponse.builder().message("Email confirmed. You can now log in.").build();

        } catch (CodeMismatchException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid confirmation code.");
        } catch (ExpiredCodeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirmation code has expired. Request a new one.");
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito confirm error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Resend confirmation code (if the email was lost or expired)
    // -------------------------------------------------------------------------
    public AuthResponse resendConfirmationCode(ResendCodeRequest req) {
        try {
            var builder = ResendConfirmationCodeRequest.builder()
                    .clientId(props.getClientId())
                    .username(req.getEmail());

            withSecretHash(builder, req.getEmail(), ResendConfirmationCodeRequest.Builder::secretHash);

            cognitoClient.resendConfirmationCode(builder.build());

            log.info("Confirmation code resent to: {}", req.getEmail());
            return AuthResponse.builder()
                    .message("A new confirmation code has been sent to your email.")
                    .build();

        } catch (UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No account found for this email.");
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito resend-code error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Login — USER_PASSWORD_AUTH flow (must be enabled in Cognito app client)
    // -------------------------------------------------------------------------
    public AuthResponse login(LoginRequest req) {
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", req.getEmail());
            authParams.put("PASSWORD", req.getPassword());
            addSecretHashParam(authParams, req.getEmail());

            InitiateAuthResponse response = cognitoClient.initiateAuth(
                    InitiateAuthRequest.builder()
                            .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                            .clientId(props.getClientId())
                            .authParameters(authParams)
                            .build()
            );

            AuthenticationResultType result = response.authenticationResult();
            log.info("User logged in: {}", req.getEmail());

            return AuthResponse.builder()
                    .accessToken(result.accessToken())
                    .idToken(result.idToken())
                    .refreshToken(result.refreshToken())
                    .expiresIn(result.expiresIn())
                    .tokenType(result.tokenType())
                    .message("Login successful.")
                    .build();

        } catch (NotAuthorizedException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        } catch (UserNotConfirmedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account not confirmed. Check your email for the verification code.");
        } catch (UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No account found for this email.");
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito login error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Refresh access token using a refresh token
    // -------------------------------------------------------------------------
    public AuthResponse refreshToken(RefreshTokenRequest req) {
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("REFRESH_TOKEN", req.getRefreshToken());
            if (req.getEmail() != null && !req.getEmail().isBlank()) {
                addSecretHashParam(authParams, req.getEmail());
            }

            InitiateAuthResponse response = cognitoClient.initiateAuth(
                    InitiateAuthRequest.builder()
                            .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                            .clientId(props.getClientId())
                            .authParameters(authParams)
                            .build()
            );

            AuthenticationResultType result = response.authenticationResult();
            return AuthResponse.builder()
                    .accessToken(result.accessToken())
                    .idToken(result.idToken())
                    .expiresIn(result.expiresIn())
                    .tokenType(result.tokenType())
                    .message("Token refreshed.")
                    .build();

        } catch (NotAuthorizedException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired. Please log in again.");
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito refresh error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Initiate forgot-password flow (Cognito emails a reset code)
    // -------------------------------------------------------------------------
    public AuthResponse forgotPassword(ForgotPasswordInput req) {
        try {
            var builder = ForgotPasswordRequest.builder()
                    .clientId(props.getClientId())
                    .username(req.getEmail());

            withSecretHash(builder, req.getEmail(), ForgotPasswordRequest.Builder::secretHash);

            cognitoClient.forgotPassword(builder.build());

            log.info("Forgot-password initiated for: {}", req.getEmail());
            return AuthResponse.builder()
                    .message("Password reset code sent to your email.")
                    .build();

        } catch (UserNotFoundException e) {
            // Don't leak whether the email exists
            return AuthResponse.builder()
                    .message("If an account exists, a reset code has been sent.")
                    .build();
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito forgot-password error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Complete password reset with the code received by email
    // -------------------------------------------------------------------------
    public AuthResponse resetPassword(ResetPasswordRequest req) {
        try {
            var builder = ConfirmForgotPasswordRequest.builder()
                    .clientId(props.getClientId())
                    .username(req.getEmail())
                    .confirmationCode(req.getConfirmationCode())
                    .password(req.getNewPassword());

            withSecretHash(builder, req.getEmail(), ConfirmForgotPasswordRequest.Builder::secretHash);

            cognitoClient.confirmForgotPassword(builder.build());

            log.info("Password reset for: {}", req.getEmail());
            return AuthResponse.builder()
                    .message("Password reset successful. You can now log in with your new password.")
                    .build();

        } catch (CodeMismatchException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reset code.");
        } catch (ExpiredCodeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset code has expired. Initiate forgot-password again.");
        } catch (InvalidPasswordException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password does not meet requirements: " + e.getMessage());
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito reset-password error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Global sign-out (invalidates all tokens for the user)
    // -------------------------------------------------------------------------
    public AuthResponse globalSignOut(String accessToken) {
        try {
            cognitoClient.globalSignOut(
                    GlobalSignOutRequest.builder()
                            .accessToken(accessToken)
                            .build()
            );
            log.info("Global sign-out completed.");
            return AuthResponse.builder().message("Logged out from all devices.").build();

        } catch (NotAuthorizedException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token is invalid or already expired.");
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito sign-out error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.awsErrorDetails().errorMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean hasClientSecret() {
        return props.getClientSecret() != null && !props.getClientSecret().isBlank();
    }

    /** Compute HMAC-SHA256(username + clientId, clientSecret) as required by Cognito. */
    private String computeSecretHash(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.getClientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(username.getBytes(StandardCharsets.UTF_8));
            byte[] raw = mac.doFinal(props.getClientId().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Cognito SECRET_HASH", e);
        }
    }

    private void addSecretHashParam(Map<String, String> params, String username) {
        if (hasClientSecret()) {
            params.put("SECRET_HASH", computeSecretHash(username));
        }
    }

    @FunctionalInterface
    private interface SecretHashSetter<B> {
        B apply(B builder, String hash);
    }

    private <B> void withSecretHash(B builder, String username, SecretHashSetter<B> setter) {
        if (hasClientSecret()) {
            setter.apply(builder, computeSecretHash(username));
        }
    }
}

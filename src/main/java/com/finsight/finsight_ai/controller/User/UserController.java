package com.finsight.finsight_ai.controller.User;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class UserController {

    /**
     * Returns the authenticated user's profile extracted from the Cognito JWT.
     * Send: Authorization: Bearer <idToken>
     */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("sub", jwt.getSubject());
        profile.put("email", jwt.getClaim("email"));
        profile.put("emailVerified", jwt.getClaim("email_verified"));
        profile.put("name", jwt.getClaim("name"));
        profile.put("username", jwt.getClaim("cognito:username"));
        profile.put("tokenUse", jwt.getClaim("token_use"));
        profile.put("issuedAt", jwt.getIssuedAt());
        profile.put("expiresAt", jwt.getExpiresAt());
        return profile;
    }
}

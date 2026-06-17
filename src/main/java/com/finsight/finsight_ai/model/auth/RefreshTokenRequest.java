package com.finsight.finsight_ai.model.auth;

import lombok.Data;

@Data
public class RefreshTokenRequest {
    private String refreshToken;
    /** Required when the app client has a client secret configured. */
    private String email;
}

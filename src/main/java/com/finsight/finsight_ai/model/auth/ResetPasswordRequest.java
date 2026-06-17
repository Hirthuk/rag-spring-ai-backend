package com.finsight.finsight_ai.model.auth;

import lombok.Data;

@Data
public class ResetPasswordRequest {
    private String email;
    private String confirmationCode;
    private String newPassword;
}

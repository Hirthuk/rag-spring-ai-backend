package com.finsight.finsight_ai.model.auth;

import lombok.Data;

@Data
public class ConfirmRegistrationRequest {
    private String email;
    private String confirmationCode;
}

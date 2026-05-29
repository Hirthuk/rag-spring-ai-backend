package com.finsight.finsight_ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FrontendRequest {

    private String systemMessage;
    private String userMessage;
    private String conversationId;
}

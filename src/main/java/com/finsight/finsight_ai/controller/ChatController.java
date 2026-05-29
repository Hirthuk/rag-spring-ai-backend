package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.model.FrontendRequest;
import com.finsight.finsight_ai.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    @PostMapping  // Changed from @GetMapping to @PostMapping
    public ChatResponse chat(@RequestBody FrontendRequest request) {
        String systemMessage = request.getSystemMessage();
        String userMessage = request.getUserMessage();
        String conversationId = request.getConversationId();
        log.info("Received chat request - User: {}, System message present: {}",
                userMessage, systemMessage != null && !systemMessage.isEmpty());

        return chatService.askQuestion(conversationId, userMessage, systemMessage);
    }
}
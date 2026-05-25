package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin("*")
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public ChatResponse chat(@RequestParam String message) {
        return chatService.askQuestion(message);
    }
}

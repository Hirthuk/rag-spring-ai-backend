package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.model.FrontendRequest;
import com.finsight.finsight_ai.service.ChatService;
import com.finsight.finsight_ai.service.search.TavilySearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final TavilySearchService tavilySearchService;
    @PostMapping  // Changed from @GetMapping to @PostMapping
    public ChatResponse chat(@RequestBody FrontendRequest request) {
        String systemMessage = request.getSystemMessage();
        String userMessage = request.getUserMessage();
        String conversationId = request.getConversationId();
        log.info("Received chat request - User: {}, System message present: {}",
                userMessage, systemMessage != null && !systemMessage.isEmpty());

        return chatService.askQuestion(conversationId, userMessage, systemMessage);
    }

    /**
     * Streaming endpoint - returns Server-Sent Events so the frontend can render the
     * answer token-by-token (typing effect). Consume with EventSource/fetch on the
     * frontend; listen for "token", "chart", "done", and "error" events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody FrontendRequest request) {
        String systemMessage = request.getSystemMessage();
        String userMessage = request.getUserMessage();
        String conversationId = request.getConversationId();
        log.info("Received STREAM chat request - User: {}", userMessage);

        return chatService.streamQuestion(conversationId, userMessage, systemMessage);
    }

    @PostMapping("/tavily")
    public String TavilyResponse(@RequestBody FrontendRequest request) {
        String userMessage = request.getUserMessage();
        return tavilySearchService.search(userMessage);
    }
}
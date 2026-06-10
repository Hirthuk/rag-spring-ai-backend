package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.service.memory.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final MemoryService memoryService;

    @GetMapping("/memory/{conversationId}")
    public String getMemoryStatus(@PathVariable String conversationId) {
        int messageCount = memoryService.getMessageCount(conversationId);
        String history = memoryService.getFormattedHistory(conversationId);

        return String.format(
                "Conversation: %s\nMessage Count: %d\n\nHistory:\n%s",
                conversationId, messageCount, history
        );
    }

    @DeleteMapping("/memory/{conversationId}")
    public String clearMemory(@PathVariable String conversationId) {
        memoryService.clearHistory(conversationId);
        return "Memory cleared for conversation: " + conversationId;
    }
}
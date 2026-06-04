package com.finsight.finsight_ai.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelDebugger implements CommandLineRunner {

    private final ChatModel chatModel;

    @Override
    public void run(String... args) {
        log.info("CHAT MODEL FOUND => {}", chatModel.getClass().getName());
    }
}

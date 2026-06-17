package com.finsight.finsight_ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.*;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PinPointService {

    private final PinpointClient pinpointClient;

    @Value("${aws.pinpoint.application-id}")
    private String pinpointApplicationId;

    public void SendWelcomeEmail(String email) {
        AddressConfiguration address = AddressConfiguration.builder()
                .channelType("EMAIL")
                .build();

        Map<String, AddressConfiguration> addresses = Map.of(email, address);

        SimpleEmailPart subject = SimpleEmailPart.builder()
                .data("Welcome to Finsight!")
                .charset("UTF-8")
                .build();
        SimpleEmailPart htmlBody = SimpleEmailPart.builder()
                .data("<html><body><h1>Welcome, " + "From Finsight" + "!</h1><p>Thank you for joining Finsight. We're excited to have you on board.</p></body></html>")
                .charset("UTF-8")
                .build();

        SimpleEmail emailMessage = SimpleEmail.builder().subject(subject).htmlPart(htmlBody).build();

        EmailMessage message = EmailMessage.builder().simpleEmail(emailMessage).build();

        DirectMessageConfiguration directConfig = DirectMessageConfiguration.builder().emailMessage(message).build();

        MessageRequest request = MessageRequest.builder()
                .addresses(addresses)
                .messageConfiguration(directConfig)
                .build();

        SendMessagesRequest sendMessagesRequest = SendMessagesRequest.builder()
                .applicationId(pinpointApplicationId)
                .messageRequest(request)
                .build();

        pinpointClient.sendMessages(sendMessagesRequest);
    }

}

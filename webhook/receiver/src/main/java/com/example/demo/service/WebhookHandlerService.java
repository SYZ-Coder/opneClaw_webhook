package com.example.demo.service;

import com.example.demo.model.WebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WebhookHandlerService {

    private final Map<String, WebhookPayload> receivedMessages = new ConcurrentHashMap<>();
    
    @Value("${receiver.openclaw.webhook.secret}")
    private String webhookSecret;

    /**
     * Process an incoming webhook from OpenClaw
     */
    public WebhookPayload processWebhook(WebhookPayload payload) {
        log.info("Processing webhook from OpenClaw: {}", payload);
        
        // Validate webhook if secret is configured
        if (webhookSecret != null && !webhookSecret.isEmpty()) {
            // In a real implementation, you would validate the signature here
            log.debug("Webhook secret validation would happen here");
        }
        
        // Store the received message
        String messageId = payload.getSessionKey() != null ? 
                payload.getSessionKey() : "unknown-" + System.currentTimeMillis();
        receivedMessages.put(messageId, payload);
        
        log.info("Webhook processed successfully. Message ID: {}", messageId);
        
        return payload;
    }

    /**
     * Get all received messages
     */
    public Map<String, WebhookPayload> getReceivedMessages() {
        return new ConcurrentHashMap<>(receivedMessages);
    }

    /**
     * Clear received messages
     */
    public void clearMessages() {
        receivedMessages.clear();
        log.info("Cleared all received messages");
    }

    /**
     * Get a specific message by ID
     */
    public WebhookPayload getMessage(String messageId) {
        return receivedMessages.get(messageId);
    }
}
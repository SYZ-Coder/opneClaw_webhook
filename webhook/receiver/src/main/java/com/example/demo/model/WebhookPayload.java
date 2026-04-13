package com.example.demo.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebhookPayload {
    private String text;           // Description of the event
    private String message;        // The prompt or message for the agent
    private String name;           // Human-readable name for the hook
    private String agentId;        // Route to specific agent
    private String sessionKey;     // Session key for the agent run
    private String wakeMode;       // "now" or "next-heartbeat"
    private Boolean deliver;       // Whether to deliver response to channel
    private String channel;        // Messaging channel for delivery
    private String to;            // Recipient identifier
    private String model;         // Model override
    private String thinking;      // Thinking level override
    private Integer timeoutSeconds; // Maximum duration for agent run
    private String status;        // Status of the webhook processing
    private String error;         // Error message if any
    private Long timestamp;       // When the webhook was received
    private Object data;          // Additional data
}
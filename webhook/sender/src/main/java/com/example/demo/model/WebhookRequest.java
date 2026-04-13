package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookRequest {
    private String text;            // For /wake endpoint
    private String message;         // For /agent endpoint
    private String name;            // Human-readable name for the hook
    private String agentId;         // Route to specific agent
    private String sessionKey;      // Session key for the agent run
    private String mode;            // Wake mode used by /wake endpoint
    private String wakeMode;        // "now" or "next-heartbeat"
    private Boolean deliver;        // Whether to deliver response to channel
    private String channel;         // Messaging channel for delivery
    private String to;              // Recipient identifier
    private String model;           // Model override
    private String thinking;        // Thinking level override
    private Integer timeoutSeconds; // Maximum duration for agent run
    private String endpoint;        // Custom endpoint for mapped hooks
    private Object source;          // Source for mapped hooks
    private Object data;            // Additional data for mapped hooks
}

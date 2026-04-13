package com.example.demo.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebhookResponse {
    private Boolean ok;
    private String runId;
    private String status;
    private String finalText;
    private String sessionId;
    private String message;
    private String error;
    private Long timestamp;
    private Object data;
}

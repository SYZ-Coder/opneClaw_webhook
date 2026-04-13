package com.example.demo.controller;

import com.example.demo.model.WebhookPayload;
import com.example.demo.service.WebhookHandlerService;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
@CrossOrigin(origins = "*")
public class WebhookController {

    @Autowired
    private WebhookHandlerService webhookHandlerService;

    /**
     * Receive webhook from OpenClaw
     */
    @PostMapping("/receive")
    public ResponseEntity<WebhookResponse> receiveWebhook(@RequestBody WebhookPayload payload) {
        log.info("Received webhook from OpenClaw: {}", payload);
        
        try {
            WebhookPayload processed = webhookHandlerService.processWebhook(payload);
            WebhookResponse response = WebhookResponse.builder()
                    .status("processed")
                    .message("Webhook processed successfully")
                    .data(processed)
                    .build();
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            WebhookResponse response = WebhookResponse.builder()
                    .status("error")
                    .message("Failed to process webhook: " + e.getMessage())
                    .build();
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get all received webhooks
     */
    @GetMapping("/messages")
    public ResponseEntity<Map<String, WebhookPayload>> getMessages() {
        log.info("Getting all received messages");
        return ResponseEntity.ok(webhookHandlerService.getReceivedMessages());
    }

    /**
     * Get a specific message by ID
     */
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<WebhookPayload> getMessage(@PathVariable String messageId) {
        log.info("Getting message by ID: {}", messageId);
        WebhookPayload message = webhookHandlerService.getMessage(messageId);
        
        if (message != null) {
            return ResponseEntity.ok(message);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Clear all received messages
     */
    @DeleteMapping("/messages")
    public ResponseEntity<Void> clearMessages() {
        log.info("Clearing all received messages");
        webhookHandlerService.clearMessages();
        return ResponseEntity.ok().build();
    }

    // Response DTO
    public static class WebhookResponse {
        private String status;
        private String message;
        private Object data;

        public static WebhookResponseBuilder builder() {
            return new WebhookResponseBuilder();
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }

        public static class WebhookResponseBuilder {
            private String status;
            private String message;
            private Object data;

            public WebhookResponseBuilder status(String status) {
                this.status = status;
                return this;
            }

            public WebhookResponseBuilder message(String message) {
                this.message = message;
                return this;
            }

            public WebhookResponseBuilder data(Object data) {
                this.data = data;
                return this;
            }

            public WebhookResponse build() {
                WebhookResponse response = new WebhookResponse();
                response.status = this.status;
                response.message = this.message;
                response.data = this.data;
                return response;
            }
        }
    }
}
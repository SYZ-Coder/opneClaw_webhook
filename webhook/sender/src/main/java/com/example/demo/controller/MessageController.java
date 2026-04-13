package com.example.demo.controller;

import com.example.demo.model.WebhookRequest;
import com.example.demo.model.WebhookResponse;
import com.example.demo.service.OpenClawWebhookService;
import com.example.demo.service.WebhookRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/message")
@CrossOrigin(origins = "*")
public class MessageController {

    @Autowired
    private OpenClawWebhookService openClawWebhookService;

    /**
     * Send a simple wake request to OpenClaw
     */
    @PostMapping("/wake")
    public Mono<ResponseEntity<WebhookResponse>> sendWake(@RequestBody @Valid WakeRequest request) {
        log.info("Received wake request: {}", request);
        
        return openClawWebhookService.sendWakeRequest(request.getText())
                .map(response -> ResponseEntity.ok(response))
                .onErrorResume(WebhookRequestException.class,
                        ex -> Mono.just(ResponseEntity.status(ex.getStatusCode()).body(ex.getResponse())))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    /**
     * Send an agent request to OpenClaw
     */
    @PostMapping("/agent")
    public Mono<ResponseEntity<WebhookResponse>> sendAgent(@RequestBody @Valid AgentRequest request) {
        log.info("Received agent request: {}", request);
        
        return openClawWebhookService.sendAgentRequest(request.getMessage(), request.getAgentId())
                .map(response -> ResponseEntity.ok(response))
                .onErrorResume(WebhookRequestException.class,
                        ex -> Mono.just(ResponseEntity.status(ex.getStatusCode()).body(ex.getResponse())))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    /**
     * RPC 版：提交 agent 并等待最终结果。
     *
     * 与 /api/message/agent 的区别：
     * - /agent：走 webhook，先返回 ACK，再由服务端轮询会话补结果。
     * - /agent/rpc：走 gateway rpc（agent + agent.wait），优先直接拿最终状态。
     */
    @PostMapping("/agent/rpc")
    public Mono<ResponseEntity<WebhookResponse>> sendAgentRpc(@RequestBody @Valid AgentRpcRequest request) {
        log.info("Received agent rpc request: {}", request);

        return openClawWebhookService.sendAgentRpcRequest(
                        request.getMessage(),
                        request.getAgentId(),
                        request.getSessionKey(),
                        request.getSubmitTimeoutMs(),
                        request.getWaitTimeoutMs())
                .map(response -> {
                    if (Boolean.FALSE.equals(response.getOk())) {
                        return ResponseEntity.status(502).body(response);
                    }
                    return ResponseEntity.ok(response);
                })
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    /**
     * RPC 文本版：只返回最终文本，方便前端/脚本直接展示。
     * Content-Type: text/plain
     */
    @PostMapping(value = "/agent/rpc/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> sendAgentRpcText(@RequestBody @Valid AgentRpcRequest request) {
        log.info("Received agent rpc text request: {}", request);
        return openClawWebhookService.sendAgentRpcRequest(
                        request.getMessage(),
                        request.getAgentId(),
                        request.getSessionKey(),
                        request.getSubmitTimeoutMs(),
                        request.getWaitTimeoutMs())
                .map(response -> {
                    String text = extractFinalText(response);
                    if (text == null || text.trim().isEmpty()) {
                        return ResponseEntity.status(204).body("");
                    }
                    return ResponseEntity.ok(text);
                })
                .defaultIfEmpty(ResponseEntity.status(204).body(""));
    }

    /**
     * Stream agent execution lifecycle using SSE
     */
    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<WebhookResponse>> streamAgent(@RequestBody @Valid AgentRequest request) {
        log.info("Received stream agent request: {}", request);
        return openClawWebhookService.streamAgentRequest(request.getMessage(), request.getAgentId());
    }

    /**
     * Query agent execution result by runId
     */
    @GetMapping("/result/{runId}")
    public Mono<ResponseEntity<WebhookResponse>> getResult(
            @PathVariable("runId") String runId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "wait", required = false, defaultValue = "true") boolean wait) {
        log.info("Received result query: runId={}, agentId={}, wait={}", runId, agentId, wait);
        return openClawWebhookService.queryAgentResult(runId, agentId, wait)
                .map(response -> {
                    if ("not_found".equalsIgnoreCase(response.getStatus())) {
                        return ResponseEntity.status(404).body(response);
                    }
                    return ResponseEntity.ok(response);
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Send a custom webhook request
     */
    @PostMapping("/custom")
    public Mono<ResponseEntity<WebhookResponse>> sendCustom(@RequestBody @Valid WebhookRequest request) {
        log.info("Received custom request: {}", request);
        
        return openClawWebhookService.sendCustomRequest(request)
                .map(response -> ResponseEntity.ok(response))
                .onErrorResume(WebhookRequestException.class,
                        ex -> Mono.just(ResponseEntity.status(ex.getStatusCode()).body(ex.getResponse())))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    // Request DTOs
    public static class WakeRequest {
        @NotBlank
        private String text;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    public static class AgentRequest {
        @NotBlank
        private String message;
        
        private String agentId = "hooks";

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
    }

    public static class AgentRpcRequest {
        @NotBlank
        private String message; // 发送给 agent 的用户消息

        private String agentId = "director"; // 目标 agent，默认 director
        private String sessionKey;           // 会话键；若与 agent 不匹配，服务端会自动重试（不带 sessionKey）
        private Long submitTimeoutMs;        // agent 提交阶段超时（毫秒）
        private Long waitTimeoutMs;          // agent.wait 阶段超时（毫秒）

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getSessionKey() { return sessionKey; }
        public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
        public Long getSubmitTimeoutMs() { return submitTimeoutMs; }
        public void setSubmitTimeoutMs(Long submitTimeoutMs) { this.submitTimeoutMs = submitTimeoutMs; }
        public Long getWaitTimeoutMs() { return waitTimeoutMs; }
        public void setWaitTimeoutMs(Long waitTimeoutMs) { this.waitTimeoutMs = waitTimeoutMs; }
    }

    private String extractFinalText(WebhookResponse response) {
        // 文本接口的兜底提取策略：先看 message，再看 data.assistantReply。
        if (response == null) {
            return null;
        }
        if (response.getMessage() != null && !response.getMessage().trim().isEmpty()) {
            return response.getMessage().trim();
        }
        Object data = response.getData();
        if (data instanceof Map) {
            Object assistantReply = ((Map<?, ?>) data).get("assistantReply");
            if (assistantReply != null) {
                String text = String.valueOf(assistantReply).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }
}

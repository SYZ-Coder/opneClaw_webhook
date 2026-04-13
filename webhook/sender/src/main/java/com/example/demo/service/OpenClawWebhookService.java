package com.example.demo.service;

import com.example.demo.model.WebhookRequest;
import com.example.demo.model.WebhookResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Slf4j
@Service
public class OpenClawWebhookService {

    private final WebClient webClient;
    private final String openclawUrl;
    private final String token;
    private final ObjectMapper objectMapper;
    private final String openclawHome;
    private final String defaultHookSessionKey;
    private final boolean waitForAgentResult;
    private final long resultTimeoutMs;
    private final long resultPollIntervalMs;
    private final String gatewayCommand;
    private final long rpcSubmitTimeoutMs;
    private final long rpcWaitTimeoutMs;
    private final ConcurrentMap<String, RunResultState> runResults = new ConcurrentHashMap<String, RunResultState>();

    public OpenClawWebhookService(
            @Value("${openclaw.webhook.url}") String openclawUrl,
            @Value("${openclaw.webhook.token}") String token,
            @Value("${openclaw.local.home:${user.home}/.openclaw}") String openclawHome,
            @Value("${openclaw.webhook.default-session-key:hook:springboot-demo}") String defaultHookSessionKey,
            @Value("${openclaw.webhook.wait-result:true}") boolean waitForAgentResult,
            @Value("${openclaw.webhook.wait-timeout-ms:30000}") long resultTimeoutMs,
            @Value("${openclaw.webhook.wait-poll-ms:400}") long resultPollIntervalMs,
            @Value("${openclaw.rpc.command:openclaw}") String gatewayCommand,
            @Value("${openclaw.rpc.submit-timeout-ms:10000}") long rpcSubmitTimeoutMs,
            @Value("${openclaw.rpc.wait-timeout-ms:30000}") long rpcWaitTimeoutMs,
            ObjectMapper objectMapper) {
        this.openclawUrl = normalizeWebhookBaseUrl(openclawUrl);
        this.token = token;
        this.objectMapper = objectMapper;
        this.openclawHome = openclawHome;
        this.defaultHookSessionKey = defaultHookSessionKey;
        this.waitForAgentResult = waitForAgentResult;
        this.resultTimeoutMs = resultTimeoutMs;
        this.resultPollIntervalMs = resultPollIntervalMs;
        this.gatewayCommand = gatewayCommand;
        this.rpcSubmitTimeoutMs = rpcSubmitTimeoutMs;
        this.rpcWaitTimeoutMs = rpcWaitTimeoutMs;

        this.webClient = WebClient.builder()
                .baseUrl(this.openclawUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();

        log.info("Configured OpenClaw webhook base URL: {}", this.openclawUrl);
    }

    /**
     * Send a wake request to OpenClaw
     */
    public Mono<WebhookResponse> sendWakeRequest(String text) {
        log.info("Sending wake request to OpenClaw: {}", text);

        WebhookRequest request = WebhookRequest.builder()
                .text(text)
                .mode("now")
                .build();

        return post("wake", request, "Wake");
    }

    /**
     * Send an agent request to OpenClaw
     */
    public Mono<WebhookResponse> sendAgentRequest(String message, String agentId) {
        log.info("Sending agent request to OpenClaw: message={}, agentId={}", message, agentId);

        WebhookRequest request = WebhookRequest.builder()
                .message(message)
                .name("SpringBoot Demo")
                .agentId(agentId)
                .wakeMode("now")
                .deliver(false)
                .build();

        long acceptedAt = System.currentTimeMillis();
        return post("agent", request, "Agent")
                .flatMap(response -> {
                    if (response != null && Boolean.TRUE.equals(response.getOk()) && response.getRunId() != null) {
                        runResults.put(response.getRunId(), new RunResultState(response.getRunId(), agentId, acceptedAt));
                    }
                    return waitAndAttachAgentResult(response, agentId, acceptedAt);
                });
    }

    /**
     * RPC 模式入口：调用 Gateway 的 agent + agent.wait。
     *
     * 处理顺序：
     * 1) 提交 agent 请求，拿到 runId。
     * 2) 调用 agent.wait 等待任务结束。
     * 3) 若 wait 没返回最终文本，则回退到本地 session transcript 提取 assistant 文本。
     */
    public Mono<WebhookResponse> sendAgentRpcRequest(String message, String agentId, String sessionKey, Long submitTimeoutMs, Long waitTimeoutMs) {
        // 优先使用请求参数中的超时，未传则走配置默认值。
        final long submitTimeout = normalizeTimeout(submitTimeoutMs, rpcSubmitTimeoutMs);
        final long waitTimeout = normalizeTimeout(waitTimeoutMs, rpcWaitTimeoutMs);
        // 兜底 agentId/sessionKey，避免空值导致 RPC 参数校验失败。
        final String resolvedAgentId = (agentId == null || agentId.trim().isEmpty()) ? "director" : agentId.trim();
        final String resolvedSessionKey = (sessionKey == null || sessionKey.trim().isEmpty()) ? defaultHookSessionKey : sessionKey.trim();

        // RPC 调用与文件读取可能阻塞，放到 boundedElastic 线程池执行。
        return Mono.fromCallable(() -> runAgentRpcAndWait(message, resolvedAgentId, resolvedSessionKey, submitTimeout, waitTimeout))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Stream an agent request lifecycle via SSE events:
     * accepted -> polling* -> completed | timeout | error
     */
    public Flux<ServerSentEvent<WebhookResponse>> streamAgentRequest(String message, String agentId) {
        log.info("Streaming agent request to OpenClaw: message={}, agentId={}", message, agentId);

        WebhookRequest request = WebhookRequest.builder()
                .message(message)
                .name("SpringBoot Demo")
                .agentId(agentId)
                .wakeMode("now")
                .deliver(false)
                .build();

        final long acceptedAt = System.currentTimeMillis();
        final long timeoutMs = Math.max(resultTimeoutMs, 1000L);
        final long pollMs = Math.max(resultPollIntervalMs, 100L);

        return post("agent", request, "Agent")
                .flatMapMany(ack -> {
                    if (ack == null || !Boolean.TRUE.equals(ack.getOk()) || ack.getRunId() == null) {
                        WebhookResponse failed = WebhookResponse.builder()
                                .ok(false)
                                .status("error")
                                .message("Agent request was not accepted by OpenClaw")
                                .timestamp(System.currentTimeMillis())
                                .build();
                        return Flux.just(toSseEvent("error", null, failed));
                    }

                    final String runId = ack.getRunId();
                    runResults.put(runId, new RunResultState(runId, agentId, acceptedAt));

                    AtomicBoolean completed = new AtomicBoolean(false);
                    Flux<ServerSentEvent<WebhookResponse>> polling = Flux.interval(Duration.ofMillis(pollMs))
                            .concatMap(tick -> queryAgentResult(runId, agentId, false))
                            .map(result -> {
                                String eventName = "polling";
                                if ("completed".equalsIgnoreCase(result.getStatus())) {
                                    eventName = "completed";
                                    completed.set(true);
                                }
                                return toSseEvent(eventName, runId, result);
                            })
                            .takeUntil(event -> "completed".equalsIgnoreCase(event.event()))
                            .take(Duration.ofMillis(timeoutMs))
                            .concatWith(Mono.defer(() -> {
                                if (completed.get()) {
                                    return Mono.empty();
                                }
                                WebhookResponse timeout = WebhookResponse.builder()
                                        .ok(true)
                                        .runId(runId)
                                        .status("pending")
                                        .message("result not ready before timeout")
                                        .timestamp(System.currentTimeMillis())
                                        .build();
                                return Mono.just(toSseEvent("timeout", runId, timeout));
                            }));

                    return Flux.concat(
                            Flux.just(toSseEvent("accepted", runId, ack)),
                            polling
                    );
                })
                .onErrorResume(WebhookRequestException.class, ex -> {
                    WebhookResponse body = ex.getResponse();
                    if (body == null) {
                        body = WebhookResponse.builder()
                                .ok(false)
                                .status("error")
                                .message("Agent request to OpenClaw failed")
                                .timestamp(System.currentTimeMillis())
                                .build();
                    }
                    return Flux.just(toSseEvent("error", body.getRunId(), body));
                })
                .onErrorResume(ex -> {
                    WebhookResponse body = WebhookResponse.builder()
                            .ok(false)
                            .status("error")
                            .message("Agent stream failed: " + ex.getMessage())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    return Flux.just(toSseEvent("error", null, body));
                });
    }

    public Mono<WebhookResponse> queryAgentResult(String runId, String agentId, boolean wait) {
        if (runId == null || runId.trim().isEmpty()) {
            return Mono.just(WebhookResponse.builder()
                    .ok(false)
                    .runId(runId)
                    .status("error")
                    .message("runId is required")
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        String normalizedRunId = runId.trim();
        RunResultState state = runResults.get(normalizedRunId);
        if (state != null && state.assistantReply != null) {
            return Mono.just(buildCompletedResult(normalizedRunId, state.assistantReply, state.agentId, state.acceptedAtMs));
        }

        if (state == null && (agentId == null || agentId.trim().isEmpty())) {
            return Mono.just(WebhookResponse.builder()
                    .ok(false)
                    .runId(normalizedRunId)
                    .status("not_found")
                    .message("runId not found in sender cache; provide agentId to attempt lookup")
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        final String lookupAgentId = state != null ? state.agentId : agentId.trim();
        final long acceptedAtMs = state != null ? state.acceptedAtMs : System.currentTimeMillis() - Duration.ofMinutes(15).toMillis();

        // Cache miss fallback: return latest assistant text from the matched hook session.
        if (state == null && (agentId != null && !agentId.trim().isEmpty())) {
            Optional<String> latest = tryReadLatestAssistantText(lookupAgentId);
            if (latest.isPresent()) {
                return Mono.just(buildCompletedResult(normalizedRunId, latest.get(), lookupAgentId, acceptedAtMs));
            }
        }

        if (!wait) {
            Optional<String> immediate = tryReadAssistantText(lookupAgentId, acceptedAtMs);
            if (!immediate.isPresent()) {
                return Mono.just(WebhookResponse.builder()
                        .ok(true)
                        .runId(normalizedRunId)
                        .status("pending")
                        .message("result not ready")
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
            cacheRunReply(normalizedRunId, lookupAgentId, acceptedAtMs, immediate.get());
            return Mono.just(buildCompletedResult(normalizedRunId, immediate.get(), lookupAgentId, acceptedAtMs));
        }

        return Mono.fromCallable(() -> waitForAssistantText(lookupAgentId, acceptedAtMs))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    if (!result.isPresent()) {
                        return WebhookResponse.builder()
                                .ok(true)
                                .runId(normalizedRunId)
                                .status("pending")
                                .message("result not ready")
                                .timestamp(System.currentTimeMillis())
                                .build();
                    }
                    cacheRunReply(normalizedRunId, lookupAgentId, acceptedAtMs, result.get());
                    return buildCompletedResult(normalizedRunId, result.get(), lookupAgentId, acceptedAtMs);
                });
    }

    /**
     * Send a custom webhook request
     */
    public Mono<WebhookResponse> sendCustomRequest(WebhookRequest request) {
        log.info("Sending custom request to OpenClaw: {}", request);

        String endpoint = Optional.ofNullable(request.getEndpoint())
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse("agent");

        return post(endpoint.replaceFirst("^/+", ""), request, "Custom");
    }

    private Mono<WebhookResponse> post(String path, WebhookRequest request, String requestType) {
        return webClient.post()
                .uri(uriBuilder -> {
                    if (path == null || path.trim().isEmpty()) {
                        return uriBuilder.build();
                    }
                    return uriBuilder.pathSegment(path.replaceFirst("^/+", "")).build();
                })
                .bodyValue(request)
                .exchangeToMono(response -> {
                    HttpStatus status = response.statusCode();
                    Mono<WebhookResponse> bodyMono = response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> toWebhookResponse(body, status, requestType, path));

                    if (status.is2xxSuccessful()) {
                        return bodyMono.doOnNext(body ->
                                log.info("{} request successful: status={}, body={}", requestType, status.value(), body));
                    }

                    return bodyMono.flatMap(body -> {
                        WebhookResponse errorResponse = enrichErrorResponse(body, status, requestType, path);
                        log.error("{} request failed: status={}, body={}", requestType, status.value(), errorResponse);
                        return Mono.error(new WebhookRequestException(status, errorResponse));
                    });
                })
                .timeout(Duration.ofSeconds(30))
                .doOnError(error -> {
                    if (!(error instanceof WebhookRequestException)) {
                        log.error("{} request failed before receiving a valid response", requestType, error);
                    }
                });
    }

    private WebhookResponse runAgentRpcAndWait(String message, String agentId, String sessionKey, long submitTimeoutMs, long waitTimeoutMs) {
        try {
            // 第一步：提交任务（agent），失败时会在 invokeAgentWithSessionFallback 内部处理重试。
            JsonNode submit = invokeAgentWithSessionFallback(message, agentId, sessionKey, submitTimeoutMs);
            String runId = submit.path("runId").asText("");
            if (runId.isEmpty()) {
                // 提交响应中没有 runId，属于协议异常，直接返回 error。
                return WebhookResponse.builder()
                        .ok(false)
                        .status("error")
                        .message("RPC agent accepted response missing runId")
                        .error(submit.toString())
                        .timestamp(System.currentTimeMillis())
                        .data(submit)
                        .build();
            }

            // 第二步：等待任务结束（agent.wait 可能只返回状态，不带文本）。
            JsonNode waitParams = objectMapper.createObjectNode()
                    .put("runId", runId)
                    .put("timeoutMs", waitTimeoutMs);
            JsonNode waited = invokeGatewayRpc("agent.wait", waitParams.toString(), waitTimeoutMs + 5000L);
            log.info("RPC submit response: {}", submit.toString());
            log.info("RPC wait response: {}", waited.toString());

            boolean ok = waited.path("ok").asBoolean(true);
            String status = waited.path("status").asText(ok ? "completed" : "error");
            String finalText = extractRpcFinalText(waited);
            long acceptedAtMs = submit.path("acceptedAt").asLong(System.currentTimeMillis());

            if ((finalText == null || finalText.trim().isEmpty()) && ok) {
                // 某些版本 agent.wait 不直接返回最终文本：
                // 先短轮询会话文件，等待 transcript 刷盘。
                Optional<String> fromSession = waitForAssistantText(agentId, acceptedAtMs);
                if (!fromSession.isPresent()) {
                    // 再兜底读取该 agent 最新 session 的 assistant 文本。
                    fromSession = tryReadLatestAssistantText(agentId);
                }
                if (fromSession.isPresent()) {
                    finalText = fromSession.get();
                    log.info("RPC final text recovered from local session: runId={}, textChars={}", runId, finalText.length());
                } else {
                    log.warn("RPC wait returned no final text and local session fallback also found nothing: runId={}, agentId={}", runId, agentId);
                }
            }

            if (ok && finalText != null && !finalText.trim().isEmpty()) {
                // 只要拿到有效文本，就统一标记 completed，便于前端判定。
                status = "completed";
            }

            WebhookResponse response = WebhookResponse.builder()
                    .ok(ok)
                    .runId(runId)
                    .status(status.isEmpty() ? (ok ? "completed" : "error") : status)
                    // 顶层 message 放最终文本，兼容现有调用方。
                    .message(finalText.isEmpty() ? "agent.wait finished without final text" : finalText)
                    .error(ok ? null : waited.path("error").asText(null))
                    .timestamp(System.currentTimeMillis())
                    .build();

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            // data 中保留调试信息，方便排障和后续扩展。
            payload.put("mode", "rpc-agent-wait");
            payload.put("agentId", agentId);
            payload.put("sessionKey", sessionKey);
            payload.put("acceptedAtMs", acceptedAtMs);
            payload.put("assistantReply", finalText);
            payload.put("source", "rpc+openclaw-session");
            payload.put("submitResponse", submit);
            payload.put("waitResponse", waited);
            response.setData(payload);
            return response;
        } catch (Exception ex) {
            log.error("RPC agent.wait flow failed", ex);
            // 所有未捕获异常都转换为结构化错误响应，避免直接 500。
            return WebhookResponse.builder()
                    .ok(false)
                    .status("error")
                    .message("RPC agent.wait failed")
                    .error(ex.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    private JsonNode invokeAgentWithSessionFallback(String message, String agentId, String sessionKey, long submitTimeoutMs) throws Exception {
        try {
            // 优先按调用方传入的 sessionKey 提交。
            return invokeGatewayRpc("agent", buildAgentSubmitParams(message, agentId, sessionKey), submitTimeoutMs);
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? "" : ex.getMessage();
            boolean sessionAgentMismatch = error.contains("does not match session key agent");
            if (!sessionAgentMismatch) {
                throw ex;
            }
            // 常见错误：sessionKey 归属 agent 与请求 agentId 不一致。
            // 这里自动重试一次（不带 sessionKey），提高可用性。
            log.warn("RPC agent/session mismatch detected, retrying without sessionKey. agentId={}, sessionKey={}", agentId, sessionKey);
            return invokeGatewayRpc("agent", buildAgentSubmitParams(message, agentId, null), submitTimeoutMs);
        }
    }

    private String buildAgentSubmitParams(String message, String agentId, String sessionKey) throws Exception {
        // 组装 RPC 参数。idempotencyKey 在新版本为必填字段。
        ObjectNode submitParams = objectMapper.createObjectNode()
                .put("agentId", agentId)
                .put("message", message)
                // Newer OpenClaw RPC validates idempotency for agent submission.
                .put("idempotencyKey", UUID.randomUUID().toString());
        if (sessionKey != null && !sessionKey.trim().isEmpty()) {
            submitParams.put("sessionKey", sessionKey.trim());
        }
        return submitParams.toString();
    }

    private JsonNode invokeGatewayRpc(String method, String paramsJson, long timeoutMs) throws Exception {
        log.info("Invoke Gateway RPC: method={}, timeoutMs={}", method, timeoutMs);
        // 命令候选：openclaw/openclaw.cmd/绝对路径等，按顺序尝试。
        List<String> commandCandidates = resolveGatewayCommandCandidates();
        String output = null;
        Exception startFailure = null;
        String usedCommand = null;

        for (String candidate : commandCandidates) {
            List<String> command = buildGatewayRpcCommand(candidate, method, paramsJson, timeoutMs);
            try {
                output = executeCommand(command, timeoutMs + 5000L);
                usedCommand = candidate;
                break;
            } catch (IOException ex) {
                if (isCommandNotFound(ex)) {
                    startFailure = ex;
                    log.debug("Gateway command candidate not found: {}", candidate);
                    continue;
                }
                throw ex;
            }
        }

        if (output == null) {
            throw new IllegalStateException("Cannot find executable for OpenClaw RPC command. Tried: " + commandCandidates, startFailure);
        }
        if (!gatewayCommand.equals(usedCommand)) {
            // 记录实际命中的可执行文件，方便环境问题排障。
            log.info("Gateway RPC command fallback selected: {}", usedCommand);
        }

        String jsonBody = extractJsonBody(output);
        return objectMapper.readTree(jsonBody);
    }

    private List<String> buildGatewayRpcCommand(String commandName, String method, String paramsJson, long timeoutMs) {
        List<String> command = new ArrayList<String>();
        command.add(commandName);
        command.add("gateway");
        command.add("call");
        command.add(method);
        command.add("--json");
        command.add("--params");
        command.add(adaptParamsForCommand(commandName, paramsJson));
        command.add("--timeout");
        command.add(String.valueOf(timeoutMs));
        return command;
    }

    private String adaptParamsForCommand(String commandName, String paramsJson) {
        if (commandName == null) {
            return paramsJson;
        }
        String lower = commandName.toLowerCase();
        if (lower.endsWith(".cmd")) {
            // Windows 的 npm .cmd shim 可能破坏 JSON 引号，这里做 cmd 安全转义。
            return "\"" + paramsJson.replace("\"", "\"\"") + "\"";
        }
        return paramsJson;
    }

    private List<String> resolveGatewayCommandCandidates() {
        List<String> candidates = new ArrayList<String>();
        String configured = gatewayCommand == null ? "" : gatewayCommand.trim();
        if (configured.isEmpty()) {
            configured = "openclaw";
        }
        candidates.add(configured);

        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean windows = osName.contains("win");
        if (!windows) {
            return candidates;
        }

        if ("openclaw".equalsIgnoreCase(configured)) {
            // Windows 常见命令名补充。
            candidates.add("openclaw.cmd");
            candidates.add("openclaw.exe");
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.trim().isEmpty()) {
            String npmCmd = Paths.get(userHome, "AppData", "Roaming", "npm", "openclaw.cmd").toString();
            if (!candidates.contains(npmCmd)) {
                candidates.add(npmCmd);
            }
        }
        return candidates;
    }

    private boolean isCommandNotFound(IOException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("createprocess error=2")
                || lower.contains("cannot run program")
                || lower.contains("no such file");
    }

    private String executeCommand(List<String> command, long waitTimeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        // 合并 stdout/stderr，避免丢失 CLI 错误信息。
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = readProcessOutput(process);
        boolean finished = process.waitFor(waitTimeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timeout: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + ", output=" + output);
        }
        return output;
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private String extractJsonBody(String output) {
        if (output == null || output.trim().isEmpty()) {
            throw new IllegalStateException("Gateway RPC output is empty");
        }
        // openclaw CLI 可能会输出 banner，取第一个 JSON 对象起点作为实际响应体。
        int start = output.indexOf('{');
        if (start < 0) {
            throw new IllegalStateException("Gateway RPC output has no JSON body: " + output);
        }
        return output.substring(start).trim();
    }

    private String extractRpcFinalText(JsonNode waited) {
        // 先按已知字段提取（最快、最稳定）。
        String[] candidates = new String[] {
                waited.path("result").path("text").asText(""),
                waited.path("result").path("message").asText(""),
                waited.path("result").path("outputText").asText(""),
                waited.path("message").asText("")
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }

        Optional<String> deepText = findFirstText(waited.path("result"), 0);
        if (deepText.isPresent()) {
            return deepText.get();
        }

        // 没提取到文本时返回空串，交给会话兜底分支继续处理。
        return "";
    }

    private Optional<String> findFirstText(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (depth > 8) {
            return Optional.empty();
        }

        if (node.isTextual()) {
            String text = node.asText("");
            if (text != null && !text.trim().isEmpty()) {
                return Optional.of(text.trim());
            }
            return Optional.empty();
        }

        final Set<String> preferredKeys = new HashSet<String>(Arrays.asList(
                "assistantReply", "finalText", "outputText", "text", "message", "content"
        ));

        if (node.isObject()) {
            for (String key : preferredKeys) {
                JsonNode child = node.get(key);
                Optional<String> text = findFirstText(child, depth + 1);
                if (text.isPresent()) {
                    return text;
                }
            }
            return Optional.empty();
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> text = findFirstText(child, depth + 1);
                if (text.isPresent()) {
                    return text;
                }
            }
        }
        return Optional.empty();
    }

    private long normalizeTimeout(Long requested, long fallback) {
        if (requested == null || requested.longValue() <= 0L) {
            return fallback;
        }
        return requested.longValue();
    }

    private WebhookResponse enrichErrorResponse(WebhookResponse body, HttpStatus status, String requestType, String path) {
        WebhookResponse response = body != null ? body : WebhookResponse.builder().build();
        if (response.getStatus() == null || response.getStatus().trim().isEmpty()) {
            response.setStatus("error");
        }
        if (response.getMessage() == null || response.getMessage().trim().isEmpty()) {
            response.setMessage(requestType + " request to OpenClaw failed");
        }
        if (response.getError() == null || response.getError().trim().isEmpty()) {
            response.setError("HTTP " + status.value() + " from /hooks/" + path);
        }
        if (response.getTimestamp() == null) {
            response.setTimestamp(System.currentTimeMillis());
        }
        return response;
    }

    private WebhookResponse toWebhookResponse(String body, HttpStatus status, String requestType, String path) {
        String rawBody = body == null ? "" : body.trim();
        if (rawBody.isEmpty()) {
            return enrichErrorResponse(WebhookResponse.builder().build(), status, requestType, path);
        }

        if ((rawBody.startsWith("{") && rawBody.endsWith("}")) || (rawBody.startsWith("[") && rawBody.endsWith("]"))) {
            try {
                WebhookResponse parsed = objectMapper.readValue(rawBody, WebhookResponse.class);
                if (status.isError()) {
                    return enrichErrorResponse(parsed, status, requestType, path);
                }
                if (parsed.getStatus() == null || parsed.getStatus().trim().isEmpty()) {
                    parsed.setStatus(Boolean.FALSE.equals(parsed.getOk()) ? "error" : "success");
                }
                if (parsed.getMessage() == null || parsed.getMessage().trim().isEmpty()) {
                    if (parsed.getRunId() != null && !parsed.getRunId().trim().isEmpty()) {
                        parsed.setMessage("Accepted by OpenClaw, runId=" + parsed.getRunId());
                    } else {
                        parsed.setMessage(requestType + " request accepted by OpenClaw");
                    }
                }
                if (parsed.getTimestamp() == null) {
                    parsed.setTimestamp(System.currentTimeMillis());
                }
                return parsed;
            } catch (JsonProcessingException ex) {
                log.warn("Failed to parse OpenClaw response as JSON: {}", rawBody, ex);
            }
        }

        WebhookResponse fallback = WebhookResponse.builder()
                .status(status.is2xxSuccessful() ? "success" : "error")
                .message(status.is2xxSuccessful() ? rawBody : requestType + " request to OpenClaw failed")
                .error(status.is2xxSuccessful() ? null : rawBody)
                .timestamp(System.currentTimeMillis())
                .data(rawBody)
                .build();

        if (status.isError()) {
            return enrichErrorResponse(fallback, status, requestType, path);
        }

        return fallback;
    }

    private String normalizeWebhookBaseUrl(String configuredUrl) {
        String normalized = configuredUrl == null ? "" : configuredUrl.trim();
        normalized = normalized.replaceAll("/+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("openclaw.webhook.url must not be empty");
        }
        if (!normalized.endsWith("/hooks")) {
            normalized = normalized + "/hooks";
        }
        return normalized + "/";
    }

    private Mono<WebhookResponse> waitAndAttachAgentResult(WebhookResponse response, String agentId, long acceptedAtMs) {
        if (!waitForAgentResult) {
            return Mono.just(response);
        }
        if (response == null || !Boolean.TRUE.equals(response.getOk()) || response.getRunId() == null) {
            return Mono.just(response);
        }

        return Mono.fromCallable(() -> waitForAssistantText(agentId, acceptedAtMs))
                .subscribeOn(Schedulers.boundedElastic())
                .map(resultText -> {
                    if (!resultText.isPresent()) {
                        return response;
                    }

                    String text = resultText.get();
                    cacheRunReply(response.getRunId(), agentId, acceptedAtMs, text);
                    return applyCompletedResult(response, text, agentId, acceptedAtMs);
                });
    }

    private Optional<String> tryReadAssistantText(String agentId, long acceptedAtMs) {
        try {
            Path sessionFile = resolveSessionFile(agentId);
            return extractLatestAssistantText(sessionFile, acceptedAtMs);
        } catch (Exception ex) {
            log.debug("Immediate read assistant text failed: agentId={}", agentId, ex);
            return Optional.empty();
        }
    }

    private Optional<String> tryReadLatestAssistantText(String agentId) {
        try {
            Path sessionFile = resolveSessionFile(agentId);
            return extractLatestAssistantTextNoTimeFilter(sessionFile);
        } catch (Exception ex) {
            log.debug("Immediate read latest assistant text failed: agentId={}", agentId, ex);
            return Optional.empty();
        }
    }

    private Optional<String> waitForAssistantText(String agentId, long acceptedAtMs) {
        long deadline = System.currentTimeMillis() + Math.max(resultTimeoutMs, 1000L);
        int attempt = 0;
        // 清理线程中断标记，避免 NIO 读取被直接打断。
        clearThreadInterruptFlagIfNeeded("waitForAssistantText-start");
        log.info("Start polling assistant result: agentId={}, acceptedAtMs={}, timeoutMs={}, pollMs={}",
                agentId, acceptedAtMs, resultTimeoutMs, resultPollIntervalMs);
        while (System.currentTimeMillis() <= deadline) {
            attempt++;
            try {
                Path sessionFile = resolveSessionFile(agentId);
                long now = System.currentTimeMillis();
                log.debug("Polling attempt {}: sessionFile={}, elapsedMs={}", attempt, sessionFile, now - acceptedAtMs);
                Optional<String> text = extractLatestAssistantText(sessionFile, acceptedAtMs);
                if (text.isPresent()) {
                    log.info("Assistant result found on attempt {}: sessionFile={}, textChars={}",
                            attempt, sessionFile, text.get().length());
                    return text;
                }
                log.debug("Polling attempt {}: no assistant text found yet", attempt);
                Thread.sleep(Math.max(resultPollIntervalMs, 100L));
            } catch (InterruptedException ie) {
                // Do not re-set interrupted flag on shared scheduler thread, avoid poisoning subsequent file reads.
                log.warn("Polling interrupted while waiting assistant result: agentId={}", agentId);
                return Optional.empty();
            } catch (Exception ex) {
                log.warn("Failed while polling assistant result from local session store", ex);
                return Optional.empty();
            }
        }
        log.warn("Polling timeout: agentId={}, attempts={}, waitedMs={}", agentId, attempt, Math.max(resultTimeoutMs, 1000L));
        return Optional.empty();
    }

    private Path resolveSessionFile(String agentId) {
        // 读取会话前先清理中断标记，降低 ClosedByInterruptException 概率。
        clearThreadInterruptFlagIfNeeded("resolveSessionFile-start");
        Path sessionsDir = Paths.get(openclawHome, "agents", agentId, "sessions");
        Path sessionsIndex = sessionsDir.resolve("sessions.json");
        if (Files.exists(sessionsIndex)) {
            try {
                // 优先走 sessions.json，能更准确定位当前会话文件。
                JsonNode root = objectMapper.readTree(new String(Files.readAllBytes(sessionsIndex), StandardCharsets.UTF_8));
                JsonNode target = resolveHookSessionNode(root, agentId);
                String sessionFile = target.path("sessionFile").asText("");
                if (!sessionFile.isEmpty()) {
                    Path sessionPath = Paths.get(sessionFile);
                    if (Files.exists(sessionPath)) {
                        log.debug("Resolved session file from sessions.json: agentId={}, keyMatched={}, sessionFile={}",
                                agentId, target.path("_matchedSessionKey").asText("unknown"), sessionPath);
                        return sessionPath;
                    }
                    log.debug("Resolved session file path does not exist: agentId={}, keyMatched={}, sessionFile={}",
                            agentId, target.path("_matchedSessionKey").asText("unknown"), sessionPath);
                }
            } catch (ClosedByInterruptException interrupted) {
                // 遇到中断型 I/O，清理标记并重试一次。
                clearThreadInterruptFlagIfNeeded("resolveSessionFile-sessionsIndex-retry");
                try {
                    JsonNode root = objectMapper.readTree(new String(Files.readAllBytes(sessionsIndex), StandardCharsets.UTF_8));
                    JsonNode target = resolveHookSessionNode(root, agentId);
                    String sessionFile = target.path("sessionFile").asText("");
                    if (!sessionFile.isEmpty()) {
                        Path sessionPath = Paths.get(sessionFile);
                        if (Files.exists(sessionPath)) {
                            log.debug("Resolved session file from sessions.json after interrupt retry: agentId={}, keyMatched={}, sessionFile={}",
                                    agentId, target.path("_matchedSessionKey").asText("unknown"), sessionPath);
                            return sessionPath;
                        }
                    }
                } catch (Exception retryEx) {
                    log.debug("Retry read sessions index failed at {}", sessionsIndex, retryEx);
                }
            } catch (IOException ex) {
                log.debug("Failed to read sessions index at {}", sessionsIndex, ex);
            }
        }

        try (Stream<Path> files = Files.list(sessionsDir)) {
            // 索引不可用时，退化到“最近修改的 jsonl”。
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .max((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .map(path -> {
                        log.debug("Fallback to latest session file: agentId={}, sessionFile={}", agentId, path);
                        return path;
                    })
                    .orElseThrow(() -> new IllegalStateException("No session transcript found in " + sessionsDir));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to resolve session transcript in " + sessionsDir, ex);
        }
    }

    private void clearThreadInterruptFlagIfNeeded(String context) {
        if (Thread.interrupted()) {
            log.debug("Cleared interrupted flag on worker thread: context={}", context);
        }
    }

    private JsonNode resolveHookSessionNode(JsonNode root, String agentId) {
        JsonNode direct = root.path(defaultHookSessionKey);
        if (!direct.isMissingNode()) {
            if (direct.isObject()) {
                ((ObjectNode) direct).put("_matchedSessionKey", defaultHookSessionKey);
            }
            return direct;
        }

        String normalized = defaultHookSessionKey == null ? "" : defaultHookSessionKey.trim();
        String prefixed = "agent:" + agentId + ":" + normalized;
        String stripped = normalized.startsWith("hook:") ? normalized.substring("hook:".length()) : normalized;
        String prefixedStripped = "agent:" + agentId + ":" + stripped;

        JsonNode best = null;
        long bestUpdatedAt = Long.MIN_VALUE;
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            boolean match = key.equals(prefixed)
                    || key.equals(prefixedStripped)
                    || (!normalized.isEmpty() && key.endsWith(":" + normalized))
                    || (!stripped.isEmpty() && key.endsWith(":" + stripped));
            if (!match) {
                continue;
            }
            JsonNode value = entry.getValue();
            long updatedAt = value.path("updatedAt").asLong(Long.MIN_VALUE);
            if (best == null || updatedAt >= bestUpdatedAt) {
                best = value;
                bestUpdatedAt = updatedAt;
                if (best.isObject()) {
                    ((ObjectNode) best).put("_matchedSessionKey", key);
                }
            }
        }
        if (best != null) {
            return best;
        }
        throw new IllegalStateException("No hook session entry matched for key=" + defaultHookSessionKey + ", agentId=" + agentId);
    }

    private Optional<String> extractLatestAssistantText(Path sessionFile, long acceptedAtMs) {
        try {
            String latest = null;
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (!"message".equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode message = node.path("message");
                if (!"assistant".equals(message.path("role").asText())) {
                    continue;
                }
                String ts = node.path("timestamp").asText("");
                if (ts.isEmpty()) {
                    continue;
                }
                long eventMs = Instant.parse(ts).toEpochMilli();
                if (eventMs + 1000 < acceptedAtMs) {
                    continue;
                }
                String text = extractTextContent(message.path("content"));
                if (!text.isEmpty()) {
                    latest = text;
                }
            }
            return Optional.ofNullable(latest);
        } catch (Exception ex) {
            log.debug("Failed to parse session transcript {}", sessionFile, ex);
            return Optional.empty();
        }
    }

    private Optional<String> extractLatestAssistantTextNoTimeFilter(Path sessionFile) {
        try {
            String latest = null;
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (!"message".equals(node.path("type").asText())) {
                    continue;
                }
                JsonNode message = node.path("message");
                if (!"assistant".equals(message.path("role").asText())) {
                    continue;
                }
                String text = extractTextContent(message.path("content"));
                if (!text.isEmpty()) {
                    latest = text;
                }
            }
            return Optional.ofNullable(latest);
        } catch (Exception ex) {
            log.debug("Failed to parse latest assistant text without time filter: {}", sessionFile, ex);
            return Optional.empty();
        }
    }

    private String extractTextContent(JsonNode contentArray) {
        if (contentArray == null || !contentArray.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : contentArray) {
            if (!"text".equals(item.path("type").asText())) {
                continue;
            }
            String text = item.path("text").asText("");
            if (text.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.trim());
        }
        return sb.toString();
    }

    private void cacheRunReply(String runId, String agentId, long acceptedAtMs, String text) {
        if (runId == null) {
            return;
        }
        RunResultState state = runResults.computeIfAbsent(runId, key -> new RunResultState(runId, agentId, acceptedAtMs));
        state.agentId = agentId;
        state.acceptedAtMs = acceptedAtMs;
        state.assistantReply = text;
        state.completedAtMs = System.currentTimeMillis();
    }

    private WebhookResponse applyCompletedResult(WebhookResponse response, String text, String agentId, long acceptedAtMs) {
        response.setStatus("completed");
        response.setMessage(text);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runId", response.getRunId());
        payload.put("assistantReply", text);
        payload.put("agentId", agentId);
        payload.put("acceptedAtMs", acceptedAtMs);
        payload.put("source", "openclaw-session");
        response.setData(payload);
        return response;
    }

    private WebhookResponse buildCompletedResult(String runId, String text, String agentId, long acceptedAtMs) {
        WebhookResponse response = WebhookResponse.builder()
                .ok(true)
                .runId(runId)
                .status("completed")
                .message(text)
                .timestamp(System.currentTimeMillis())
                .build();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runId", runId);
        payload.put("assistantReply", text);
        payload.put("agentId", agentId);
        payload.put("acceptedAtMs", acceptedAtMs);
        payload.put("source", "openclaw-session");
        response.setData(payload);
        return response;
    }

    private ServerSentEvent<WebhookResponse> toSseEvent(String eventName, String runId, WebhookResponse body) {
        ServerSentEvent.Builder<WebhookResponse> builder = ServerSentEvent.<WebhookResponse>builder()
                .event(eventName)
                .data(body);
        if (runId != null && !runId.trim().isEmpty()) {
            builder.id(runId);
        }
        return builder.build();
    }

    private static final class RunResultState {
        private final String runId;
        private String agentId;
        private long acceptedAtMs;
        private long completedAtMs;
        private String assistantReply;

        private RunResultState(String runId, String agentId, long acceptedAtMs) {
            this.runId = runId;
            this.agentId = agentId;
            this.acceptedAtMs = acceptedAtMs;
        }
    }
}

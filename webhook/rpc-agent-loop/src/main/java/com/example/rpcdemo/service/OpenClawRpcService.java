package com.example.rpcdemo.service;

import com.example.rpcdemo.model.RpcResultResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OpenClawRpcService {

    private final ObjectMapper objectMapper;

    public OpenClawRpcService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RpcResultResponse runAndWait(String message, String agentId, String sessionKey, long submitTimeoutMs, long waitTimeoutMs) {
        JsonNode submit = callAgent(message, agentId, sessionKey, submitTimeoutMs);
        String runId = submit.path("runId").asText("");
        if (runId.isEmpty()) {
            throw new OpenClawRpcException("RPC agent call did not return runId: " + submit.toString());
        }

        JsonNode wait = callAgentWait(runId, waitTimeoutMs);
        String status = wait.path("status").asText("");
        boolean ok = wait.path("ok").asBoolean(!"error".equalsIgnoreCase(status));
        String finalText = extractFinalText(wait);

        return RpcResultResponse.builder()
                .ok(ok)
                .status(status.isEmpty() ? (ok ? "completed" : "error") : status)
                .runId(runId)
                .message(finalText.isEmpty() ? "agent.wait returned without final text" : finalText)
                .result(wait.path("result").isMissingNode() ? wait : wait.path("result"))
                .submitResponse(submit)
                .waitResponse(wait)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public RpcResultResponse waitByRunId(String runId, long waitTimeoutMs) {
        JsonNode wait = callAgentWait(runId, waitTimeoutMs);
        String status = wait.path("status").asText("");
        boolean ok = wait.path("ok").asBoolean(!"error".equalsIgnoreCase(status));
        String finalText = extractFinalText(wait);
        return RpcResultResponse.builder()
                .ok(ok)
                .status(status.isEmpty() ? (ok ? "completed" : "error") : status)
                .runId(runId)
                .message(finalText.isEmpty() ? "agent.wait returned without final text" : finalText)
                .result(wait.path("result").isMissingNode() ? wait : wait.path("result"))
                .waitResponse(wait)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private JsonNode callAgent(String message, String agentId, String sessionKey, long timeoutMs) {
        try {
            JsonNode params = objectMapper.createObjectNode()
                    .put("agentId", agentId)
                    .put("message", message)
                    .put("sessionKey", sessionKey);
            return invokeGateway("agent", params.toString(), timeoutMs);
        } catch (Exception ex) {
            throw new OpenClawRpcException("Failed to call RPC agent", ex);
        }
    }

    private JsonNode callAgentWait(String runId, long timeoutMs) {
        try {
            JsonNode params = objectMapper.createObjectNode()
                    .put("runId", runId)
                    .put("timeoutMs", timeoutMs);
            // CLI timeout should be slightly larger than wait timeout to avoid client-side premature timeout.
            return invokeGateway("agent.wait", params.toString(), timeoutMs + 5000L);
        } catch (Exception ex) {
            throw new OpenClawRpcException("Failed to call RPC agent.wait", ex);
        }
    }

    private JsonNode invokeGateway(String method, String paramsJson, long timeoutMs) {
        List<String> command = new ArrayList<String>();
        command.add("openclaw");
        command.add("gateway");
        command.add("call");
        command.add(method);
        command.add("--json");
        command.add("--params");
        command.add(paramsJson);
        command.add("--timeout");
        command.add(String.valueOf(timeoutMs));

        log.info("Executing OpenClaw RPC: method={}, timeoutMs={}", method, timeoutMs);
        CommandResult result = execute(command, timeoutMs + 5000L);
        if (result.exitCode != 0) {
            throw new OpenClawRpcException("RPC command failed: method=" + method + ", stderr=" + result.stderr + ", stdout=" + result.stdout);
        }

        String json = extractJsonBody(result.stdout);
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new OpenClawRpcException("Cannot parse RPC output as JSON: " + json, ex);
        }
    }

    private CommandResult execute(List<String> command, long waitTimeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());

            boolean done = process.waitFor(waitTimeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new OpenClawRpcException("RPC command timed out: " + String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), stdout, stderr);
        } catch (OpenClawRpcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenClawRpcException("Failed to execute command: " + String.join(" ", command), ex);
        }
    }

    private String readAll(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private String extractJsonBody(String stdout) {
        if (stdout == null) {
            return "{}";
        }
        int start = stdout.indexOf('{');
        if (start < 0) {
            throw new OpenClawRpcException("RPC output does not contain JSON object: " + stdout);
        }
        return stdout.substring(start).trim();
    }

    private String extractFinalText(JsonNode waitNode) {
        String[] candidates = new String[] {
                waitNode.path("result").path("text").asText(""),
                waitNode.path("result").path("message").asText(""),
                waitNode.path("message").asText("")
        };
        for (String text : candidates) {
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        }
        return "";
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }
}

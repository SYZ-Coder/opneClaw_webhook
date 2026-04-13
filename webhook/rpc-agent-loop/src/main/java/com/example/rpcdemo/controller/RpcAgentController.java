package com.example.rpcdemo.controller;

import com.example.rpcdemo.model.RpcAgentWaitRequest;
import com.example.rpcdemo.model.RpcResultResponse;
import com.example.rpcdemo.service.OpenClawRpcException;
import com.example.rpcdemo.service.OpenClawRpcService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/rpc")
@CrossOrigin(origins = "*")
public class RpcAgentController {

    private final OpenClawRpcService rpcService;

    public RpcAgentController(OpenClawRpcService rpcService) {
        this.rpcService = rpcService;
    }

    /**
     * Demo endpoint: submit to agent and wait final result using agent.wait.
     */
    @PostMapping("/agent/wait")
    public ResponseEntity<RpcResultResponse> runAndWait(@RequestBody @Valid RpcAgentWaitRequest request) {
        log.info("RPC runAndWait request: agentId={}, sessionKey={}", request.getAgentId(), request.getSessionKey());
        RpcResultResponse response = rpcService.runAndWait(
                request.getMessage(),
                request.getAgentId(),
                request.getSessionKey(),
                safeTimeout(request.getSubmitTimeoutMs(), 10000L),
                safeTimeout(request.getWaitTimeoutMs(), 30000L)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Wait by existing runId (when you already got runId elsewhere).
     */
    @GetMapping("/result/{runId}")
    public ResponseEntity<RpcResultResponse> waitByRunId(
            @PathVariable("runId") String runId,
            @RequestParam(value = "waitTimeoutMs", required = false, defaultValue = "30000") long waitTimeoutMs) {
        RpcResultResponse response = rpcService.waitByRunId(runId, safeTimeout(waitTimeoutMs, 30000L));
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(OpenClawRpcException.class)
    public ResponseEntity<RpcResultResponse> handleRpcException(OpenClawRpcException ex) {
        log.error("RPC demo request failed", ex);
        RpcResultResponse body = RpcResultResponse.builder()
                .ok(false)
                .status("error")
                .message(ex.getMessage())
                .timestamp(System.currentTimeMillis())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    private long safeTimeout(Long timeoutMs, long fallback) {
        if (timeoutMs == null || timeoutMs.longValue() <= 0L) {
            return fallback;
        }
        return timeoutMs.longValue();
    }
}

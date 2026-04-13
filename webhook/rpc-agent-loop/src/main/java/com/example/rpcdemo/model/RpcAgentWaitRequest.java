package com.example.rpcdemo.model;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RpcAgentWaitRequest {
    @NotBlank
    private String message;
    private String agentId = "director";
    private String sessionKey = "hook:springboot-demo";
    private Long submitTimeoutMs = 10000L;
    private Long waitTimeoutMs = 30000L;
}

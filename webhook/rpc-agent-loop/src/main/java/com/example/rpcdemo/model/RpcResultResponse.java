package com.example.rpcdemo.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RpcResultResponse {
    private Boolean ok;
    private String status;
    private String runId;
    private String message;
    private Object result;
    private Object submitResponse;
    private Object waitResponse;
    private Long timestamp;
}

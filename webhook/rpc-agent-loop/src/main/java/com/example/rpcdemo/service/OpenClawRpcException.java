package com.example.rpcdemo.service;

public class OpenClawRpcException extends RuntimeException {
    public OpenClawRpcException(String message) {
        super(message);
    }

    public OpenClawRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}

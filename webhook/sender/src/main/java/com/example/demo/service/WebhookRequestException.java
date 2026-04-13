package com.example.demo.service;

import com.example.demo.model.WebhookResponse;
import org.springframework.http.HttpStatus;

public class WebhookRequestException extends RuntimeException {

    private final HttpStatus statusCode;
    private final WebhookResponse response;

    public WebhookRequestException(HttpStatus statusCode, WebhookResponse response) {
        super(response != null ? response.getMessage() : null);
        this.statusCode = statusCode;
        this.response = response;
    }

    public HttpStatus getStatusCode() {
        return statusCode;
    }

    public WebhookResponse getResponse() {
        return response;
    }
}

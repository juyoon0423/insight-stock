package com.insightstock.insightstock.rag;

public class RagApiException extends RuntimeException {

    public RagApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.akamai.miniwsa.service;

public class BatchTooLargeException extends RuntimeException {
    public BatchTooLargeException(String message) {
        super(message);
    }
}

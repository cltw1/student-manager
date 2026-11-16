package com.example.studentmanager;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public AppException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static AppException bad(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}

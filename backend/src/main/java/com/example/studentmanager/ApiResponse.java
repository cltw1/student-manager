package com.example.studentmanager;

import java.util.UUID;

public record ApiResponse<T>(boolean success, String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(String code, String message, T data) {
        return new ApiResponse<>(true, code, message, data, UUID.randomUUID().toString());
    }
    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null, UUID.randomUUID().toString());
    }
}

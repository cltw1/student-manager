package com.example.studentmanager;

public record OperationResult(String tool, boolean success, String code, String message, Object data,
                              ActionService.ActionView action, boolean mutated) {
    public static OperationResult success(String tool, String code, String message, Object data, boolean mutated) {
        return new OperationResult(tool, true, code, message, data, null, mutated);
    }
    public static OperationResult failure(String tool, String code, String message) {
        return new OperationResult(tool, false, code, message, null, null, false);
    }
}

package com.example.studentmanager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiResponse<Void>> app(AppException ex) {
        return ResponseEntity.status(ex.status()).body(ApiResponse.fail(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiResponse<Void>> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(409).body(ApiResponse.fail("STUDENT_EXISTS", "学号已存在，请使用其他学号"));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> database(DataAccessException ex) {
        log.warn("Database operation failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(503).body(ApiResponse.fail("DATABASE_UNAVAILABLE", "数据库暂时不可用，请检查 MySQL 和连接配置"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
            .map(e -> e.getDefaultMessage()).orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", message));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "请求格式或参数不正确"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("Unexpected request error", ex);
        return ResponseEntity.internalServerError().body(ApiResponse.fail("INTERNAL_ERROR", "服务处理失败，请稍后重试"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadTooLarge(Exception ex) {
        return ResponseEntity.status(413).body(ApiResponse.fail("FILE_TOO_LARGE", "Excel文件不能超过2MB"));
    }
}

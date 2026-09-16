package com.manliao.backend.common;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ErrorResponses errors;
    public ApiExceptionHandler(ErrorResponses errors) { this.errors = errors; }
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> domain(ApiError error, HttpServletRequest request) {
        var response=ResponseEntity.status(error.status());
        if(error.status()==429) response.header("Retry-After",String.valueOf(error.retryAfterSeconds()));
        return response
            .body(errors.body(error.code(), error.getMessage(), request, error.details()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var details = error.getBindingResult().getFieldErrors().stream()
            .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage()))).toList();
        // Never include rejected values: they may contain passwords or tokens.
        return ResponseEntity.unprocessableContent().body(errors.body(
            "COMMON_VALIDATION_ERROR", "请求参数校验失败", request, details));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(HttpServletRequest request) {
        return ResponseEntity.unprocessableContent().body(errors.body(
            "COMMON_VALIDATION_ERROR", "请求参数校验失败", request, null));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(errors.body("COMMON_NOT_FOUND", "资源不存在", request, null));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> wrongMethod(HttpServletRequest request) {
        return ResponseEntity.status(405).body(errors.body("COMMON_BAD_REQUEST", "请求方法不支持", request, null));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadTooLarge(HttpServletRequest request) {
        return ResponseEntity.status(413).body(errors.body("MEDIA_FILE_TOO_LARGE","图片超过上传大小限制",request,null));
    }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> missingPart(HttpServletRequest request) {
        return ResponseEntity.unprocessableContent().body(errors.body("COMMON_VALIDATION_ERROR","缺少必要的上传字段",request,null));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error, HttpServletRequest request) {
        // Log the exception type only; JDBC exception messages can contain bound values.
        LOG.error("Unhandled exception type={}, request_id={}", error.getClass().getName(),
            RequestContextFilter.requestId(request));
        return ResponseEntity.internalServerError().body(errors.body(
            "COMMON_INTERNAL_ERROR", "服务器内部错误", request, null));
    }
}

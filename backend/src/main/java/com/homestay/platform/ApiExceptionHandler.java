package com.homestay.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException error) {
    int status = error.getStatusCode().value();
    return response(status, errorCode(status, error.getReason()), reason(error.getReason()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
    String message = error.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(field -> field.getField() + " " + field.getDefaultMessage())
        .orElse("请求参数不合法");
    return response(400, "VALIDATION_FAILED", message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException error) {
    return response(400, "REQUEST_BODY_INVALID", "请求体格式不正确");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException error) {
    return response(409, "RESOURCE_CONFLICT", "数据已存在或正在被其他操作使用");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
    return response(500, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
  }

  private ResponseEntity<Map<String, Object>> response(int status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("status", status);
    return ResponseEntity.status(status).body(body);
  }

  private String reason(String reason) {
    return reason == null || reason.isBlank() ? "请求失败" : reason;
  }

  private String errorCode(int status, String reason) {
    String message = reason(reason);
    if (message.contains("库存")) return "RESERVATION_STOCK_NOT_ENOUGH";
    if (message.contains("支付单已过期") || message.contains("支付超时")) return "PAYMENT_EXPIRED";
    if (message.contains("订单状态")) return "ORDER_STATUS_INVALID";
    if (message.contains("订单不存在")) return "RESERVATION_NOT_FOUND";
    if (status == HttpStatus.UNAUTHORIZED.value()) return "AUTH_REQUIRED";
    if (status == HttpStatus.FORBIDDEN.value()) return "PERMISSION_DENIED";
    if (status == HttpStatus.NOT_FOUND.value()) return "RESOURCE_NOT_FOUND";
    if (status == HttpStatus.CONFLICT.value()) return "RESOURCE_CONFLICT";
    return switch (status) {
      case 400 -> "BAD_REQUEST";
      case 500 -> "INTERNAL_ERROR";
      default -> "REQUEST_FAILED";
    };
  }
}

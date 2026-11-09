package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final SessionService session;
  private final PaymentService payments;
  private final RateLimitService rateLimits;

  public PaymentController(SessionService session, PaymentService payments, RateLimitService rateLimits) {
    this.session = session;
    this.payments = payments;
    this.rateLimits = rateLimits;
  }

  public record SimulateRequest(@NotBlank String result) {}

  @PostMapping("/reservations/{orderNo}/payments")
  public Map<String, Object> createPayment(
      @PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requirePermission(authorization, "payment:simulate").id();
    rateLimits.check("payment-create", String.valueOf(userId), 12, Duration.ofMinutes(1));
    return payments.createPayment(orderNo, userId);
  }

  @PostMapping("/payments/{paymentNo}/simulate")
  public Map<String, Object> simulate(
      @PathVariable String paymentNo,
      @Valid @RequestBody SimulateRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    Long userId = session.requirePermission(authorization, "payment:simulate").id();
    rateLimits.check("payment-simulate", String.valueOf(userId), 20, Duration.ofMinutes(1));
    return payments.simulate(paymentNo, userId, body.result());
  }

  @GetMapping("/payments/{paymentNo}")
  public Map<String, Object> getPayment(
      @PathVariable String paymentNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.getPayment(paymentNo, session.requirePermission(authorization, "payment:simulate").id());
  }
}

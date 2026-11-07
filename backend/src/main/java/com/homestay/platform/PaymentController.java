package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final SessionService session;
  private final PaymentService payments;

  public PaymentController(SessionService session, PaymentService payments) {
    this.session = session;
    this.payments = payments;
  }

  public record SimulateRequest(@NotBlank String result) {}

  @PostMapping("/reservations/{orderNo}/payments")
  public Map<String, Object> createPayment(
      @PathVariable String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.createPayment(orderNo, session.requirePermission(authorization, "payment:simulate").id());
  }

  @PostMapping("/payments/{paymentNo}/simulate")
  public Map<String, Object> simulate(
      @PathVariable String paymentNo,
      @Valid @RequestBody SimulateRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.simulate(paymentNo, session.requirePermission(authorization, "payment:simulate").id(), body.result());
  }

  @GetMapping("/payments/{paymentNo}")
  public Map<String, Object> getPayment(
      @PathVariable String paymentNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return payments.getPayment(paymentNo, session.requirePermission(authorization, "payment:simulate").id());
  }
}

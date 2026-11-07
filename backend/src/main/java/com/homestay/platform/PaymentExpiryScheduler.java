package com.homestay.platform;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentExpiryScheduler {
  private final PaymentService payments;

  public PaymentExpiryScheduler(PaymentService payments) {
    this.payments = payments;
  }

  @Scheduled(fixedDelay = 60_000)
  public void expirePayments() {
    payments.expirePendingPayments();
  }
}

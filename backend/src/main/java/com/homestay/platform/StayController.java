package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class StayController {
  private final SessionService session;
  private final StayService stays;

  public StayController(SessionService session, StayService stays) {
    this.session = session;
    this.stays = stays;
  }

  public record AssignRoomRequest(@NotNull List<Long> roomIds) {}
  public record CheckInRequest(List<Long> reservationRoomIds) {}
  public record ChangeRoomRequest(@NotNull Long reservationRoomId, @NotNull Long newRoomId, @NotBlank String reason, BigDecimal feeDifference) {}
  public record CollectRequest(@NotBlank String orderNo, @NotNull @Positive BigDecimal amount, @NotBlank String method, String remark) {}
  public record CheckOutRequest(List<Long> reservationRoomIds) {}

  @PostMapping("/reservations/{orderNo}/assign-room")
  public Map<String, Object> assignRoom(
      @PathVariable String orderNo,
      @Valid @RequestBody AssignRoomRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return stays.assignRooms(orderNo, session.requirePermission(authorization, "reservation:assign-room").id(), body.roomIds());
  }

  @PostMapping("/reservations/{orderNo}/check-in")
  public Map<String, Object> checkIn(
      @PathVariable String orderNo,
      @RequestBody(required = false) CheckInRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return stays.checkIn(orderNo, session.requirePermission(authorization, "reservation:check-in").id(), body == null ? null : body.reservationRoomIds());
  }

  @PostMapping("/reservations/{orderNo}/change-room")
  public Map<String, Object> changeRoom(
      @PathVariable String orderNo,
      @Valid @RequestBody ChangeRoomRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return stays.changeRoom(orderNo, session.requirePermission(authorization, "reservation:change-room").id(), body.reservationRoomId(), body.newRoomId(), body.reason(), body.feeDifference());
  }

  @PostMapping("/reservations/{orderNo}/check-out")
  public Map<String, Object> checkOut(
      @PathVariable String orderNo,
      @RequestBody(required = false) CheckOutRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return stays.checkOut(orderNo, session.requirePermission(authorization, "reservation:check-out").id(), body == null ? null : body.reservationRoomIds());
  }

  @PostMapping("/payments/collect")
  public Map<String, Object> collect(
      @Valid @RequestBody CollectRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return stays.collect(body.orderNo(), session.requirePermission(authorization, "payment:collect").id(), body.amount(), body.method(), body.remark());
  }
}

package com.homestay.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
  private final SessionService session;
  private final ReviewService reviews;

  public ReviewController(SessionService session, ReviewService reviews) {
    this.session = session;
    this.reviews = reviews;
  }

  public record ReviewRequest(@Min(1) @Max(5) int score, String content) {}
  public record ReplyRequest(@NotBlank String reply) {}

  @GetMapping("/reviews")
  public Map<String, Object> published() {
    return Map.of("code", "0", "message", "success", "data", reviews.published());
  }

  @PostMapping("/reviews")
  public Map<String, Object> create(@Valid @RequestBody ReviewRequest body,
      @RequestParam String orderNo,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return Map.of("code", "0", "message", "评价提交成功", "data", reviews.create(session.requirePermission(authorization, "review:create").id(), orderNo, body.score(), body.content()));
  }

  @GetMapping("/admin/reviews")
  public Map<String, Object> all(@RequestHeader(value = "Authorization", required = false) String authorization) {
    session.requirePermission(authorization, "review:moderate");
    return Map.of("code", "0", "message", "success", "data", reviews.all());
  }

  @PatchMapping("/admin/reviews/{id}/status")
  public Map<String, Object> status(@PathVariable Long id, @RequestParam String status,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    reviews.updateStatus(session.requirePermission(authorization, "review:moderate").id(), id, status);
    return Map.of("code", "0", "message", "评价状态已更新");
  }

  @PostMapping("/admin/reviews/{id}/reply")
  public Map<String, Object> reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    reviews.reply(session.requirePermission(authorization, "review:moderate").id(), id, body.reply());
    return Map.of("code", "0", "message", "评价回复成功");
  }
}

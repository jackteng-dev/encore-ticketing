package dev.elpsy.ticket.orders;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
public final class PaymentModels {
 private PaymentModels() {}
 public record Pay(@NotNull UUID requestId,@Min(1) long expectedVersion,@Min(1) @Max(10000000) int expectedTotalTwd,
  @NotNull @Pattern(regexp="SUCCESS|DECLINED") String scenario) {}
 public record Reconcile(@NotNull UUID requestId) {}
 public record Payment(UUID id,UUID requestId,String status,String reason,int amountTwd,OffsetDateTime createdAt,OffsetDateTime processedAt) {}
}

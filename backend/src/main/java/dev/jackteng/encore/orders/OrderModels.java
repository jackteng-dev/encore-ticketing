package dev.jackteng.encore.orders;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;
public final class OrderModels {
 private OrderModels() {}
 public record Place(@NotNull UUID requestId,@NotNull UUID ticketId,@Min(1) @Max(10) int quantity,
  @Min(1) long expectedTicketVersion,@Min(1) @Max(10000000) int expectedTotalTwd) {}
 public record Cancel(@NotNull UUID requestId,@Min(1) long expectedVersion) {}
 public record Order(UUID id,UUID requestId,UUID ticketId,String eventTitle,String sessionName,OffsetDateTime startsAt,
  String ticketName,int priceTwd,int quantity,int totalTwd,String status,long version,OffsetDateTime createdAt,
  OffsetDateTime expiresAt,OffsetDateTime closedAt,PaymentModels.Payment payment,boolean mockPaymentsEnabled) {}
}

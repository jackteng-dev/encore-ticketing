package dev.jackteng.encore.orders;
import java.time.OffsetDateTime;
import java.util.UUID;
public final class PurchaseModels {
 private PurchaseModels() {}
 public record Purchase(UUID id,UUID requestId,UUID ticketId,int quantity,long expectedTicketVersion,int expectedTotalTwd,
  String status,long version,String reason,UUID orderId,OffsetDateTime createdAt,OffsetDateTime deadlineAt,OffsetDateTime closedAt) {}
}

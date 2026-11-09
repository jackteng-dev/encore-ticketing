package dev.elpsy.ticket.tickets;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
public final class TicketModels {
 private TicketModels(){}
 public record Issue(@NotNull UUID requestId,@Min(1) long expectedVersion){}
 public record Inspect(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code,@NotNull UUID expectedSessionId){}
 public record CheckIn(@NotNull UUID requestId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code,@NotNull UUID expectedSessionId,@Min(1) long expectedVersion){}
 public record Revoke(@NotNull UUID requestId,@NotNull UUID ticketId,@NotNull UUID expectedSessionId,@Min(1) long expectedVersion,@NotBlank @Pattern(regexp="LOST_CODE|SECURITY|OPERATION_ERROR") String reason){}
 public record CustomerTicket(UUID id,int unit,String code,String state,long version,OffsetDateTime createdAt,OffsetDateTime changedAt){}
 public record Bundle(UUID orderId,UUID requestId,long orderVersionAtIssue,int quantity,boolean issuanceEnabled,boolean eligible,OffsetDateTime issuedAt,List<CustomerTicket> tickets){}
 public record StaffTicket(UUID id,UUID orderId,UUID sessionId,String eventTitle,String sessionName,String ticketName,int unit,String state,long version,boolean eligible,OffsetDateTime changedAt){}
 public record Receipt(UUID requestId,String action,StaffTicket ticket,AdmissionWindowModels.Proof windowProof){}
 public record IssueRecovery(UUID requestId,UUID orderId,long expectedVersion,String bodyFingerprint,String state,Bundle bundle,OffsetDateTime fencedAt){}
 public record AdmissionRecovery(UUID requestId,String action,UUID ticketId,UUID expectedSessionId,long expectedVersion,String reason,String bodyFingerprint,String state,Receipt receipt,OffsetDateTime fencedAt){}
 public record Settings(boolean enabled){}
 public record Audit(UUID requestId,UUID ticketId,String action,String reason,String state,long version,OffsetDateTime createdAt){}
}

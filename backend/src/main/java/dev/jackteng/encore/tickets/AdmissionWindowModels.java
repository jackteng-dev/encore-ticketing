package dev.jackteng.encore.tickets;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AdmissionWindowModels {
 private AdmissionWindowModels() {}
 public static final String UTC_SECONDS="\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";
 // Strings preserve the exact original canonical body for lookup/fencing.
 public record Configure(@NotNull UUID requestId,@Min(0) @Max(1000000000) long expectedVersion,
  @NotNull @Size(min=20,max=20) @Pattern(regexp=UTC_SECONDS) String opensAt,
  @NotNull @Size(min=20,max=20) @Pattern(regexp=UTC_SECONDS) String closesAt) {}
 public record Policy(UUID sessionId,long version,UUID requestId,OffsetDateTime opensAt,
  OffsetDateTime closesAt,OffsetDateTime createdAt) {}
 public record Status(UUID sessionId,long version,UUID requestId,OffsetDateTime opensAt,
  OffsetDateTime closesAt,String state,OffsetDateTime observedAt) {}
 public record Recovery(UUID requestId,UUID sessionId,long expectedVersion,String opensAt,
  String closesAt,String bodyFingerprint,String state,Policy policy,OffsetDateTime recordedAt) {}
 public record Proof(UUID requestId,UUID sessionId,long policyVersion,OffsetDateTime opensAt,
  OffsetDateTime closesAt,OffsetDateTime checkedAt) {}
}

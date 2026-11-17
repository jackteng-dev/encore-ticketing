package dev.jackteng.encore.catalog;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
public final class Models {
 private Models() {}
 public record EventInput(@NotNull UUID requestId, @NotNull @Min(0) Long expectedVersion,
  @NotBlank @Pattern(regexp="[a-z0-9]+(-[a-z0-9]+)*") @Size(max=80) String slug,
  @NotBlank @Size(max=120) String title, @NotNull @Size(max=3000) String description,
  @NotBlank @Size(max=160) String venue) {}
 public record SessionInput(@NotNull UUID requestId, @NotNull @Min(0) Long expectedVersion,
  @NotBlank @Size(max=120) String name, @NotNull OffsetDateTime startsAt,
  @NotNull OffsetDateTime salesStart, @NotNull OffsetDateTime salesEnd) {}
 public record TicketInput(@NotNull UUID requestId, @NotNull @Min(0) Long expectedVersion,
  @NotBlank @Size(max=100) String name, @Min(1) @Max(1000000) int priceTwd,
  @Min(1) @Max(100000) int capacity, @Min(1) @Max(10) int maxPerCustomer) {}
 public record Transition(@NotNull UUID requestId,@NotNull @Min(1) Long expectedVersion) {}
 public record Event(UUID id,String slug,String title,String description,String venue,String status,long version) {}
 public record Ticket(UUID id,String name,int priceTwd,int capacity,int maxPerCustomer,long version,Integer available) {
  public Ticket {if(available==null)available=capacity;}
 }
 public record Session(UUID id,UUID eventId,String name,OffsetDateTime startsAt,OffsetDateTime salesStart,
  OffsetDateTime salesEnd,long version,String salesStatus,List<Ticket> tickets) {}
 public record Detail(Event event,List<Session> sessions,boolean orderingEnabled,boolean waitingRoomEnabled,boolean purchaseRequestsEnabled,boolean preDebitEnabled) {}
 public record Page<T>(List<T> items,long total,int page,int size) {}
 /** 公開列表摘要：活動欄位加上場次與票價彙總，供搜尋結果與卡片顯示。 */
 public record PublicEvent(UUID id,String slug,String title,String description,String venue,String status,long version,
  OffsetDateTime firstStartsAt,OffsetDateTime lastStartsAt,int sessionCount,Integer minPriceTwd,Integer maxPriceTwd,boolean selling) {}
 public enum PublicSort {NEWEST,DATE,PRICE}
 public record Audit(UUID requestId,String action,UUID resourceId,OffsetDateTime createdAt) {}
}

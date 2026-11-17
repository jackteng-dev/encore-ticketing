package dev.jackteng.encore.catalog;
import jakarta.validation.constraints.*;
import java.util.*;
/** 活動分類、場館、演出者與活動檔案（展示資料，不影響票額與售票條件）。 */
public final class TaxonomyModels {
 private TaxonomyModels() {}
 static final String SLUG="[a-z0-9]+(-[a-z0-9]+)*";
 static final String IMAGE="(https://|/)[^\\s\"<>]+";
 public record Category(UUID id,String slug,String name,int sortOrder,long eventCount) {}
 public record CategoryRef(UUID id,String slug,String name) {}
 public record Venue(UUID id,String slug,String name,String city,String address,String description,String imageUrl,long version) {}
 public record Performer(UUID id,String slug,String name,String description,String imageUrl,long version) {}
 public record VenueInput(@NotNull UUID requestId,@NotNull @Min(0) Long expectedVersion,
  @NotBlank @Pattern(regexp=SLUG) @Size(max=80) String slug,@NotBlank @Size(max=160) String name,
  @NotNull @Size(max=40) String city,@NotNull @Size(max=200) String address,@NotNull @Size(max=3000) String description,
  @Size(max=500) @Pattern(regexp=IMAGE) String imageUrl) {}
 public record PerformerInput(@NotNull UUID requestId,@NotNull @Min(0) Long expectedVersion,
  @NotBlank @Pattern(regexp=SLUG) @Size(max=80) String slug,@NotBlank @Size(max=120) String name,
  @NotNull @Size(max=3000) String description,@Size(max=500) @Pattern(regexp=IMAGE) String imageUrl) {}
 /** expectedVersion 為 0 代表此活動尚無檔案。 */
 public record ProfileInput(@NotNull UUID requestId,@NotNull @Min(0) Long expectedVersion,UUID categoryId,UUID venueId,
  @NotNull @Size(max=20) List<@NotNull UUID> performerIds,@Size(max=500) @Pattern(regexp=IMAGE) String imageUrl) {}
 /** id 等同 eventId，讓後台共用的原命令核對（需要 id 與 version）可沿用。 */
 @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value="id",allowGetters=true)
 public record Profile(UUID eventId,CategoryRef category,Venue venue,List<Performer> performers,String imageUrl,long version) {
  @com.fasterxml.jackson.annotation.JsonProperty("id") public UUID id(){return eventId;}
 }
}

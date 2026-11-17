package dev.jackteng.encore.customer;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.customer.EngagementService.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer")
public class EngagementController {
 private final EngagementService service;
 public EngagementController(EngagementService service){this.service=service;}
 @GetMapping("/events/{eventId}/engagement") public EventFlags flags(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId){return service.flags(p,eventId);}
 @PostMapping("/events/{eventId}/favorite") public EventFlags favorite(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@Valid @RequestBody Toggle in){return service.setFavorite(p,eventId,in.on());}
 @PostMapping("/events/{eventId}/reminder") public EventFlags reminder(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@Valid @RequestBody Toggle in){return service.setReminder(p,eventId,in.on());}
 @GetMapping("/favorites") public Page<FavoriteItem> favorites(@AuthenticationPrincipal Principal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return service.favorites(p,page,size);}
 @GetMapping("/notifications") public Page<Notification> notifications(@AuthenticationPrincipal Principal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.notifications(p,page,size);}
 @GetMapping("/notifications/summary") public Summary summary(@AuthenticationPrincipal Principal p){return service.summary(p);}
 @PostMapping("/notifications/read") public Summary read(@AuthenticationPrincipal Principal p,@Valid @RequestBody MarkRead in){return service.markRead(p,in);}
}

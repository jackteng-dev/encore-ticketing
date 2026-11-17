package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.*;
import java.util.UUID;
import java.security.Principal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class CatalogController {
 private final CatalogService service;
 public CatalogController(CatalogService service){this.service=service;}
 @GetMapping("/public/events") public Page<PublicEvent> events(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
  @RequestParam(required=false) String q,@RequestParam(required=false) String category,@RequestParam(required=false) String venue,@RequestParam(required=false) String performer,
  @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
  @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
  @RequestParam(defaultValue="newest") String sort,@RequestParam(defaultValue="false") boolean onSale){
  PublicSort order=switch(sort){case "newest"->PublicSort.NEWEST;case "date"->PublicSort.DATE;case "price"->PublicSort.PRICE;
   default->throw new dev.jackteng.encore.common.ApiErrors.Problem(400,"INVALID_INPUT","不支援的排序");};
  return service.search(q,category,venue,performer,from,to,order,onSale,page,size);}
 @GetMapping("/public/events/{id}") public Detail publicDetail(@PathVariable UUID id){return service.detail(id,true);}
 @GetMapping("/admin/events") public Page<Event> adminEvents(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return service.list(false,page,size);}
 @GetMapping("/admin/events/{id}") public Detail adminDetail(@PathVariable UUID id){return service.detail(id,false);}
 @GetMapping("/admin/audit") public Page<Audit> audit(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.audit(page,size);}
 @PostMapping("/admin/events") public Event create(Principal p,@Valid @RequestBody EventInput in){return service.createEvent(p.getName(),in);}
 @PutMapping("/admin/events/{id}") public Event update(Principal p,@PathVariable UUID id,@Valid @RequestBody EventInput in){return service.updateEvent(p.getName(),id,in);}
 @PostMapping("/admin/events/{id}/publish") public Event publish(Principal p,@PathVariable UUID id,@Valid @RequestBody Transition in){return service.transition(p.getName(),id,in,true);}
 @PostMapping("/admin/events/{id}/archive") public Event archive(Principal p,@PathVariable UUID id,@Valid @RequestBody Transition in){return service.transition(p.getName(),id,in,false);}
 @PostMapping("/admin/events/{id}/sessions") public Session session(Principal p,@PathVariable UUID id,@Valid @RequestBody SessionInput in){return service.createSession(p.getName(),id,in);}
 @PutMapping("/admin/sessions/{id}") public Session updateSession(Principal p,@PathVariable UUID id,@Valid @RequestBody SessionInput in){return service.updateSession(p.getName(),id,in);}
 @PostMapping("/admin/sessions/{id}/tickets") public Ticket ticket(Principal p,@PathVariable UUID id,@Valid @RequestBody TicketInput in){return service.createTicket(p.getName(),id,in);}
 @PutMapping("/admin/tickets/{id}") public Ticket updateTicket(Principal p,@PathVariable UUID id,@Valid @RequestBody TicketInput in){return service.updateTicket(p.getName(),id,in);}
}

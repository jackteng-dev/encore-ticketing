package dev.elpsy.ticket.inventory;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import dev.elpsy.ticket.inventory.StockMaintenanceService.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/stock")
public class StockMaintenanceController {
 private final StockMaintenanceService service;public StockMaintenanceController(StockMaintenanceService service){this.service=service;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","員工帳號已變更，請由原員工核對操作");return p.getName();}
 @GetMapping("/tickets") public Page<Ticket> tickets(Principal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return service.tickets(p.getName(),page,size);}
 @GetMapping("/tickets/{id}") public Ticket ticket(Principal p,@PathVariable UUID id){return service.ticket(p.getName(),id);}
 @GetMapping("/tickets/{id}/issues") public Page<Issue> issues(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return service.issues(p.getName(),id,page,size);}
 @PostMapping("/tickets/{id}/rebuilds") public ResponseEntity<Receipt> rebuild(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID id,@Valid @RequestBody Begin b){var r=service.submit(actor(p,expected),id,b);return ResponseEntity.status(r.state().equals("REGISTERED")||r.state().equals("CLOSED")?202:200).location(URI.create("/api/admin/stock/rebuilds/"+r.requestId())).body(r);}
 @GetMapping("/rebuilds/{id}") public Receipt progress(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID id){return service.progress(actor(p,expected),id);}
 @GetMapping("/rebuilds/{id}/audit") public Page<Audit> audit(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.audit(p.getName(),id,page,size);}
}

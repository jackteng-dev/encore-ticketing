package dev.elpsy.ticket.inventory;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import dev.elpsy.ticket.inventory.StockReturnStaffService.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/stock")
public class StockReturnStaffController {
 private final StockReturnStaffService service; public StockReturnStaffController(StockReturnStaffService service){this.service=service;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對返還操作");return p.getName();}
 @GetMapping("/return-settings") public Settings settings(Principal p){return service.settings(p.getName());}
 @GetMapping("/tickets/{id}/return-candidates") public Page<Candidate> candidates(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return service.candidates(p.getName(),id,page,size);}
 @PostMapping("/tickets/{ticket}/orders/{order}/returns") public ResponseEntity<Receipt> submit(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID ticket,@PathVariable UUID order,@Valid @RequestBody Begin b){var r=service.submit(actor(p,expected),ticket,order,b);return ResponseEntity.status(r.state().equals("REGISTERED")||r.state().equals("CLOSED")?202:200).location(URI.create("/api/admin/stock/returns/"+r.requestId())).body(r);}
 @GetMapping("/returns/{id}") public Receipt progress(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID id){return service.progress(actor(p,expected),id);}
 @GetMapping("/returns/{id}/audit") public Page<Audit> audit(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.audit(p.getName(),id,page,size);}
}

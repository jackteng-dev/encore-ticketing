package dev.elpsy.ticket.inventory;
import dev.elpsy.ticket.catalog.Models.Page;import dev.elpsy.ticket.common.ApiErrors.Problem;import dev.elpsy.ticket.inventory.StockReviewService.*;
import jakarta.validation.Valid;import java.net.URI;import java.security.Principal;import java.util.UUID;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/stock")
public class StockReviewController {
 private final StockReviewService service;public StockReviewController(StockReviewService service){this.service=service;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","員工帳號已變更，請由原員工核對操作");return p.getName();}
 @GetMapping("/review-settings") public Settings settings(Principal p){return service.settings(p.getName());}
 @PostMapping("/intents/{id}/reviews") public ResponseEntity<Receipt> submit(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID id,@Valid @RequestBody Begin b){var r=service.submit(actor(p,expected),id,b);return ResponseEntity.status(r.state().equals("REGISTERED")||r.state().equals("PENDING")?202:200).location(URI.create("/api/admin/stock/reviews/"+r.requestId())).body(r);}
 @GetMapping("/reviews/{id}") public Receipt progress(Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false) String expected,@PathVariable UUID id){return service.progress(actor(p,expected),id);}
 @GetMapping("/reviews/{id}/audit") public Page<Audit> audit(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.audit(p.getName(),id,page,size);}
}

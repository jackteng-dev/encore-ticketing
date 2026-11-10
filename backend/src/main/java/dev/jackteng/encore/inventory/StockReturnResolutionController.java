package dev.jackteng.encore.inventory;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.inventory.StockReturnResolutionService.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/stock/returns/{request}/resolution")
public class StockReturnResolutionController {
 private final StockReturnResolutionService service;
 public StockReturnResolutionController(StockReturnResolutionService service){this.service=service;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對返還結案");return p.getName();}
 @GetMapping public Preview preview(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected){return service.preview(actor(p,expected),request);}
 @PostMapping public Receipt submit(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Begin b){return service.submit(actor(p,expected),request,b);}
 @GetMapping("/recovery") public Recovery recovery(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@RequestParam long expectedVersion,@RequestParam UUID replacementRequestId){return service.recovery(actor(p,expected),request,new Begin(request,expectedVersion,replacementRequestId));}
 @PostMapping("/reconcile") public Recovery reconcile(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Begin b){return service.reconcile(actor(p,expected),request,b);}
 @PostMapping("/rebind") public Receipt rebind(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Rebind b){return service.rebind(actor(p,expected),request,b);}
 @GetMapping("/recovery-audit") public Page<RecoveryAudit> recoveryAudit(Principal p,@PathVariable UUID request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return service.recoveryAudit(p.getName(),request,page,size);}
 @GetMapping("/audit") public Page<Audit> audit(Principal p,@PathVariable UUID request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return service.audit(p.getName(),request,page,size);}
}

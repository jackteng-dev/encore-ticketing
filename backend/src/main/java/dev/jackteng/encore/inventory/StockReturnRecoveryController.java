package dev.jackteng.encore.inventory;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.inventory.StockReturnRecoveryStore.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/stock")
public class StockReturnRecoveryController {
 private final StockReturnRecoveryStore store;
 public StockReturnRecoveryController(StockReturnRecoveryStore store){this.store=store;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對背景返還工作");return p.getName();}
 @GetMapping("/return-recovery-settings") public Settings settings(Principal p){return store.settings(p.getName());}
 @GetMapping("/returns/{request}/recovery-job") public Job read(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected){return store.read(actor(p,expected),request);}
 @PostMapping("/returns/{request}/recovery-job") public Job enroll(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody StockReturnStaffService.Begin body){return store.enroll(actor(p,expected),request,body);}
 @GetMapping("/returns/{request}/recovery-job/audit") public Page<Audit> audit(Principal p,@PathVariable UUID request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return store.audit(p.getName(),request,page,size);}
}

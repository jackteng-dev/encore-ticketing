package dev.elpsy.ticket.inventory;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import dev.elpsy.ticket.inventory.StockReturnDiscoveryService.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/stock/return-discovery")
public class StockReturnDiscoveryController {
 private final StockReturnDiscoveryService service;
 public StockReturnDiscoveryController(StockReturnDiscoveryService service){this.service=service;}
 private String actor(Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對自動返還授權");return p.getName();}
 @GetMapping("/settings") public Settings settings(Principal p){return service.settings(p.getName());}
 @GetMapping("/tickets/{ticket}/policy") public Policy forTicket(Principal p,@PathVariable UUID ticket){return service.forTicket(p.getName(),ticket);}
 @GetMapping("/tickets/{ticket}/policy-requests/{request}") public EnrollmentProof enrollmentProof(Principal p,@PathVariable UUID ticket,@PathVariable UUID request,@RequestParam int maxSources){return service.enrollmentProof(p.getName(),ticket,request,maxSources);}
 @GetMapping("/policies/{request}/stop-requests/{stop}") public StopProof stopProof(Principal p,@PathVariable UUID request,@PathVariable UUID stop,@RequestParam long expectedVersion){return service.stopProof(p.getName(),request,stop,expectedVersion);}
 @PostMapping("/tickets/{ticket}/policies") public Policy enroll(Principal p,@PathVariable UUID ticket,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Enroll body){return service.enroll(actor(p,expected),ticket,body);}
 @GetMapping("/policies/{request}") public Policy read(Principal p,@PathVariable UUID request){return service.read(p.getName(),request);}
 @PostMapping("/policies/{request}/stop") public Policy stop(Principal p,@PathVariable UUID request,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Stop body){return service.stop(actor(p,expected),request,body);}
 @GetMapping("/policies/{request}/sources") public Page<Source> sources(Principal p,@PathVariable UUID request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return service.sources(p.getName(),request,page,size);}
}

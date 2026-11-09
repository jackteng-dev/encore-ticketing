package dev.elpsy.ticket.tickets;
import dev.elpsy.ticket.tickets.TicketModels.*;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class TicketController {
 private final TicketService service;public TicketController(TicketService service){this.service=service;}
 private String actor(java.security.Principal p,String expected){if(expected!=null&&!expected.equals(p.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對票券操作");return p.getName();}
 @GetMapping("/api/customer/orders/{id}/tickets") public Bundle read(@AuthenticationPrincipal Principal p,@PathVariable UUID id){return service.read(p,id);}
 @PostMapping("/api/customer/orders/{id}/tickets") public Bundle issue(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@Valid @RequestBody Issue b){return service.issue(p,id,b);}
 @GetMapping("/api/admin/admissions/settings") public Settings settings(java.security.Principal p){return service.settings(p.getName());}
 @GetMapping("/api/admin/admissions/tickets") public Page<StaffTicket> list(java.security.Principal p,@RequestParam UUID sessionId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return service.list(p.getName(),sessionId,page,size);}
 @GetMapping("/api/admin/admissions/tickets/{id}/audit") public Page<Audit> audit(java.security.Principal p,@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size){return service.audit(p.getName(),id,page,size);}
 @PostMapping("/api/admin/admissions/inspect") public StaffTicket inspect(java.security.Principal p,@Valid @RequestBody Inspect b){return service.inspect(p.getName(),b);}
 @PostMapping("/api/admin/admissions/check-ins") public Receipt checkIn(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody CheckIn b){return service.checkIn(actor(p,expected),b);}
 @PostMapping("/api/admin/admissions/revocations") public Receipt revoke(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Revoke b){return service.revoke(actor(p,expected),b);}

 @PostMapping("/api/customer/orders/{id}/tickets/receipt") public IssueRecovery issueReceipt(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@Valid @RequestBody Issue b){return service.issueReceipt(p,id,b);}
 @PostMapping("/api/customer/orders/{id}/tickets/stop") public IssueRecovery stopIssue(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@Valid @RequestBody Issue b){return service.stopIssue(p,id,b);}
 @PostMapping("/api/admin/admissions/check-ins/receipt") public AdmissionRecovery checkInReceipt(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody CheckIn b){return service.checkInReceipt(actor(p,expected),b);}
 @PostMapping("/api/admin/admissions/check-ins/stop") public AdmissionRecovery stopCheckIn(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody CheckIn b){return service.stopCheckIn(actor(p,expected),b);}
 @PostMapping("/api/admin/admissions/revocations/receipt") public AdmissionRecovery revokeReceipt(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Revoke b){return service.revokeReceipt(actor(p,expected),b);}
 @PostMapping("/api/admin/admissions/revocations/stop") public AdmissionRecovery stopRevoke(java.security.Principal p,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Revoke b){return service.stopRevoke(actor(p,expected),b);}
}

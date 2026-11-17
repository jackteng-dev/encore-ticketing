package dev.jackteng.encore.orders;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.orders.RefundService.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class RefundController {
 private final RefundService service;
 public RefundController(RefundService service){this.service=service;}
 @PostMapping("/api/customer/orders/{id}/refund") public Refund apply(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@Valid @RequestBody Apply in){return service.apply(p,id,in);}
 @PostMapping("/api/customer/refunds/{id}/withdraw") public Refund withdraw(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@Valid @RequestBody Withdraw in){return service.withdraw(p,id,in);}
 @GetMapping("/api/customer/refunds") public List<Refund> latest(@AuthenticationPrincipal Principal p,@RequestParam(defaultValue="") List<UUID> orderIds){return service.latest(p,orderIds);}
 @GetMapping("/api/admin/refunds/{id}/tickets") public List<RefundTicket> tickets(java.security.Principal p,@PathVariable UUID id){return service.tickets(p.getName(),id);}
 @GetMapping("/api/admin/refunds") public Page<Refund> queue(java.security.Principal p,@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.queue(p.getName(),status,page,size);}
 @PostMapping("/api/admin/refunds/{id}/review") public Refund review(java.security.Principal p,@PathVariable UUID id,@Valid @RequestBody Review in){return service.review(p.getName(),id,in);}
}

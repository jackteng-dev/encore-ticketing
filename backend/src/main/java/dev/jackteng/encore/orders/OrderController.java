package dev.jackteng.encore.orders;
import dev.jackteng.encore.orders.OrderModels.*;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.catalog.Models.Page;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer/orders")
public class OrderController {
 private final OrderService service;private final PaymentService payments;public OrderController(OrderService service,PaymentService payments){this.service=service;this.payments=payments;}
 @PostMapping public Order place(@AuthenticationPrincipal Principal principal,@Valid @RequestBody Place body){return service.place(principal,body);}
 @GetMapping public Page<Order> list(@AuthenticationPrincipal Principal principal,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return service.list(principal,page,size);}
 @GetMapping("/{id}") public Order detail(@AuthenticationPrincipal Principal principal,@PathVariable UUID id){return service.detail(principal,id);}
 @PostMapping("/{id}/cancel") public Order cancel(@AuthenticationPrincipal Principal principal,@PathVariable UUID id,@Valid @RequestBody Cancel body){return service.cancel(principal,id,body);}
 @PostMapping("/{id}/payments") public Order pay(@AuthenticationPrincipal Principal principal,@PathVariable UUID id,@Valid @RequestBody PaymentModels.Pay body){return payments.start(principal,id,body);}
 @PostMapping("/{id}/payments/{attempt}/reconcile") public Order reconcile(@AuthenticationPrincipal Principal principal,@PathVariable UUID id,@PathVariable UUID attempt,@Valid @RequestBody PaymentModels.Reconcile body){return payments.reconcile(principal,id,attempt,body);}
}

package dev.elpsy.ticket.orders;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.orders.OrderModels.Place;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer/purchase-attempts")
public class StockCheckoutController {
 private final StockCheckoutService service;public StockCheckoutController(StockCheckoutService service){this.service=service;}
 @PostMapping public ResponseEntity<StockCheckoutService.Progress> submit(@AuthenticationPrincipal Principal principal,@Valid @RequestBody Place body){var p=service.submit(principal,body);return ResponseEntity.status(p.purchase()==null&&!p.state().equals("ABORTED")||p.state().equals("PROCESSING")?202:200).location(URI.create("/api/customer/purchase-attempts/"+p.requestId())).body(p);}
 @GetMapping("/{requestId}") public StockCheckoutService.Progress progress(@AuthenticationPrincipal Principal principal,@PathVariable UUID requestId){return service.progress(principal,requestId);}
}

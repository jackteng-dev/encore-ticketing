package dev.elpsy.ticket.orders;
import dev.elpsy.ticket.orders.OrderModels.*;
import dev.elpsy.ticket.orders.PurchaseModels.Purchase;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.catalog.Models.Page;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer/purchases")
public class PurchaseController {
 private final PurchaseService service;public PurchaseController(PurchaseService service){this.service=service;}
 @PostMapping public ResponseEntity<Purchase> submit(@AuthenticationPrincipal Principal principal,@Valid @RequestBody Place body){var p=service.submit(principal,body);return ResponseEntity.status(p.status().equals("PROCESSING")?202:200).location(URI.create("/api/customer/purchases/"+p.id())).body(p);}
 @GetMapping public Page<Purchase> list(@AuthenticationPrincipal Principal principal,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return service.list(principal,page,size);}
 @GetMapping("/{id}") public Purchase detail(@AuthenticationPrincipal Principal principal,@PathVariable UUID id){return service.detail(principal,id);}
 @PostMapping("/{id}/cancel") public Purchase cancel(@AuthenticationPrincipal Principal principal,@PathVariable UUID id,@Valid @RequestBody Cancel body){return service.cancel(principal,id,body);}
}

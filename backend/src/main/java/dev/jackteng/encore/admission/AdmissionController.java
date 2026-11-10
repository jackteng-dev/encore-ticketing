package dev.jackteng.encore.admission;
import dev.jackteng.encore.admission.AdmissionService.*;
import dev.jackteng.encore.customer.CustomerService.Principal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer/waiting-room/{ticket}")
public class AdmissionController {
 private final AdmissionService service;public AdmissionController(AdmissionService service){this.service=service;}
 @PostMapping("/join") public State join(@AuthenticationPrincipal Principal p,@PathVariable UUID ticket,@Valid @RequestBody Join body){return service.join(p,ticket,body);}
 @GetMapping public State status(@AuthenticationPrincipal Principal p,@PathVariable UUID ticket){return service.status(p,ticket);}
}

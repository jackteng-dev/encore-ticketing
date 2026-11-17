package dev.jackteng.encore.customer;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.customer.ProfileService.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/customer/profile")
public class ProfileController {
 private final ProfileService service;
 public ProfileController(ProfileService service){this.service=service;}
 @GetMapping public Profile read(@AuthenticationPrincipal Principal p){return service.read(p);}
 @PostMapping public Profile update(@AuthenticationPrincipal Principal p,@Valid @RequestBody Update in){return service.update(p,in);}
 @PostMapping("/verifications") public Sent start(@AuthenticationPrincipal Principal p,@Valid @RequestBody Start in){return service.start(p,in);}
 @PostMapping("/verifications/confirm") public Profile confirm(@AuthenticationPrincipal Principal p,@Valid @RequestBody Confirm in){
  try{return service.confirm(p,in);}catch(WrongCode e){throw new Problem(400,"WRONG_CODE","驗證碼錯誤");}
 }
}

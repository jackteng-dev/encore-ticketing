package dev.jackteng.encore.customer;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/auth")
public class CustomerController {
 public record Registration(@NotNull @Pattern(regexp="[a-z0-9][a-z0-9_-]{2,39}") String handle,
  @NotBlank @Size(max=80) String displayName,@NotNull @Size(min=12,max=72) String password) {}
 public record Login(@NotNull @Pattern(regexp="[a-z0-9][a-z0-9_-]{2,39}") String handle,@NotNull @Size(min=12,max=72) String password) {}
 private final CustomerService service;
 private final CookieCsrfTokenRepository csrf;
 private final boolean secure;
 public CustomerController(CustomerService service,@org.springframework.beans.factory.annotation.Qualifier("customerCsrf") CookieCsrfTokenRepository csrf,@Value("${server.servlet.session.cookie.secure}") boolean secure){this.service=service;this.csrf=csrf;this.secure=secure;}
 @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("headerName",token.getHeaderName(),"token",token.getToken());}
 @PostMapping("/register") public CustomerService.Profile register(@Valid @RequestBody Registration body,HttpServletRequest req,HttpServletResponse res){
  return finish(service.register(body.handle(),body.displayName(),body.password()),req,res);
 }
 @PostMapping("/login") public CustomerService.Profile login(@Valid @RequestBody Login body,HttpServletRequest req,HttpServletResponse res){
  return finish(service.login(body.handle(),body.password()),req,res);
 }
 private CustomerService.Profile finish(CustomerService.Issued issued,HttpServletRequest req,HttpServletResponse res){
  // Rotate the current browser's session; a lost response can be resolved by logging in again.
  service.logout(CustomerAuthenticationFilter.token(req));
  cookie(res,issued.token(),Duration.ofMinutes(30));csrf.saveToken(null,req,res);
  return issued.profile();
 }
 @GetMapping("/me") public CustomerService.Profile me(@AuthenticationPrincipal CustomerService.Principal principal){return service.profile(principal);}
 @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest req,HttpServletResponse res){
  service.logout(CustomerAuthenticationFilter.token(req));cookie(res,"",Duration.ZERO);csrf.saveToken(null,req,res);return Map.of("authenticated",false);
 }
 private void cookie(HttpServletResponse res,String value,Duration age){
  res.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(CustomerAuthenticationFilter.COOKIE,value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/customer").maxAge(age).build().toString());
 }
}

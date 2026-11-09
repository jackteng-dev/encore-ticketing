package dev.elpsy.ticket.identity;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
 private final StaffSessionService sessions;private final org.springframework.security.web.csrf.CookieCsrfTokenRepository csrf;private final boolean secure;
 public AuthController(StaffSessionService sessions,org.springframework.security.web.csrf.CookieCsrfTokenRepository staffCsrf,@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure}")boolean secure){this.sessions=sessions;this.csrf=staffCsrf;this.secure=secure;}
 @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) {
  return Map.of("headerName",token.getHeaderName(),"token",token.getToken());
 }
 @GetMapping("/me") public Map<String,Object> me(Authentication auth) {
  return Map.of("username",auth.getName(),"canWrite",auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("CATALOG_WRITE")));
 }
 @PostMapping(value="/login",consumes="application/x-www-form-urlencoded") public Map<String,Boolean> login(@RequestParam org.springframework.util.MultiValueMap<String,String> body,jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res){
  if(!body.keySet().equals(java.util.Set.of("username","password"))||body.values().stream().anyMatch(v->v.size()!=1))throw new dev.elpsy.ticket.common.ApiErrors.Problem(400,"INVALID_INPUT","請提供員工帳號與密碼");
  var issued=sessions.login(body.getFirst("username"),body.getFirst("password"),StaffSessionFilter.token(req));
  cookie(res,"",java.time.Duration.ZERO,"/");cookie(res,issued.token(),java.time.Duration.ofMinutes(30),"/api");csrf.saveToken(null,req,res);return Map.of("authenticated",true);
 }
 @PostMapping("/logout") public Map<String,Boolean> logout(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res){
  sessions.logout(StaffSessionFilter.token(req));cookie(res,"",java.time.Duration.ZERO,"/");cookie(res,"",java.time.Duration.ZERO,"/api");csrf.saveToken(null,req,res);return Map.of("authenticated",false);
 }
 private void cookie(jakarta.servlet.http.HttpServletResponse res,String value,java.time.Duration age,String path){res.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,org.springframework.http.ResponseCookie.from(StaffSessionFilter.COOKIE,value).httpOnly(true).secure(secure).sameSite("Strict").path(path).maxAge(age).build().toString());}
}

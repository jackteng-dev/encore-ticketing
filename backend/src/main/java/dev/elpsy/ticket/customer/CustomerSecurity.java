package dev.elpsy.ticket.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class CustomerSecurity {
 @Bean CookieCsrfTokenRepository customerCsrf(@Value("${server.servlet.session.cookie.secure}") boolean secure){
  var repo=new CookieCsrfTokenRepository();
  repo.setCookieName("TICKET_CUSTOMER_CSRF");repo.setHeaderName("X-CUSTOMER-CSRF");repo.setCookiePath("/api/customer");
  repo.setCookieCustomizer(c->c.httpOnly(true).secure(secure).sameSite("Strict"));
  return repo;
 }
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
 @Order(1) SecurityFilterChain customers(HttpSecurity http,CustomerService service,CookieCsrfTokenRepository customerCsrf)throws Exception {
  http.securityMatcher("/api/customer/**")
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .securityContext(c->c.securityContextRepository(new NullSecurityContextRepository()))
   .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
   .csrf(c->c.csrfTokenRepository(customerCsrf))
   .addFilterBefore(new CustomerAuthenticationFilter(service),AnonymousAuthenticationFilter.class)
   .authorizeHttpRequests(a->a.requestMatchers("/api/customer/auth/csrf","/api/customer/auth/register","/api/customer/auth/login","/api/customer/auth/logout").permitAll().anyRequest().hasAuthority("CUSTOMER"))
   .exceptionHandling(e->e.authenticationEntryPoint((q,s,x)->reply(s,401,"UNAUTHENTICATED","請先登入顧客帳號"))
    .accessDeniedHandler((q,s,x)->reply(s,403,"FORBIDDEN","顧客安全驗證已過期，請重新操作")));
  return http.build();
 }
 private static void reply(jakarta.servlet.http.HttpServletResponse s,int code,String kind,String message)throws java.io.IOException {
  s.setStatus(code);s.setContentType("application/json;charset=UTF-8");s.getWriter().write("{\"code\":\""+kind+"\",\"message\":\""+message+"\"}");
 }
}

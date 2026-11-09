package dev.elpsy.ticket.identity;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwords() {return new BCryptPasswordEncoder();}
 @Bean UserDetailsService users(JdbcTemplate db) {
  return username -> db.query("SELECT username,password_hash,role,enabled FROM staff_account WHERE username=?",
   (r,n)->User.withUsername(r.getString(1)).password(r.getString(2)).authorities("CATALOG_READ",
    r.getString(3).equals("MANAGER")?"CATALOG_WRITE":"CATALOG_VIEW").disabled(!r.getBoolean(4)).build(),username)
   .stream().findFirst().orElseThrow(()->new UsernameNotFoundException("Unknown staff"));
 }
 @Bean org.springframework.security.web.csrf.CookieCsrfTokenRepository staffCsrf(@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure}")boolean secure){
  var repo=new org.springframework.security.web.csrf.CookieCsrfTokenRepository();repo.setCookieName("TICKET_STAFF_CSRF");repo.setHeaderName("X-STAFF-CSRF");repo.setCookiePath("/api");repo.setCookieCustomizer(c->c.httpOnly(true).secure(secure).sameSite("Strict"));return repo;
 }
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
 @org.springframework.core.annotation.Order(2) SecurityFilterChain security(HttpSecurity http,JdbcTemplate db,StaffSessionService sessions,org.springframework.security.web.csrf.CookieCsrfTokenRepository staffCsrf) throws Exception {
  http.sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
   .securityContext(c->c.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
   .csrf(c->c.csrfTokenRepository(staffCsrf))
   .addFilterBefore(new StaffSessionFilter(db,sessions),org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
  http.authorizeHttpRequests(a->a.requestMatchers("/api/public/**","/api/auth/csrf","/api/auth/login","/api/auth/logout").permitAll()
   .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/admin/**").hasAuthority("CATALOG_READ")
   .requestMatchers("/api/admin/**").hasAuthority("CATALOG_WRITE").anyRequest().authenticated())
   .requestCache(c->c.disable())
   .formLogin(f->f.disable()).httpBasic(c->c.disable()).logout(l->l.disable())
   .exceptionHandling(e->e.authenticationEntryPoint((q,s,x)->reply(s,401,"{\"code\":\"UNAUTHENTICATED\",\"message\":\"請先登入\"}"))
    .accessDeniedHandler((q,s,x)->reply(s,403,"{\"code\":\"FORBIDDEN\",\"message\":\"權限不足或安全驗證已過期\"}")));
  return http.build();
 }
 private static void reply(jakarta.servlet.http.HttpServletResponse s,int status,String json) throws java.io.IOException {
  s.setStatus(status);s.setContentType("application/json;charset=UTF-8");s.getWriter().write(json);
 }
}

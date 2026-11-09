package dev.elpsy.ticket.customer;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CustomerAuthenticationFilter extends OncePerRequestFilter {
 public static final String COOKIE="TICKET_CUSTOMER_SESSION";
 private final CustomerService service;
 public CustomerAuthenticationFilter(CustomerService service){this.service=service;}
 public static String token(HttpServletRequest req){
  if(req.getCookies()!=null)for(var c:req.getCookies())if(c.getName().equals(COOKIE))return c.getValue();
  return null;
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
  var authenticated=service.authenticate(token(req));
  // Optional assertion prevents stale tabs replaying an original command under a switched cookie.
  // It only compares against verified identity; it never supplies the owner or authorizes a request.
  String expected=req.getHeader("X-Expected-Customer-ID");
  if(expected!=null&&(authenticated.isEmpty()||!authenticated.get().id().toString().equals(expected))){
   res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"CUSTOMER_CHANGED\",\"message\":\"會員已切換或登入已到期，請由原顧客核對操作\"}");return;
  }
  authenticated.ifPresent(principal->{
   var context=SecurityContextHolder.createEmptyContext();
   context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("CUSTOMER"))));
   SecurityContextHolder.setContext(context);
  });
  chain.doFilter(req,res);
 }
}

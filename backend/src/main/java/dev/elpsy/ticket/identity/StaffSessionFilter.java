package dev.elpsy.ticket.identity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
public class StaffSessionFilter extends OncePerRequestFilter {
 public static final String COOKIE="TICKET_STAFF_SESSION";
 private final JdbcTemplate db;private final StaffSessionService sessions;
 public StaffSessionFilter(JdbcTemplate db,StaffSessionService sessions){this.db=db;this.sessions=sessions;}
 public static String token(HttpServletRequest req){
  String token=null;int count=0;
  if(req.getCookies()!=null)for(var cookie:req.getCookies())if(COOKIE.equals(cookie.getName())){token=cookie.getValue();count++;}
  return count==1?token:null;
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String token=token(req);
  if(token!=null){
   try{var principal=sessions.authenticate(token);SecurityContextHolder.clearContext();
    principal.ifPresent(p->{var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(p,null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ"),new org.springframework.security.core.authority.SimpleGrantedAuthority(p.role().equals("MANAGER")?"CATALOG_WRITE":"CATALOG_VIEW"))));SecurityContextHolder.setContext(context);});
   }catch(org.springframework.dao.DataAccessException error){SecurityContextHolder.clearContext();reply(res,503,"AUTH_UNAVAILABLE","登入服務暫時無法核對，請保留原操作");return;}
  }
  var auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth!=null&&auth.isAuthenticated()&&!(auth instanceof AnonymousAuthenticationToken)) {
   var roles=db.query("SELECT role FROM staff_account WHERE username=? AND enabled",(r,n)->r.getString(1),auth.getName());
   boolean write=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("CATALOG_WRITE"));
   if(roles.isEmpty()||write!=roles.getFirst().equals("MANAGER")){
    SecurityContextHolder.clearContext();
    res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"員工存取已變更，請重新登入\"}");return;
   }
  }
  String expected=req.getHeader("X-Expected-Staff-Username");
  if(expected!=null&&(auth==null||!auth.isAuthenticated()||auth instanceof AnonymousAuthenticationToken||!expected.equals(auth.getName()))){reply(res,401,"STAFF_CHANGED","員工已切換或登入已到期，請由原員工核對操作");return;}
  chain.doFilter(req,res);
 }
 private static void reply(HttpServletResponse res,int status,String code,String message)throws IOException{res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");}
}

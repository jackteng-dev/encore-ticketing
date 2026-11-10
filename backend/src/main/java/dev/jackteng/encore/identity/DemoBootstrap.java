package dev.jackteng.encore.identity;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration
@ConditionalOnProperty(name="ticket.demo-bootstrap",havingValue="true")
public class DemoBootstrap {
 @Bean ApplicationRunner bootstrap(JdbcTemplate db,PasswordEncoder encoder,
  @Value("${ticket.staff-username}") String username,@Value("${ticket.staff-password}") String password) {
  return args->{
   if(!"ticket_demo".equals(db.queryForObject("SELECT current_database()",String.class)))
    throw new IllegalStateException("Demo bootstrap requires isolated ticket_demo");
   if(db.queryForObject("SELECT count(*) FROM staff_account",Long.class)>0) return;
   if(password.length()<16||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||username.isBlank()||username.length()>80)
    throw new IllegalStateException("Provide a private 16-72 character demo staff password");
   db.update("INSERT INTO staff_account(id,username,password_hash,role) VALUES(?,?,?,'MANAGER')",UUID.randomUUID(),username,encoder.encode(password));
  };
 }
}

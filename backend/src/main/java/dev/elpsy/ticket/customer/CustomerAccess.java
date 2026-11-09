package dev.elpsy.ticket.customer;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
/** Call only inside a transaction. Keep owner -> session lock order across all writes. */
public final class CustomerAccess {
 private CustomerAccess() {}
 public static OffsetDateTime authorize(JdbcTemplate db,Principal principal){
  var accounts=db.query("SELECT enabled,session_version FROM customer_account WHERE id=? FOR UPDATE",(r,n)->java.util.Map.entry(r.getBoolean(1),r.getLong(2)),principal.id());
  if(accounts.isEmpty()||!accounts.getFirst().getKey()||accounts.getFirst().getValue()!=principal.sessionVersion())throw new Problem(401,"UNAUTHENTICATED","顧客存取已變更，請重新登入");
  var session=db.query("SELECT expires_at FROM customer_session WHERE customer_id=? AND token_hash=? AND session_version=? FOR SHARE",(r,n)->r.getObject(1,OffsetDateTime.class),principal.id(),principal.sessionHash(),principal.sessionVersion());
  var clock=db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
  if(session.isEmpty())throw new Problem(401,"UNAUTHENTICATED","顧客登入已過期");
  stillAuthenticated(session.getFirst(),clock);return session.getFirst();
 }
 public static void stillAuthenticated(OffsetDateTime deadline,OffsetDateTime now){if(!deadline.isAfter(now))throw new Problem(401,"UNAUTHENTICATED","顧客登入已過期");}
}

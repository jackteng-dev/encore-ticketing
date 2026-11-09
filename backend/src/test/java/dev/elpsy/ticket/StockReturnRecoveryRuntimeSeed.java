package dev.elpsy.ticket;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.elpsy.ticket.inventory.*;
import dev.elpsy.ticket.orders.*;
import dev.elpsy.ticket.orders.OrderModels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
/** TEST ONLY, run with historical V17 production classes before V18. */
public final class StockReturnRecoveryRuntimeSeed {
 public static void main(String[] args)throws Exception{
  if(args.length!=1||!System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-return-runtime-[a-z0-9-]{1,24}"))throw new IllegalArgumentException("Own scope required");
  var out=StockReturnBaselineSeed.privatePath(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Prior fixture preserved");
  try(var context=SpringApplication.run(TicketApplication.class,"--server.port=0","--ticket.demo-bootstrap=true","--ticket.orders.enabled=true","--ticket.purchases.enabled=true","--ticket.stock-return.enabled=true","--ticket.expiry.enabled=false","--ticket.stock-recovery.enabled=false","--ticket.delivery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false","--ticket.pre-debit.enabled=false")){
   var db=context.getBean(JdbcTemplate.class);var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);var redis=context.getBean(StringRedisTemplate.class);var fixtures=new CommitCursorFixture(context);String manager=System.getenv("TICKET_STAFF_USERNAME");var orders=context.getBean(OrderService.class);var returns=context.getBean(StockReturnStaffService.class);var all=new LinkedHashMap<String,Object>();
   if(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history",Integer.class)!=17)throw new IllegalStateException("True V17 required");
   for(String name:List.of("lost","audit","restart","blocked","authority")){
    var f=fixtures.accepted(false);if(!context.getBean(PurchaseService.class).process(f.event())||!context.getBean(StockSettlementService.class).reconcile(f.intent()).resolution().equals("COMMITTED"))throw new IllegalStateException("Original COMMIT required");var order=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,f.intent());orders.cancel(f.owner(),order,new Cancel(UUID.randomUUID(),1));UUID request=UUID.randomUUID();String actor=manager;
    if(name.equals("authority")){actor="return-authority-runtime";db.update("INSERT INTO staff_account SELECT ?,?,password_hash,'MANAGER',true FROM staff_account WHERE username=?",UUID.randomUUID(),actor,manager);}
    boolean closed=!Set.of("blocked","authority").contains(name);db.execute("ALTER TABLE stock_return_staff_audit ADD CONSTRAINT runtime_return_phase_fault CHECK(state<>'"+(closed?"APPLIED":"CLOSED")+"') NOT VALID");boolean failed=false;
    try{returns.submit(actor,f.ticket(),order,new StockReturnStaffService.Begin(request,3));}catch(org.springframework.dao.DataAccessException expected){failed=true;}finally{db.execute("ALTER TABLE stock_return_staff_audit DROP CONSTRAINT runtime_return_phase_fault");}
    if(!failed||!returns.progress(actor,request).state().equals(closed?"CLOSED":"REGISTERED"))throw new IllegalStateException("Pending original phase required");
    var item=new LinkedHashMap<String,Object>();item.put("ticketId",f.ticket());item.put("orderId",order);item.put("requestId",request);item.put("expectedReleaseRevision",3);item.put("actor",actor);item.put("fixture",f);item.put("originalDomainSha",StockReturnBaselineSeed.domainSha(db,f));item.put("oldLedgerSha",StockReturnBaselineSeed.ledgerSha(f,fixtures,redis));item.put("replacementLedgerSha",closed?CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(fixtures.ledger.key(f.ticket(),2))))):null);all.put(name,item);
   }
   var tree=(ObjectNode)json.valueToTree(Map.of("fixtures",all,"baselineSchema",17));for(var item:tree.path("fixtures"))((ObjectNode)item.path("fixture").path("owner")).remove("name");json.writeValue(out.toFile(),tree);Files.setPosixFilePermissions(out,PosixFilePermissions.fromString("rw-------"));System.out.println("Five original pending returns created against V17; private identities retained");
  }
 }
}

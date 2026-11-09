package dev.elpsy.ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
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
/** TEST ONLY: executable against historical V16 production core, before V17. */
public final class StockReturnResolutionRecoveryRuntimeSeed {
 public static void main(String[] args)throws Exception{
  if(args.length!=1||!System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-recovery-runtime-[a-z0-9-]{1,24}"))throw new IllegalArgumentException("Own runtime required");
  var out=StockReturnBaselineSeed.privatePath(args[0]);if(Files.exists(out))throw new IllegalArgumentException("Prior fixture preserved");
  try(var context=SpringApplication.run(TicketApplication.class,"--server.port=0","--ticket.demo-bootstrap=true","--ticket.orders.enabled=true","--ticket.purchases.enabled=true","--ticket.stock-return.enabled=true","--ticket.stock-maintenance.enabled=true","--ticket.stock-return-resolution.enabled=true","--ticket.expiry.enabled=false","--ticket.stock-recovery.enabled=false","--ticket.delivery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false","--ticket.pre-debit.enabled=false")){
   var db=context.getBean(JdbcTemplate.class);var json=context.getBean(ObjectMapper.class);var redis=context.getBean(StringRedisTemplate.class);var fixtures=new CommitCursorFixture(context);String manager=System.getenv("TICKET_STAFF_USERNAME");var purchases=context.getBean(PurchaseService.class);var settlement=context.getBean(StockSettlementService.class);var orders=context.getBean(OrderService.class);var returns=context.getBean(StockReturnStaffService.class);var maintenance=context.getBean(StockMaintenanceService.class);var all=new LinkedHashMap<String,Object>();
   for(String name:List.of("committed","malformed","rollback","stale","offline","active")){
    var f=fixtures.accepted(false);if(!purchases.process(f.event())||!settlement.reconcile(f.intent()).resolution().equals("COMMITTED"))throw new IllegalStateException("Original COMMIT required");var order=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,f.intent());orders.cancel(f.owner(),order,new Cancel(UUID.randomUUID(),1));orders.place(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));UUID request=UUID.randomUUID();var original=returns.submit(manager,f.ticket(),order,new StockReturnStaffService.Begin(request,3));if(!original.state().equals("BLOCKED"))throw new IllegalStateException("Original blocked required");var snapshot=maintenance.submit(manager,f.ticket(),new StockMaintenanceService.Begin(UUID.randomUUID(),4));if(!snapshot.state().equals("READY"))throw new IllegalStateException("Published snapshot required");if(name.equals("committed"))context.getBean(StockReturnResolutionService.class).submit(manager,request,new StockReturnResolutionService.Begin(request,original.version(),snapshot.requestId()));if(name.equals("active"))context.getBean(StockHoldService.class).prepare(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));
    var item=new LinkedHashMap<String,Object>();item.put("ticketId",f.ticket());item.put("orderId",order);item.put("requestId",request);item.put("expectedVersion",original.version());item.put("replacementRequestId",snapshot.requestId());item.put("fixture",f);item.put("originalDomainSha",StockReturnBaselineSeed.domainSha(db,f));item.put("oldLedgerSha",StockReturnBaselineSeed.ledgerSha(f,fixtures,redis));item.put("replacementLedgerSha",CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(fixtures.ledger.key(f.ticket(),2))))));all.put(name,item);
   }
   var tree=(ObjectNode)json.valueToTree(Map.of("fixtures",all,"baselineSchema",db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)));for(var item:tree.path("fixtures"))((ObjectNode)item.path("fixture").path("owner")).remove("name");json.writeValue(out.toFile(),tree);Files.setPosixFilePermissions(out,PosixFilePermissions.fromString("rw-------"));System.out.println("Six original BLOCKED returns and published snapshots created; identities private");
  }
 }
}

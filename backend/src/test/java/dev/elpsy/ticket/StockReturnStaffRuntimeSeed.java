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

/** TEST ONLY: real original business data, executable against historical V14 core too.
 * No fixtures/runners packaged; HTTP/browser uses the final production JAR independently.
 */
public final class StockReturnStaffRuntimeSeed {
 public static void main(String[] args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("Private fixture path required");
  var path=Path.of(args[0]).toAbsolutePath().normalize();if(!path.startsWith(Path.of("work").toAbsolutePath().normalize())||Files.exists(path))throw new IllegalArgumentException("New private work path required");
  var project=System.getenv("COMPOSE_PROJECT_NAME");if(project==null||!project.matches("ticket-return-staff-runtime-[a-z0-9-]{1,24}"))throw new IllegalArgumentException("Own runtime scope required");
  try(var context=SpringApplication.run(TicketApplication.class,"--server.port=0","--ticket.demo-bootstrap=true","--ticket.orders.enabled=true","--ticket.purchases.enabled=true","--ticket.expiry.enabled=false","--ticket.delivery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false","--ticket.stock-recovery.enabled=false","--ticket.pre-debit.enabled=false")){
   var db=context.getBean(JdbcTemplate.class);var json=context.getBean(ObjectMapper.class);var fixtures=new CommitCursorFixture(context);
   var purchases=context.getBean(PurchaseService.class);var settlement=context.getBean(StockSettlementService.class);var orders=context.getBean(OrderService.class);
   var all=new LinkedHashMap<String,Object>();
   for(String name:List.of("lost","malformed","rollback","stale","offline","expiry")){
    var f=fixtures.accepted(false);if(!purchases.process(f.event())||!"COMMITTED".equals(settlement.reconcile(f.intent()).resolution()))throw new IllegalStateException("Original core acknowledgement required");
    UUID order=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,f.intent());
    if(name.equals("expiry")){db.update("WITH stamp AS MATERIALIZED(SELECT clock_timestamp() clock) UPDATE ticket_order SET created_at=clock-interval '11 minutes',expires_at=clock-interval '1 minute' FROM stamp WHERE id=?",order);if(!orders.expire(order))throw new IllegalStateException("Original expiry required");}else orders.cancel(f.owner(),order,new Cancel(UUID.randomUUID(),1));
    if(name.equals("stale"))orders.place(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));
    var value=new LinkedHashMap<String,Object>();value.put("ticketId",f.ticket());value.put("orderId",order);value.put("releaseRevision",3);value.put("title","實驗演出");value.put("fixture",f);value.put("originalLedgerSha",StockReturnBaselineSeed.ledgerSha(f,fixtures,context.getBean(StringRedisTemplate.class)));value.put("originalDomainSha",StockReturnBaselineSeed.domainSha(db,f));all.put(name,value);
   }
   var tree=(ObjectNode)json.valueToTree(Map.of("fixtures",all,"baselineSchema",db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)));
   for(var item:tree.path("fixtures"))((ObjectNode)item.path("fixture").path("owner")).remove("name");
   json.writeValue(path.toFile(),tree);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
   System.out.println("Six original formal return fixtures created by core services; private identities not printed.");
  }
 }
}

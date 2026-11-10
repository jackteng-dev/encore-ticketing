package dev.jackteng.encore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.inventory.*;
import dev.jackteng.encore.orders.*;
import dev.jackteng.encore.orders.OrderModels.Cancel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** TEST ONLY: executable against the historical V13 production JAR, without V14 classes. */
public final class StockReturnBaselineSeed {
 public record Saved(CommitCursorFixture.Fixture fixture,UUID order,UUID request,String ledgerSha,String domainSha) {}
 public static void require(boolean yes,String message){if(!yes)throw new IllegalStateException(message);}
 public static String[] settings(){return new String[]{"--server.port=0","--ticket.orders.enabled=true","--ticket.purchases.enabled=true","--ticket.delivery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false","--ticket.expiry.enabled=false","--ticket.stock-recovery.enabled=false","--ticket.stock-maintenance.enabled=false","--ticket.stock-review.enabled=false","--ticket.pre-debit.enabled=false","--ticket.admission.enabled=false","--ticket.demo-bootstrap=false"};}
 public static Path privatePath(String path){var p=Path.of(path).toAbsolutePath().normalize();require(p.startsWith(Path.of("work").toAbsolutePath().normalize()),"Private work only");return p;}
 public static String ledgerSha(CommitCursorFixture.Fixture f,CommitCursorFixture fixtures,StringRedisTemplate redis)throws Exception{return CommitCursorExperiment.sha(new ObjectMapper().writeValueAsString(new TreeMap<>(redis.opsForHash().entries(fixtures.ledger.key(f.ticket(),1)))));}
 public static String domainSha(JdbcTemplate db,CommitCursorFixture.Fixture f){
  var sql=new StringBuilder("SELECT jsonb_build_array(");
  for(String table:List.of("ticket_type","stock_revision","stock_revision_context","stock_revision_origin","stock_origin_audit","ticket_order","order_inventory_event")){
   if(sql.charAt(sql.length()-1)!='(')sql.append(',');sql.append("(SELECT jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text) FROM ").append(table).append(" x WHERE ").append(table.equals("ticket_type")?"id":"ticket_id").append("='").append(f.ticket()).append("'::uuid)");
  }
  for(String table:List.of("stock_hold_intent","stock_hold_progress","stock_hold_acceptance","stock_hold_decision","stock_hold_ack"))sql.append(",(SELECT to_jsonb(x) FROM ").append(table).append(" x WHERE ").append(table.equals("stock_hold_intent")?"id":"intent_id").append("='").append(f.intent()).append("'::uuid)");
  sql.append(",(SELECT to_jsonb(x) FROM purchase_request x WHERE id='").append(f.purchase()).append("'::uuid),(SELECT to_jsonb(x) FROM stock_rebuild_command x WHERE ticket_id='").append(f.ticket()).append("'::uuid AND generation=1))::text");
  return CommitCursorExperiment.sha(db.queryForObject(sql.toString(),String.class));
 }
 public static void main(String[] args)throws Exception{
  require(args.length==1,"Private original fixture path required");var path=privatePath(args[0]);require(!Files.exists(path),"Existing original preserved");
  require(System.getenv("COMPOSE_PROJECT_NAME")!=null&&System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-return-upgrade-[a-z0-9-]{1,24}"),"Own upgrade project required");
  try(var context=SpringApplication.run(TicketApplication.class,settings())){
   var db=context.getBean(JdbcTemplate.class);require(db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class)==13,"Historical V13 required");
   var fixtures=new CommitCursorFixture(context);var f=fixtures.accepted(false);require(context.getBean(PurchaseService.class).process(f.event()),"Original worker");require("COMMITTED".equals(context.getBean(StockSettlementService.class).reconcile(f.intent()).resolution()),"Original acknowledgement");
   UUID order=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,f.intent());context.getBean(OrderService.class).cancel(f.owner(),order,new Cancel(UUID.randomUUID(),1));
   var saved=new Saved(f,order,UUID.randomUUID(),ledgerSha(f,fixtures,context.getBean(StringRedisTemplate.class)),domainSha(db,f));
   // java.security.Principal.getName() is a derived serializer property, not a record field.
   // Keep strict deserialization and persist only the original verified identity fields.
   var json=context.getBean(ObjectMapper.class);var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(saved);
   ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("fixture").path("owner")).remove("name");
   json.writeValue(path.toFile(),tree);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
   System.out.println("Historical V13 seed complete: original accepted COMMIT and formal cancellation; no private data printed.");
  }
 }
}

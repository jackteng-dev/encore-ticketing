package dev.jackteng.encore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.inventory.*;
import dev.jackteng.encore.inventory.MultiCursorExperiment.Command;
import dev.jackteng.encore.orders.*;
import dev.jackteng.encore.orders.OrderModels.*;
import dev.jackteng.encore.orders.PaymentModels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Fixed-head C3 proof using final JAR domain code + explicitly added TEST classpath. */
public final class StockMultiCursorExperimentRunner {
 public record Saved(List<UUID> requests,List<String> digests,String ledgerSha,String domainSha) {}
 private static void require(boolean yes,String message){if(!yes)throw new IllegalStateException(message);}
 private static Path privatePath(String path){var p=Path.of(path).toAbsolutePath().normalize();require(p.startsWith(Path.of("work").toAbsolutePath().normalize()),"Private work only");return p;}
 private static String ledgerSha(MultiCursorExperiment experiment,StringRedisTemplate redis,Command c)throws Exception{return CommitCursorExperiment.sha(new ObjectMapper().writeValueAsString(new TreeMap<>(redis.opsForHash().entries(experiment.key(c.ticket(),c.generation()).replace(":multi-experiment","")))));}
 private static String domainSha(JdbcTemplate db,Command c){
  StringBuilder sql=new StringBuilder("SELECT jsonb_build_array(");
  var scopes=new LinkedHashMap<String,String>();
  for(String table:List.of("ticket_type","stock_projection","stock_revision","stock_revision_context","stock_revision_origin","stock_origin_audit","stock_hold_intent","purchase_request","ticket_order","order_inventory_event","payment_inventory_event"))scopes.put(table,table.equals("ticket_type")?"id":"ticket_id");
  for(var e:scopes.entrySet()){if(sql.charAt(sql.length()-1)!='(')sql.append(',');sql.append("(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM ").append(e.getKey()).append(" x WHERE ").append(e.getValue()).append("='").append(c.ticket()).append("'::uuid)");}
  for(String table:List.of("stock_hold_progress","stock_hold_acceptance","stock_hold_decision","stock_hold_ack"))sql.append(",(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM ").append(table).append(" x WHERE intent_id IN(SELECT id FROM stock_hold_intent WHERE ticket_id='").append(c.ticket()).append("'::uuid))");
  sql.append(",(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM payment_event x WHERE order_id IN(SELECT id FROM ticket_order WHERE ticket_id='").append(c.ticket()).append("'::uuid)))::text");
  return CommitCursorExperiment.sha(db.queryForObject(sql.toString(),String.class));
 }
 public static void main(String[] args)throws Exception{
  require(args.length==3&&List.of("prepare","offline","verify").contains(args[0]),"Three phases required");
  require(System.getenv("COMPOSE_PROJECT_NAME")!=null&&System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-multi-[a-z0-9-]{1,32}"),"Own C3 project required");
  var fixture=privatePath(args[1]);var output=privatePath(args[2]);
  try(var context=SpringApplication.run(TicketApplication.class,"--server.port=0","--ticket.orders.enabled=true","--ticket.purchases.enabled=true","--ticket.mock-payments.enabled=true","--ticket.delivery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false","--ticket.expiry.enabled=false","--ticket.stock-recovery.enabled=false","--ticket.stock-maintenance.enabled=false","--ticket.stock-review.enabled=false","--ticket.pre-debit.enabled=false","--ticket.admission.enabled=false","--ticket.demo-bootstrap=false")){
   var db=context.getBean(JdbcTemplate.class);var redis=context.getBean(StringRedisTemplate.class);var json=context.getBean(ObjectMapper.class);
   var exp=new MultiCursorExperiment(db,redis,json,context.getBean(AdmissionSettings.class).namespace(),context.getBean(PlatformTransactionManager.class));exp.createSchema();
   var checks=new ArrayList<String>();Saved saved;List<Command> commands;
   if(args[0].equals("prepare")){
    require(!Files.exists(fixture),"Original fixture preserved");var fixtures=new CommitCursorFixture(context);var f=fixtures.accepted(false,exp::seed);
    var holds=context.getBean(StockHoldService.class);var purchases=context.getBean(PurchaseService.class);var settlement=context.getBean(StockSettlementService.class);
    var i=holds.prepare(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));require("HELD".equals(holds.apply(i.id()).state()),"Second original hold");
    var p=purchases.acceptHold(f.owner(),i.id());var e=db.queryForObject("SELECT id FROM purchase_outbox WHERE purchase_id=?",UUID.class,p.id());
    require(purchases.process(f.event())&&purchases.process(e),"Original workers");require("COMMITTED".equals(settlement.reconcile(f.intent()).resolution())&&"COMMITTED".equals(settlement.reconcile(i.id()).resolution()),"Original ack");
    var orders=context.getBean(OrderService.class);var payments=context.getBean(PaymentService.class);
    UUID first=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,f.intent()),second=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,i.id());
    orders.cancel(f.owner(),first,new Cancel(UUID.randomUUID(),1));var o=orders.detail(f.owner(),second);var pay=payments.start(f.owner(),second,new Pay(UUID.randomUUID(),o.version(),o.totalTwd(),"SUCCESS"));payments.reconcile(f.owner(),second,pay.payment().id(),new Reconcile(UUID.randomUUID()));
    commands=new ArrayList<>();for(long r=1;r<5;r++)commands.add(exp.register(UUID.randomUUID(),f.ticket(),r));
    checks.add("four contiguous original V13 origins: two accepted HOLDs, formal RELEASE, successful PAYMENT");
    var c=commands.get(1);saved=new Saved(commands.stream().map(Command::request).toList(),commands.stream().map(Command::digest).toList(),ledgerSha(exp,redis,c),domainSha(db,c));
    require("APPLIED".equals(exp.consume(commands.getFirst().request(),commands.getFirst().digest())),"First checkpoint");checks.add("first COMMIT consumed without re-debiting original ledger");
    db.execute("ALTER TABLE stock_multi_experiment.audit ADD CONSTRAINT multi_runtime_fault CHECK(request_id<>'"+c.request()+"'::uuid OR phase<>'APPLIED') NOT VALID");boolean failed=false;
    try{exp.consume(c.request(),c.digest());}catch(org.springframework.dao.DataAccessException error){failed=true;}finally{db.execute("ALTER TABLE stock_multi_experiment.audit DROP CONSTRAINT multi_runtime_fault");}
    require(failed,"Actual PG audit rollback");checks.add("second Redis checkpoint applied before real PG audit CHECK rollback");
    require(db.queryForObject("SELECT revision FROM stock_multi_experiment.cursor WHERE ticket_id=?",Long.class,c.ticket())==2L,"PG retains previous cursor");require("3".equals(redis.opsForHash().get(exp.key(c.ticket(),1),"_revision")),"Redis next cursor");checks.add("original predecessor PG checkpoint and new Redis marker retained");
    json.writeValue(fixture.toFile(),saved);Files.setPosixFilePermissions(fixture,PosixFilePermissions.fromString("rw-------"));
   }else{
    saved=json.readValue(fixture.toFile(),Saved.class);commands=saved.requests().stream().map(exp::command).toList();
    for(int n=0;n<commands.size();n++)require(commands.get(n).digest().equals(saved.digests().get(n)),"Original command body/digest");checks.add("all original UUIDs and digests survived process restart");var c=commands.get(1);
    if(args[0].equals("offline")){
     require("UNKNOWN".equals(exp.consume(c.request(),c.digest())),"Real outage must stay unknown");checks.add("actual Redis stop returns UNKNOWN without reseed or new command");
     require("PG_REPLAY".equals(exp.consume(commands.getFirst().request(),commands.getFirst().digest())),"Historical PG-only replay");checks.add("previous committed command remains PG-only readable during outage");
     require(db.queryForObject("SELECT revision FROM stock_multi_experiment.cursor WHERE ticket_id=?",Long.class,c.ticket())==2L,"No PG change");checks.add("UNKNOWN leaves unacknowledged second command and original PG cursor intact");
    }else{
     require("REDIS_REPLAY".equals(exp.consume(c.request(),c.digest())),"Original marker recovery");checks.add("same AOF restart and original marker complete missing PG checkpoint");
     require("APPLIED".equals(exp.consume(commands.get(2).request(),commands.get(2).digest())),"Formal release");checks.add("formal RELEASE advances separate evidence available without reversing original COMMIT receipt");
     require("APPLIED".equals(exp.consume(commands.get(3).request(),commands.get(3).digest())),"Formal payment");checks.add("PAYMENT held-to-sold conversion preserves evidence available");
     for(var command:commands)require("PG_REPLAY".equals(exp.consume(command.request(),command.digest())),"Historical retry");checks.add("all old original retries retain final cursor and one ack per command");
     require("5".equals(redis.opsForHash().get(exp.key(c.ticket(),1),"_revision"))&&"3".equals(redis.opsForHash().get(exp.key(c.ticket(),1),"_available")),"Final virtual snapshot");checks.add("PG/Redis evidence cursor 5 and virtual available 3 match formal PG snapshot");
     require(saved.ledgerSha().equals(ledgerSha(exp,redis,c)),"Original ledger raw values changed");checks.add("whole original FROZEN ledger and both raw COMMITTED receipts unchanged");
     require(db.queryForObject("SELECT count(*) FROM stock_multi_experiment.ack WHERE request_id=ANY(?)",Integer.class,(Object)saved.requests().toArray(UUID[]::new))==4,"Exactly four acks");checks.add("four immutable command acknowledgements with no duplicate consumption");
    }
   }
   var c=commands.getFirst();require(saved.domainSha().equals(domainSha(db,c)),"Production domain changed");checks.add("original PG domain, journal, V13 context/origin/audit and decisions unchanged");
   require("DIRTY".equals(context.getBean(StockProjectionService.class).inspect(c.ticket()).state()),"No READY publication");checks.add("production remains DIRTY; evidence never enables new holds");
   json.writeValue(output.toFile(),Map.of("phase",args[0],"passed",checks.size(),"checks",checks,"testClasspathOnly",true,"sourceSchemaVersion",db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class)));Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
   System.out.println("C3 phase "+args[0]+": "+checks.size()+" checks passed; no private data printed.");
  }
 }
}

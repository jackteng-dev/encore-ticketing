package dev.elpsy.ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.elpsy.ticket.admission.AdmissionSettings;
import dev.elpsy.ticket.inventory.*;
import dev.elpsy.ticket.orders.OrderModels.Place;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import static dev.elpsy.ticket.StockReturnBaselineSeed.*;
/** TEST ONLY: independent storage proof after actual production HTTP/browser review. */
public final class StockReturnResolutionRuntimeVerify {
 public static void main(String[] args)throws Exception{
  require(args.length==2,"Private input/output required");require(System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-resolution-runtime-[a-z0-9-]{1,24}"),"Own runtime required");
  try(var context=SpringApplication.run(TicketApplication.class,settings())){
   var json=context.getBean(ObjectMapper.class);var db=context.getBean(JdbcTemplate.class);var redis=context.getBean(StringRedisTemplate.class);var ledger=RedisTicketLedger.epochs(redis,json,context.getBean(AdmissionSettings.class).namespace());var root=json.readTree(privatePath(args[0]).toFile());var checks=new ArrayList<String>();
   require(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)==16,"Actual V16 required");CommitCursorFixture.Fixture first=null;
   for(var entry:root.path("fixtures").properties()){
    var name=entry.getKey();var item=entry.getValue();var f=json.treeToValue(item.path("fixture"),CommitCursorFixture.Fixture.class);var request=UUID.fromString(item.path("requestId").asText());
    if(!name.equals("stale"))require(item.path("originalDomainSha").asText().equals(domainSha(db,f)),"Review changed original business rows");
    require(item.path("oldLedgerSha").asText().equals(CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(f.ticket(),1)))))),"Old ledger changed");
    require(item.path("replacementLedgerSha").asText().equals(CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(f.ticket(),2)))))),"Review changed replacement ledger");checks.add(name+": PG-only review preserves both complete Redis hashes; business rows preserved except explicit stale source mutation");
    require(db.queryForObject("SELECT count(*) FROM stock_return_staff_progress WHERE request_id=? AND state='BLOCKED' AND version=2",Integer.class,request)==1,"Original phase changed");require(db.queryForObject("SELECT count(*) FROM stock_return_staff_audit WHERE request_id=?",Integer.class,request)==2,"Original audit changed");require(db.queryForObject("SELECT count(*) FROM stock_return_ack WHERE request_id=?",Integer.class,request)==0,"Review cannot create original return ack");
    int expected=Set.of("stale","active").contains(name)?0:1;require(db.queryForObject("SELECT count(*) FROM stock_return_resolution WHERE request_id=?",Integer.class,request)==expected,"Resolution count");require(db.queryForObject("SELECT count(*) FROM stock_return_resolution_audit WHERE request_id=?",Integer.class,request)==expected,"Resolution audit count");checks.add(name+": original BLOCKED remains immutable and exact resolution/audit count");if(name.equals("lost"))first=f;
   }
   require(first!=null,"Original customer required");var holds=context.getBean(StockHoldService.class);var intent=holds.prepare(first.owner(),new Place(UUID.randomUUID(),first.ticket(),1,1,1200));require("HELD".equals(holds.apply(intent.id()).state()),"Actual subsequent HOLD");var snap=ledger.snapshot(first.ticket(),2).snapshot();require(snap.available()==2&&snap.held()==1,"Exact spendable balance");checks.add("after all unchanged-hash checks, actual HOLD spends one from existing baseline three; available two held one");
   require(root.path("fixtures").path("lost").path("oldLedgerSha").asText().equals(CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(first.ticket(),1)))))),"Old ledger changed by HOLD");checks.add("subsequent HOLD preserves original FROZEN/COMMITTED ledger");var out=privatePath(args[1]);require(!Files.exists(out),"Prior proof preserved");json.writeValue(out.toFile(),Map.of("passed",checks.size(),"checks",checks,"testOnlyRunner",true));Files.setPosixFilePermissions(out,PosixFilePermissions.fromString("rw-------"));System.out.println("Production storage verification passed: "+checks.size());
  }
 }
}

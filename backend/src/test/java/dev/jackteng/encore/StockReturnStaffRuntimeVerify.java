package dev.jackteng.encore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.inventory.*;
import dev.jackteng.encore.orders.OrderModels.Place;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import static dev.jackteng.encore.StockReturnBaselineSeed.*;
/** TEST ONLY: independently verify browser-produced business effects and an actual subsequent HOLD. */
public final class StockReturnStaffRuntimeVerify {
 public static void main(String[] args)throws Exception{
  require(args.length==2,"Private input/output required");require(System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-return-staff-runtime-[a-z0-9-]{1,24}"),"Own runtime required");
  try(var context=SpringApplication.run(TicketApplication.class,settings())){
   var json=context.getBean(ObjectMapper.class);var db=context.getBean(JdbcTemplate.class);var redis=context.getBean(StringRedisTemplate.class);var ledger=RedisTicketLedger.epochs(redis,json,context.getBean(AdmissionSettings.class).namespace());var root=json.readTree(privatePath(args[0]).toFile());var checks=new ArrayList<String>();
   require(db.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)==15,"Actual V15 required");
   CommitCursorFixture.Fixture first=null;
   for(var entry:root.path("fixtures").properties()){
    var item=entry.getValue();var f=json.treeToValue(item.path("fixture"),CommitCursorFixture.Fixture.class);
    require(item.path("originalDomainSha").asText().equals(domainSha(db,f)),"Original business rows changed");
    require(item.path("originalLedgerSha").asText().equals(CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(f.ticket(),1)))))),"Old ledger changed");checks.add(entry.getKey()+": original business rows and full old ledger unchanged");
    if(entry.getKey().equals("stale")){require(db.queryForObject("SELECT count(*) FROM stock_return_ack a JOIN stock_return_command c USING(request_id) WHERE c.ticket_id=?",Integer.class,f.ticket())==0,"Blocked cannot return stock");checks.add("newer source stays BLOCKED without domain return ack");continue;}
    var gate=context.getBean(StockProjectionService.class).inspect(f.ticket());var snapshot=ledger.snapshot(f.ticket(),2).snapshot();require(gate.generation()==2&&gate.state().equals("READY")&&snapshot.phase()==RedisTicketLedger.Phase.OPEN&&snapshot.baseline()==4&&snapshot.available()==4,"Exact new READY epoch");require(db.queryForObject("SELECT count(*) FROM stock_return_staff_progress p JOIN stock_return_staff_command c USING(request_id) JOIN stock_return_ack a USING(request_id) WHERE c.ticket_id=? AND p.state='APPLIED'",Integer.class,f.ticket())==1,"One staff/domain effect");checks.add(entry.getKey()+": one actor-owned APPLIED and exact READY baseline four");if(entry.getKey().equals("lost"))first=f;
   }
   require(first!=null,"Original customer required");var holds=context.getBean(StockHoldService.class);var intent=holds.prepare(first.owner(),new Place(UUID.randomUUID(),first.ticket(),1,1,1200));require("HELD".equals(holds.apply(intent.id()).state()),"Actual new HOLD");var snap=ledger.snapshot(first.ticket(),2).snapshot();require(snap.available()==3&&snap.held()==1,"Exact spendable balance");checks.add("original verified customer really holds one ticket in HTTP-created new epoch; available three held one");
   var old=root.path("fixtures").path("lost");require(old.path("originalLedgerSha").asText().equals(CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(first.ticket(),1)))))),"Old ledger changed by new HOLD");checks.add("new HOLD preserves original FROZEN and COMMITTED ledger");
   var out=privatePath(args[1]);require(!Files.exists(out),"Prior proof preserved");json.writeValue(out.toFile(),Map.of("passed",checks.size(),"checks",checks,"testOnlyRunner",true));Files.setPosixFilePermissions(out,PosixFilePermissions.fromString("rw-------"));System.out.println("Post-browser core verification passed: "+checks.size()+" checks");
  }
 }
}

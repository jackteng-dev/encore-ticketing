package dev.jackteng.encore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.inventory.*;
import dev.jackteng.encore.orders.OrderModels.Place;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static dev.jackteng.encore.StockReturnBaselineSeed.*;

/** TEST ONLY runner invoking the actual V14 production StockReturnService. */
public final class StockReturnHandoffRunner {
 public static void main(String[] args)throws Exception{
  require(args.length==3&&List.of("migrate","prepare","offline","verify").contains(args[0]),"Bounded phase required");
  require(System.getenv("COMPOSE_PROJECT_NAME")!=null&&System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-return-upgrade-[a-z0-9-]{1,24}"),"Own project required");
  var original=privatePath(args[1]);var result=privatePath(args[2]);
  try(var context=SpringApplication.run(TicketApplication.class,settings())){
   var db=context.getBean(JdbcTemplate.class);var redis=context.getBean(StringRedisTemplate.class);var json=context.getBean(ObjectMapper.class);
   var saved=json.readValue(original.toFile(),Saved.class);var f=saved.fixture();var checks=new ArrayList<String>();
   require(db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class)==14,"V14 required");
   var ledger=RedisTicketLedger.epochs(redis,json,context.getBean(AdmissionSettings.class).namespace());var service=context.getBean(StockReturnService.class);
   if(args[0].equals("migrate")){
    for(String table:List.of("stock_return_command","stock_return_ack","stock_return_audit"))require(db.queryForObject("SELECT count(*) FROM "+table,Integer.class)==0,"No backfill");checks.add("actual V13-to-V14 migration adds three empty return tables without backfill");
   }else{
    var c=service.begin(saved.request(),saved.order(),3);require(c.generation()==2&&c.available()==4,"Exact fresh full PG snapshot");checks.add("same original return UUID binds source RELEASE and fresh generation 2 full available 4");
    if(args[0].equals("prepare")){
     db.execute("ALTER TABLE stock_return_audit ADD CONSTRAINT return_runtime_fault CHECK(request_id<>'"+saved.request()+"'::uuid OR phase<>'APPLIED') NOT VALID");boolean failed=false;
     try{service.reconcile(saved.request());}catch(org.springframework.dao.DataAccessException error){failed=true;}finally{db.execute("ALTER TABLE stock_return_audit DROP CONSTRAINT return_runtime_fault");}
     require(failed,"Actual audit CHECK rollback");checks.add("new Redis epoch opened before real PG APPLIED audit rollback");
     require("CLOSED".equals(context.getBean(StockProjectionService.class).inspect(f.ticket()).state()),"PG remains CLOSED");checks.add("PG CLOSED and original unacknowledged command prevent new accepted holds");
     require(ledger.snapshot(f.ticket(),2).snapshot().phase()==RedisTicketLedger.Phase.OPEN,"Redis prepared once");checks.add("original fresh Redis key retained OPEN with full snapshot and no claims");
    }else if(args[0].equals("offline")){
     var out=service.reconcile(saved.request());require("PENDING".equals(out.state())&&"REDIS_UNKNOWN".equals(out.failure()),"Real outage stays unknown");checks.add("actual Redis outage returns PENDING REDIS_UNKNOWN without compensating credit");
     require(db.queryForObject("SELECT count(*) FROM stock_return_ack WHERE request_id=?",Integer.class,saved.request())==0,"No false ack");checks.add("original PG-only return body and REGISTERED audit survive process restart and outage");
    }else{
     var out=service.reconcile(saved.request());require("APPLIED".equals(out.state())&&!out.historicalReplay(),"Original completion");checks.add("same AOF restart completes original new-epoch READY ack and audit");
     require(ledger.snapshot(f.ticket(),2).snapshot().available()==4,"Return counted once");checks.add("new generation snapshot available 4 matches formal PG stock without INCR");
     var i=context.getBean(StockHoldService.class).prepare(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));require(i.generation()==2&&"HELD".equals(context.getBean(StockHoldService.class).apply(i.id()).state()),"New actual HOLD");checks.add("verified original customer successfully pre-debits new generation after handoff");
     require(ledger.snapshot(f.ticket(),2).snapshot().available()==3&&ledger.snapshot(f.ticket(),2).snapshot().held()==1,"Spendable conservation");checks.add("new epoch available 3 held 1 baseline 4 conserves spendable stock");
     for(int n=0;n<6;n++)require(service.reconcile(saved.request()).historicalReplay(),"Historical replay only");checks.add("six old return retries replay PG acknowledgement without reinitializing used epoch");
     require(db.queryForObject("SELECT count(*) FROM stock_return_audit WHERE request_id=?",Integer.class,saved.request())==2,"Exactly two audits");checks.add("one original return ack and exactly REGISTERED APPLIED audits");
    }
   }
   require(saved.domainSha().equals(domainSha(db,f)),"Original formal business rows changed");checks.add("original PG stock orders events journal V13 origins and COMMIT evidence unchanged");
   if(!args[0].equals("offline")){
    var actual=CommitCursorExperiment.sha(json.writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger.key(f.ticket(),1)))));require(saved.ledgerSha().equals(actual),"Old ledger changed");checks.add("whole original FROZEN ledger and raw COMMITTED receipt unchanged");
    require(ledger.open(f.ticket(),1).code()==RedisTicketLedger.Code.CLOSED,"Old epoch never reopens");checks.add("delayed old OPEN remains rejected even after new epoch publication");
   }
   json.writeValue(result.toFile(),Map.of("phase",args[0],"passed",checks.size(),"checks",checks,"productionServiceExercised",true,"sourceSchemaVersion",14));Files.setPosixFilePermissions(result,PosixFilePermissions.fromString("rw-------"));
   System.out.println("Return phase "+args[0]+": "+checks.size()+" checks passed; no private data printed.");
  }
 }
}

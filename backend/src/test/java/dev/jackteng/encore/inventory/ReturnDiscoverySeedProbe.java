package dev.jackteng.encore.inventory;
import dev.jackteng.encore.TicketApplication;
import dev.jackteng.encore.catalog.*;
import dev.jackteng.encore.catalog.Models.*;
import dev.jackteng.encore.customer.CustomerService;
import dev.jackteng.encore.orders.*;
import dev.jackteng.encore.orders.OrderModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test classpath only: real V22 fixtures and later formal cancellation, with no background execution. */
public final class ReturnDiscoverySeedProbe {
 public static void main(String[] args)throws Exception {
  if(args.length!=2||!Set.of("seed","release").contains(args[0]))throw new IllegalArgumentException("Fixture phase required");
  String namespace=System.getenv("TICKET_REDIS_NAMESPACE");
  if(namespace==null||!namespace.startsWith("encore-demo-discovery-"))throw new IllegalArgumentException("Own discovery fixture namespace required");
  var app=new SpringApplication(TicketApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
  try(var context=app.run("--spring.profiles.active=m5d-probe","--spring.main.web-application-type=none",
   "--ticket.demo-bootstrap="+args[0].equals("seed"),"--ticket.expiry.enabled=false",
   "--ticket.stock-recovery.enabled=false","--ticket.stock-return-recovery.enabled=false",
   "--ticket.stock-return-discovery.enabled=false","--ticket.delivery.publisher-enabled=false","--ticket.delivery.consumer-enabled=false")) {
   var db=context.getBean(JdbcTemplate.class);if(!"ticket_demo".equals(db.queryForObject("SELECT current_database()",String.class)))throw new IllegalStateException("Disposable database required");
   var json=context.getBean(ObjectMapper.class);var path=Path.of(args[1]);
   if(args[0].equals("seed")) {
    if(path.toFile().exists()||db.queryForObject("SELECT max(version::int) FROM flyway_schema_history",Integer.class)!=22)throw new IllegalStateException("New V22 fixture required");
    var catalog=context.getBean(CatalogService.class);var customers=context.getBean(CustomerService.class);
    var projections=context.getBean(StockProjectionService.class);var holds=context.getBean(StockHoldService.class);
    var purchases=context.getBean(PurchaseService.class);var settlement=context.getBean(StockSettlementService.class);
    var orders=context.getBean(OrderService.class);String manager=System.getenv("TICKET_STAFF_USERNAME");var all=new LinkedHashMap<String,Object>();
    for(String name:List.of("history","future","stopped")) {
     var now=OffsetDateTime.now();var event=catalog.createEvent(manager,new EventInput(UUID.randomUUID(),0L,"discovery-"+UUID.randomUUID(),"合成自動返還","不收款","隔離"));
     var session=catalog.createSession(manager,event.id(),new SessionInput(UUID.randomUUID(),0L,"合成",now.plusDays(2),now.minusHours(1),now.plusHours(2)));
     var ticket=catalog.createTicket(manager,session.id(),new TicketInput(UUID.randomUUID(),0L,"一般票",1200,4,4));catalog.transition(manager,event.id(),new Transition(UUID.randomUUID(),1L),true);
     var rebuild=projections.begin(ticket.id(),UUID.randomUUID(),1);if(!projections.complete(rebuild.requestId()).state().equals("READY"))throw new IllegalStateException("Seed projection not ready");
     var account=customers.register("ds-"+UUID.randomUUID().toString().substring(0,30),"合成顧客",UUID.randomUUID().toString());var owner=customers.authenticate(account.token()).orElseThrow();
     var intent=holds.prepare(owner,new Place(UUID.randomUUID(),ticket.id(),1,1,1200));if(!holds.apply(intent.id()).state().equals("HELD"))throw new IllegalStateException("Seed hold missing");
     var purchase=purchases.acceptHold(owner,intent.id());var outbox=db.queryForObject("SELECT id FROM purchase_outbox WHERE purchase_id=?",UUID.class,purchase.id());purchases.process(outbox);
     if(!settlement.reconcile(intent.id()).resolution().equals("COMMITTED"))throw new IllegalStateException("Seed original commit missing");
     var order=db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,intent.id());
     if(name.equals("history"))orders.cancel(owner,order,new Cancel(UUID.randomUUID(),1));
     var data=new LinkedHashMap<String,Object>();data.put("ticket",ticket.id());data.put("intent",intent.id());data.put("order",order);data.put("owner",owner);data.put("originalGeneration",1);
     all.put(name,data);
    }
    Files.writeString(path,json.writeValueAsString(all)+"\n",StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
   } else {
    var nodes=json.readTree(Files.readString(path));var orders=context.getBean(OrderService.class);
    for(String name:List.of("future","stopped")) {
     var node=nodes.get(name);var identity=node.get("owner");
     var owner=new CustomerService.Principal(UUID.fromString(identity.get("id").asText()),identity.get("handle").asText(),identity.get("sessionVersion").asLong(),identity.get("sessionHash").asText());
     orders.cancel(owner,UUID.fromString(node.get("order").asText()),new Cancel(UUID.randomUUID(),1));
    }
   }
  }
 }
}

package dev.jackteng.encore.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Internal M4D1 primitive, deliberately NOT a bean or a checkout gate yet.
 * A future durable PG coordinator must authorize identity, seed/fence generations,
 * and decide COMMIT/RELEASE from PG evidence. No timeout compensation is inferred here.
 */
public final class RedisTicketLedger {
 public enum Code { OK, MISSING, FENCED, CLOSED, EXPIRED, SOLD_OUT, FULL, CONFLICT, CORRUPT, ACTIVE_HOLDS, TERMINAL, INVALID }
 public enum Phase { CLOSED, OPEN, FROZEN }
 public enum Status { HELD, COMMITTED, RELEASED }
 public record Snapshot(long generation,Phase phase,int baseline,int available,int held,int committed,int entries,int limit) {}
 public record Receipt(long generation,UUID requestId,UUID owner,int quantity,long deadline,String fingerprint,Status status) {}
 public record Result(Code code,Snapshot snapshot,Receipt receipt,long serverNow) {}
 /** Fingerprint must bind the verified identity/session, ticket, quote and original command. */
 public record Claim(long generation,UUID requestId,UUID owner,int quantity,long deadline,String fingerprint) {
  public Claim {
   generationRange(generation);
   if(requestId==null||owner==null||quantity<1||quantity>10||deadline<1||deadline>10_000_000_000_000L||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid ledger claim");
  }
 }
 public static final class Unavailable extends RuntimeException {
  public Unavailable(){super("Redis ledger outcome unknown; reconcile the original command before retry or release");}
 }
 private final StringRedisTemplate redis;private final ObjectMapper json;private final String namespace;private final boolean epochKeys;
 private final DefaultRedisScript<String> script=new DefaultRedisScript<>();
 public RedisTicketLedger(StringRedisTemplate redis,ObjectMapper json,String namespace){
  this(redis,json,namespace,false);
 }
 private RedisTicketLedger(StringRedisTemplate redis,ObjectMapper json,String namespace,boolean epochKeys){
  if(namespace==null||!namespace.matches("encore-demo-[a-z0-9-]{1,48}"))throw new IllegalArgumentException("Invalid isolated ledger namespace");
  this.redis=redis;this.json=json;this.namespace=namespace;this.epochKeys=epochKeys;
  script.setLocation(new ClassPathResource("redis/ticket-ledger.lua"));script.setResultType(String.class);
 }
 /** M4D2 maintenance keys isolate durable PG epochs; old INIT cannot create the current epoch. */
 public static RedisTicketLedger epochs(StringRedisTemplate redis,ObjectMapper json,String namespace){return new RedisTicketLedger(redis,json,namespace,true);}
 private static void generationRange(long g){if(g<1||g>1_000_000_000L)throw new IllegalArgumentException("Generation out of range");}
 public String key(UUID ticket){if(ticket==null)throw new IllegalArgumentException("Ticket required");return namespace+":stock:{"+ticket+"}:ledger";}
 public String key(UUID ticket,long generation){generationRange(generation);return epochKeys?key(ticket)+":g:"+generation:key(ticket);}
 private Result run(UUID ticket,String... args){
  try{
   var result=json.readValue(redis.execute(script,List.of(key(ticket,Long.parseLong(args[1]))),(Object[])args),Result.class);
   if(result==null||result.code()==null||result.serverNow()<1)throw new Unavailable();return result;
  }catch(Unavailable e){throw e;}catch(Exception e){throw new Unavailable();}
 }
 /** Only on verified empty storage, after closing durable admission. Never automatic on MISSING. */
 public Result initialize(UUID ticket,long generation,int available,int receiptLimit){
  generationRange(generation);bounds(available,receiptLimit);
  return run(ticket,"INIT",""+generation,""+available,""+receiptLimit);
 }
 private static void bounds(int available,int limit){if(available<0||available>1_000_000||limit<1||limit>4096)throw new IllegalArgumentException("Snapshot bounds invalid");}
 public Result snapshot(UUID ticket,long generation){generationRange(generation);return run(ticket,"READ",""+generation);}
 public Result open(UUID ticket,long generation){generationRange(generation);return run(ticket,"OPEN",""+generation);}
 public Result close(UUID ticket,long generation){generationRange(generation);return run(ticket,"CLOSE",""+generation);}
 /** Closed/frozen + no HELD receipts only; old receipts retained, new generation exactly old+1.
  * Does not prove snapshot freshness or PG quiescence; caller must establish both durably.
  */
 public Result rotate(UUID ticket,long expectedGeneration,int available){
  if(epochKeys)throw new IllegalStateException("Epoch keys are allocated by PG, not Redis ROTATE");
  generationRange(expectedGeneration);bounds(available,1);return run(ticket,"ROTATE",""+expectedGeneration,""+available);
 }
 private Result command(UUID ticket,String mode,Claim c){return run(ticket,mode,""+c.generation(),c.requestId().toString(),c.owner().toString(),""+c.quantity(),""+c.deadline(),c.fingerprint());}
 public Result hold(UUID ticket,Claim c){return command(ticket,"HOLD",c);}
 public Result lookup(UUID ticket,Claim c){return command(ticket,"LOOKUP",c);}
 /** Only after original PG reservation commit is proven; valid even after deadline/close. */
 public Result commit(UUID ticket,Claim c){return command(ticket,"COMMIT",c);}
 /** Only after PG proves no reservation and fences a late writer. Never just because TTL elapsed. */
 public Result release(UUID ticket,Claim c){return command(ticket,"RELEASE",c);}
}

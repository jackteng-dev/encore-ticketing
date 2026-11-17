package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.catalog.SalesReportService.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
/** 員工唯讀報表（/api/admin GET 需 CATALOG_READ，唯讀員工可看）。 */
@RestController
@RequestMapping("/api/admin/reports")
public class SalesReportController {
 private final SalesReportService service;
 public SalesReportController(SalesReportService service){this.service=service;}
 @GetMapping("/events") public Page<EventSummary> events(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return service.events(page,size);}
 @GetMapping("/events/{id}") public EventReport event(@PathVariable UUID id){return service.event(id);}
}

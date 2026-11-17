package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.SeatMapService.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class SeatMapController {
 private final SeatMapService service;
 public SeatMapController(SeatMapService service){this.service=service;}
 @GetMapping("/public/events/{id}/seat-maps") public List<SeatMap> publicMaps(@PathVariable UUID id){return service.forEvent(id);}
 @GetMapping("/admin/sessions/{id}/seat-map") public SeatMap adminMap(@PathVariable UUID id){return service.forSession(id);}
 @PutMapping("/admin/sessions/{id}/seat-map") public SeatMap update(Principal p,@PathVariable UUID id,@Valid @RequestBody SeatMapInput in){return service.update(p.getName(),id,in);}
}

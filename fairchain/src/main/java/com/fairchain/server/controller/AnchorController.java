package com.fairchain.server.controller;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.server.entity.AnchorRecord;
import com.fairchain.server.service.AnchorService;
import com.fairchain.server.repository.ClientsRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/anchors") public class AnchorController {
 private final AnchorService service;private final ClientsRepository clients;
 public AnchorController(AnchorService service,ClientsRepository clients){this.service=service;this.clients=clients;}
 private String client(String key){return clients.findAll().stream().filter(c->key!=null&&key.equals(c.apiKey)).map(c->c.clientServerId).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid API key"));}
 @PostMapping public AnchorRecord record(@RequestHeader("X-API-Key") String key,@RequestBody RecordRequestDto dto){return service.record(dto,client(key));}
 @PostMapping("/verify") public boolean verify(@RequestHeader("X-API-Key") String key,@RequestBody com.fairchain.nodeapi.dto.BlockVerifyDto dto){return service.verifyTicket(client(key),dto.recordKey(),dto.version(),dto.recordHash()).valid();}
 @PostMapping("/tickets/verify")
 public com.fairchain.server.service.TicketVerificationResult verifyTicket(
  @RequestHeader("X-API-Key") String key,
  @RequestBody com.fairchain.nodeapi.dto.BlockVerifyDto dto) {
  return service.verifyTicket(client(key),dto.recordKey(),dto.version(),dto.recordHash());
 }
 @PostMapping("/tickets/{recordKey}/reconcile")
 public com.fairchain.server.service.TicketVerificationResult reconcile(
  @RequestHeader("X-API-Key") String key,@PathVariable String recordKey){
  return service.reconcilePending(client(key),recordKey);
 }
 @GetMapping("/tickets/{recordKey}/status")
 public com.fairchain.server.service.TicketLifecycleResult ticketStatus(
   @RequestHeader("X-API-Key") String key,@PathVariable String recordKey) {
  return service.ticketStatus(client(key),recordKey);
 }
}

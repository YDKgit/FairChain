package com.fairchain.node.service;

import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies that 2/3 PREPARE replies do not lead to an unsafe partial COMMIT in strict demo mode. */
class NodePrepareQuorumTest {
 @Test void missingThirdPrepareMustNeverTriggerCommit() throws Exception {
  var first=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var second=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  AtomicInteger commits=new AtomicInteger();
  first.createContext("/node/consensus/proposals", ex -> respond(ex,200,"true"));
  second.createContext("/node/consensus/proposals", ex -> respond(ex,200,"false"));
  first.createContext("/node/consensus/blocks", ex -> {commits.incrementAndGet(); respond(ex,200,"true");});
  second.createContext("/node/consensus/blocks", ex -> {commits.incrementAndGet(); respond(ex,200,"true");});
  first.start();second.start();
  try {
   BlockRepository blocks=mock(BlockRepository.class);
   PendingBlockRepository pending=mock(PendingBlockRepository.class);
   when(blocks.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
   when(blocks.findTopByRecordKeyOrderByBlockIndexDesc(anyString())).thenReturn(Optional.empty());
   String peers="http://127.0.0.1:"+first.getAddress().getPort()+",http://127.0.0.1:"+second.getAddress().getPort();
   NodeService service=new NodeService(blocks,pending,peers,"test-secret");
   var result=service.submit(new BlockRecordDto("a".repeat(64),1L,"b".repeat(64)));
   assertFalse(result.accepted());
   assertEquals(0,commits.get(),"No commit request is permitted before all PREPARE replies");
   verify(pending).saveAndFlush(any()); // recovery data remains durable
   verify(blocks,never()).saveAndFlush(any());
  } finally {first.stop(0);second.stop(0);}
 }
 private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,String json) throws java.io.IOException {
  byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","application/json");
  exchange.sendResponseHeaders(status,bytes.length);
  try(var out=exchange.getResponseBody()){out.write(bytes);}
 }
}

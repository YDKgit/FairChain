package com.fairchain.server.service;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NodeClientTest {
 @Test void incompleteNodeConfigurationMustFailClosed() {
  var client=new NodeClient("http://localhost:8081,http://localhost:8082","secret");
  assertFalse(client.verifyQuorum(new BlockVerifyDto("a".repeat(64),1L,"b".repeat(64))));
 }
 @Test void nullVerificationMustFailClosed() {
  var client=new NodeClient("http://localhost:8081,http://localhost:8082,http://localhost:8083","secret");
  assertFalse(client.verifyQuorum(null));
 }
}

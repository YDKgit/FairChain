package com.fairchain.server.service;
import com.fairchain.nodeapi.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;
@Component public class NodeClient {
 private final List<String> nodes;private final String secret;
 private final RestClient http=RestClient.create();
 public NodeClient(@Value("${blockchain.nodes}") String nodes,@Value("${blockchain.secret}") String secret){this.nodes=Arrays.asList(nodes.split(","));this.secret=secret;}
 public BlockResultDto write(BlockRecordDto dto) {
  // A single leader coordinates the three-node proposal; do NOT submit separately to all three nodes.
  return http.post().uri(nodes.get(0).trim()+"/node/records").header("X-Node-Secret",secret).body(dto).retrieve().body(BlockResultDto.class);
 }
 public boolean verifyQuorum(BlockVerifyDto dto) {
  if (dto == null || dto.recordKey() == null || dto.version() == null || dto.recordHash() == null || nodes.size() != 3) return false;
  int ok=0;
  for(String node:nodes) try {var response=http.post().uri(node.trim()+"/node/records/verify").header("X-Node-Secret",secret).body(dto).retrieve().body(BlockVerifyResultDto.class);if(response!=null && response.valid() && dto.version().equals(response.latestVersion()) && dto.recordHash().equals(response.latestRecordHash()))ok++;}catch(Exception ignored){}
  return ok==3;
 }
 /** Require all three committed proofs to match, including block index and block hash. */
 public Optional<BlockProofDto> unanimousProof(String recordKey,Long version,String recordHash) {
  if(recordKey==null || version==null || recordHash==null || nodes.size()!=3)return Optional.empty();
  BlockProofDto consensus=null;
  for(String node:nodes) {
   try {
    var proof=http.get().uri(node.trim()+"/node/records/proof/"+recordKey)
      .header("X-Node-Secret",secret).retrieve().body(BlockProofDto.class);
    if(proof==null || !proof.valid() || proof.blockIndex()==null || proof.blockHash()==null ||
        !version.equals(proof.version()) || !recordHash.equals(proof.recordHash()))return Optional.empty();
    if(consensus!=null && !consensus.equals(proof))return Optional.empty();
    consensus=proof;
   }catch(Exception ex){return Optional.empty();}
  }
  return Optional.ofNullable(consensus);
 }
 /** Read-only audit. Never repairs chains or treats a 2/3 vote as an atomic commit. */
 public ClusterAuditResult auditCluster() {
  var observations=new ArrayList<ClusterAuditResult.NodeObservation>();
  for(String base:nodes) {
   String url=base.trim();
   try {
    var status=http.get().uri(url+"/node/ledger/status").header("X-Node-Secret",secret)
      .retrieve().body(NodeLedgerStatusDto.class);
    observations.add(new ClusterAuditResult.NodeObservation(url,status!=null,status,status==null?"EMPTY_RESPONSE":null));
   } catch(Exception ex) {
    observations.add(new ClusterAuditResult.NodeObservation(url,false,null,"UNREACHABLE"));
   }
  }
  if(observations.size()!=3 || observations.stream().anyMatch(o->!o.reachable()))
    return new ClusterAuditResult(false,"NODE_UNREACHABLE",observations);
  var first=observations.get(0).status();
  if(observations.stream().anyMatch(o->!o.status().chainValid()))
    return new ClusterAuditResult(false,"CHAIN_INVALID",observations);
  if(observations.stream().anyMatch(o->!o.status().pendingIndices().isEmpty()))
    return new ClusterAuditResult(false,"PENDING_PROPOSALS",observations);
  if(observations.stream().anyMatch(o->!Objects.equals(first.height(),o.status().height()) ||
                                       !Objects.equals(first.headHash(),o.status().headHash())))
    return new ClusterAuditResult(false,"HEAD_MISMATCH",observations);
  return new ClusterAuditResult(true,"CONSISTENT",observations);
 }

}

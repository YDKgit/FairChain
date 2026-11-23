package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.JoinChallengeDto;
import com.fairchain.nodeapi.dto.JoinProofDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 가입 전 확인 — NodeController가 구현.
 *
 * 운영자가 새 노드의 주소만 알려 주면, 리더가 그 주소에 무작위 값(nonce)을 보내고 새 노드는
 * "FairChain이 띄운 노드가 맞다"는 증명을 돌려준다: 노드 공유 비밀값으로 만든 HMAC과 자기 개인키 서명.
 *
 * 이 경로만 {@code X-Node-Secret} 헤더 없이 받는다. 아직 확인되지 않은 주소에 비밀값을 보내면 가짜 노드가 비밀값을
 * 가져갈 수 있기 때문이다 — 비밀값은 증명을 확인한 뒤에만 그 주소로 나간다.
 */
public interface NodeJoinApi {

    String PROOF = "/node/join/proof";

    @PostMapping(PROOF)
    JoinProofDto joinProof(@RequestBody JoinChallengeDto challenge);
}

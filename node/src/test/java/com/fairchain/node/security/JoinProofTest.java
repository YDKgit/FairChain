package com.fairchain.node.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 가입 전 확인: 비밀값을 아는 노드만, 그리고 자기 개인키를 가진 노드만 통과한다 */
class JoinProofTest {

    private static final String SECRET = "a-long-random-node-secret";
    private static final String URL = "http://192.168.0.14:9001";

    @TempDir
    Path dir;

    @Test
    void onlyTheSharedSecretMakesAMatchingMac() {
        byte[] payload = JoinProof.payload(JoinProof.newNonce(), "D1", "D", "leader-layer", URL, "key");
        String mac = JoinProof.mac(SECRET, payload);
        assertTrue(JoinProof.macMatches(SECRET, payload, mac));
        assertFalse(JoinProof.macMatches("another-secret", payload, mac));
        assertFalse(JoinProof.macMatches(SECRET, payload, null));
        assertFalse(JoinProof.macMatches(SECRET, payload, ""));
    }

    @Test
    void aProofCannotBeReusedForAnotherNonceOrAnotherNode() {
        String nonce = JoinProof.newNonce();
        String mac = JoinProof.mac(SECRET, JoinProof.payload(nonce, "D1", "D", "leader-layer", URL, "key"));
        assertFalse(JoinProof.macMatches(SECRET, JoinProof.payload(JoinProof.newNonce(), "D1", "D", "leader-layer", URL, "key"), mac));
        assertFalse(JoinProof.macMatches(SECRET, JoinProof.payload(nonce, "D1", "D", "leader-layer", "http://10.0.0.9:9001", "key"), mac));
        assertFalse(JoinProof.macMatches(SECRET, JoinProof.payload(nonce, "D1", "D", "leader-layer", URL, "other-key"), mac));
        // 필드 경계가 섞이지 않는다: ("D1", "D") 와 ("D", "1D")
        assertFalse(Arrays.equals(JoinProof.payload(nonce, "D1", "D", "r", URL, "k"), JoinProof.payload(nonce, "D", "1D", "r", URL, "k")));
    }

    @Test
    void theSignatureShowsWhoHoldsThePrivateKey() {
        NodeKeys d1 = new NodeKeys("D1", dir.resolve("D1.key"));
        NodeKeys other = new NodeKeys("X1", dir.resolve("X1.key"));
        byte[] payload = JoinProof.payload(JoinProof.newNonce(), "D1", "D", "leader-layer", URL, d1.publicKey());
        assertTrue(NodeKeys.verifyBytes(d1.publicKey(), payload, d1.signBytes(payload)));
        assertFalse(NodeKeys.verifyBytes(d1.publicKey(), payload, other.signBytes(payload)), "남의 공개키를 자기 것이라 할 수 없다");
        assertFalse(NodeKeys.verifyBytes(d1.publicKey(), payload, "not-base64!"));
        assertFalse(NodeKeys.verifyBytes("not-a-key", payload, d1.signBytes(payload)));
    }

    @Test
    void noncesAreRandomAndChecked() {
        assertNotEquals(JoinProof.newNonce(), JoinProof.newNonce());
        assertTrue(JoinProof.validNonce(JoinProof.newNonce()));
        assertFalse(JoinProof.validNonce(null));
        assertFalse(JoinProof.validNonce("short"));
        assertFalse(JoinProof.validNonce("x".repeat(129)));
        assertFalse(JoinProof.validNonce("has spaces and | bars 123456"));
    }

    @Test
    void addressesAreComparedInOneForm() {
        assertEquals(URL, JoinProof.normalizeUrl(" HTTP://192.168.0.14:9001/ "));
        assertEquals("http://node-d.example:9001", JoinProof.normalizeUrl("http://Node-D.example:9001"));
        assertEquals("https://nodes.example/d1", JoinProof.normalizeUrl("https://nodes.example/d1/"));
        assertNull(JoinProof.normalizeUrl("192.168.0.14:9001"), "스킴이 없으면 주소가 아니다");
        assertNull(JoinProof.normalizeUrl("ftp://192.168.0.14"));
        assertNull(JoinProof.normalizeUrl("http://user:pw@192.168.0.14:9001"));
        assertNull(JoinProof.normalizeUrl("http://192.168.0.14:9001/?x=1"));
        assertNull(JoinProof.normalizeUrl(""));
        assertNull(JoinProof.normalizeUrl(null));
    }
}

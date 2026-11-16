package com.fairchain.node.security;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeKeysTest {

    @TempDir
    Path dir;

    private static final String H = "ab".repeat(32);

    private static BlockProposalDto block(String blockHash) {
        return new BlockProposalDto(1L, "0".repeat(64), "b".repeat(64), 1L, "c".repeat(64), 1000L, blockHash);
    }

    @Test
    void keyFileIsCreatedOnceAndReusedAfterRestart() throws Exception {
        Path file = dir.resolve("data/node1.key");
        NodeKeys first = new NodeKeys("A1", file);
        assertTrue(Files.exists(file));
        NodeKeys again = new NodeKeys("A1", file);
        assertEquals(first.publicKey(), again.publicKey());
        assertNotNull(NodeKeys.parsePublicKey(first.publicKey()));
        assertTrue(again.signedByMe(first.sign(block(H))), "재시작 전에 한 서명도 확인된다");
    }

    @Test
    void signatureCoversTheBlockHashAndTheSigner() {
        NodeKeys a = new NodeKeys("A1", dir.resolve("a.key"));
        NodeKeys b = new NodeKeys("B1", dir.resolve("b.key"));
        BlockProposalDto signed = a.sign(block(H));
        assertEquals("A1", signed.signerId());
        assertTrue(NodeKeys.verify(a.publicKey(), signed));
        assertTrue(a.signedByMe(signed));

        assertFalse(NodeKeys.verify(b.publicKey(), signed), "다른 노드의 키로는 맞지 않는다");
        assertFalse(b.signedByMe(signed));
        BlockProposalDto otherHash = new BlockProposalDto(1L, signed.prevBlockHash(), signed.recordKey(), 1L,
                signed.recordHash(), 1000L, "cd".repeat(32), "A1", signed.signature());
        assertFalse(NodeKeys.verify(a.publicKey(), otherHash), "블록 해시를 바꾸면 서명이 맞지 않는다");
        assertFalse(a.signedByMe(block(H)), "서명 없는 블록");
        assertFalse(NodeKeys.verify("not-a-key", signed));
        assertNull(NodeKeys.parsePublicKey("not-a-key"));
        assertNotEquals(a.publicKey(), b.publicKey());
    }

    @Test
    void keyFileOfAnotherNodeIsRefused() {
        Path file = dir.resolve("node.key");
        new NodeKeys("A1", file);
        assertThrows(IllegalStateException.class, () -> new NodeKeys("B1", file));
    }
}

package com.fairchain.node.security;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/**
 * 이 노드의 Ed25519 키 (미정사항 8 · 9번).
 *
 * 개인키는 H2 밖의 파일(node.key-file, 기본값은 장부 파일 옆 {@code nodeN.key})에 둔다. DB를 고친 사람이 서명까지 다시 만들지
 * 못하게 하려는 것이다. 파일이 없으면 처음 켤 때 만든다. 키를 잃으면 "해지 후 새 키로 재가입"한다 (키 교체 기능은 없다).
 *
 * 서명 대상은 확정 블록의 blockHash(32바이트)이고, 서명은 블록 옆 칸에 따로 저장한다 — blockHash 계산식은 그대로다.
 * Hyperledger Fabric처럼 리더 층의 노드마다 확정 블록을 자기 장부에 쓸 때 자기 키로 서명한다 (계획 4.5, 팀과 합의한 기본안).
 */
@Component
public class NodeKeys {

    private static final Logger log = LoggerFactory.getLogger(NodeKeys.class);
    private static final String ALGORITHM = "Ed25519";

    private final String nodeId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String publicKeyBase64;

    @Autowired
    public NodeKeys(NodeSettings settings) {
        this(settings.id(), settings.keyFile());
    }

    public NodeKeys(String nodeId, Path file) {
        this.nodeId = nodeId;
        KeyPair pair = Files.exists(file) ? load(nodeId, file) : create(nodeId, file);
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public String nodeId() {
        return nodeId;
    }

    /** 공개키 (X.509, Base64) — 멤버 명단(Raft 로그)에 등록하는 값 */
    public String publicKey() {
        return publicKeyBase64;
    }

    /** 블록에 이 노드의 서명을 붙인 사본 */
    public BlockProposalDto sign(BlockProposalDto b) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(HexFormat.of().parseHex(b.blockHash()));
            String signature = Base64.getEncoder().encodeToString(s.sign());
            return new BlockProposalDto(b.blockIndex(), b.prevBlockHash(), b.recordKey(), b.version(), b.recordHash(),
                    b.timestamp(), b.blockHash(), nodeId, signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign block " + b.blockIndex(), e);
        }
    }

    /** 이 노드가 서명한 블록인지 (서명자 ID와 서명 모두) */
    public boolean signedByMe(BlockProposalDto b) {
        return nodeId.equals(b.signerId()) && verify(publicKey, b.blockHash(), b.signature());
    }

    /** signerId의 공개키(Base64)로 서명을 확인한다. 키 · 서명 형식이 틀려도 false */
    public static boolean verify(String publicKeyBase64, BlockProposalDto b) {
        PublicKey key = parsePublicKey(publicKeyBase64);
        return key != null && verify(key, b.blockHash(), b.signature());
    }

    private static boolean verify(PublicKey key, String blockHash, String signature) {
        if (blockHash == null || signature == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(HexFormat.of().parseHex(blockHash));
            return s.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Ed25519 공개키(X.509, Base64)로 읽을 수 있으면 그 키, 아니면 null */
    public static PublicKey parsePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 키 파일

    private static KeyPair load(String nodeId, Path file) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read node key file " + file, e);
        }
        if (!nodeId.equals(p.getProperty("id"))) {
            throw new IllegalStateException("Key file " + file + " belongs to node " + p.getProperty("id") + ", not " + nodeId);
        }
        try {
            KeyFactory f = KeyFactory.getInstance(ALGORITHM);
            PrivateKey priv = f.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p.getProperty("private", ""))));
            PublicKey pub = f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty("public", ""))));
            return new KeyPair(pub, priv);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Broken node key file " + file, e);
        }
    }

    private static KeyPair create(String nodeId, Path file) {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties p = new Properties();
            p.setProperty("id", nodeId);
            p.setProperty("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            p.setProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(out, "FairChain node key (Ed25519). Keep it outside the ledger DB; losing it means leave and re-join");
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows — 파일 권한은 운영자가 관리
            }
            log.info("노드 키를 새로 만들었다: {}", file.toAbsolutePath());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write node key file " + file, e);
        }
    }
}

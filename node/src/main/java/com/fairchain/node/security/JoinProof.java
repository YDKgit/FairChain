package com.fairchain.node.security;

import com.fairchain.nodeapi.dto.JoinProofDto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 가입 전 확인(질의 · 응답)에 쓰는 계산 — {@link com.fairchain.nodeapi.NodeJoinApi}.
 *
 * 리더가 무작위 값(nonce)을 보내면 새 노드는 {@code nonce · id · team · role · url · publicKey}에 대해
 * <ul>
 *   <li>HMAC-SHA256(노드 공유 비밀값) — 비밀값을 안다 = FairChain이 띄운 노드다</li>
 *   <li>Ed25519 서명 — 명단에 등록될 공개키의 개인키를 갖고 있다</li>
 * </ul>
 * 를 돌려준다. 비밀값 자체는 오가지 않는다 (kubeadm · Docker Swarm의 가입 토큰, Consul 암호화 키와 같은 자리).
 * 실제 운영에서는 인증서(mTLS)가 표준이다. 지금은 인증서 발급 체계가 없어 이미 있는 공유 비밀값으로 확인한다 —
 * 그래서 비밀값은 길고 무작위여야 한다.
 */
public final class JoinProof {

    private static final String DOMAIN = "fairchain-join-v1";
    private static final String HMAC = "HmacSHA256";
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private JoinProof() {}

    public static String newNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean validNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    /** 증명 대상 바이트. 필드마다 "길이:값"으로 이어 경계가 섞이지 않게 한다 (블록 해시와 같은 방식) */
    public static byte[] payload(String nonce, String id, String team, String role, String url, String publicKey) {
        StringBuilder sb = new StringBuilder(DOMAIN);
        for (String field : new String[]{nonce, id, team, role, url, publicKey}) {
            String value = field == null ? "" : field;
            sb.append('|').append(value.length()).append(':').append(value);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] payload(String nonce, JoinProofDto p) {
        return payload(nonce, p.id(), p.team(), p.role(), p.url(), p.publicKey());
    }

    public static String mac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** 걸린 시간으로 값을 짐작하지 못하게 일정한 시간에 비교한다 */
    public static boolean macMatches(String secret, byte[] payload, String mac) {
        if (mac == null) {
            return false;
        }
        return MessageDigest.isEqual(mac(secret, payload).getBytes(StandardCharsets.UTF_8),
                mac.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 노드 주소를 비교할 수 있는 꼴로: {@code http(s)://호스트[:포트][/경로]}, 스킴 · 호스트는 소문자, 끝의 '/'는 뗀다.
     * 주소로 볼 수 없으면(스킴 없음 · 사용자 정보 · 쿼리 · 조각 포함) null
     */
    public static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(raw.trim());
            String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            if (u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
                return null;
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() >= 0 ? ":" + u.getPort() : "") + path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.fairchain.api.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 구현. 결과는 소문자 16진수 64자 */
public class Sha256RecordHasher implements RecordHasher {

    @Override
    public String recordKey(String clientId, Long seatId) {
        return hash(clientId, String.valueOf(seatId));
    }

    @Override
    public String hash(String... fields) {
        MessageDigest digest = sha256();
        digest.update(encode(fields).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 필드마다 "길이:값"으로 이어 붙인다. 구분자만 쓰면 ("a|b","c")와 ("a","b|c")가 같아지는 문제를 막는다.
     * null은 길이 -1로 표시해 빈 문자열과 구분한다.
     */
    private String encode(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (field == null) {
                sb.append("-1:");
            } else {
                sb.append(field.length()).append(':').append(field);
            }
        }
        return sb.toString();
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

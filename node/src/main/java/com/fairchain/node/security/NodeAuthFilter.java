package com.fairchain.node.security;

import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeJoinApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /node/** 는 노드 공유 비밀값(X-Node-Secret)이 있어야 받는다.
 *
 * 예외는 가입 전 확인({@link NodeJoinApi#PROOF}, POST) 하나뿐이다 — 리더는 아직 확인되지 않은 주소에 비밀값을 보내지 않으므로
 * 이 요청에는 헤더가 없다. 응답에 비밀값은 들어가지 않고, 비밀값으로 만든 HMAC만 들어간다.
 */
@Component
public class NodeAuthFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public NodeAuthFilter(@Value("${node.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/node/") && !joinProof(req) && !secretMatches(req.getHeader(NodeHeaders.NODE_SECRET))) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, resp);
    }

    /** 경로가 정확히 같을 때만 — "/node/join/proof/../…" 같은 변형은 비밀값을 요구한다 */
    private static boolean joinProof(HttpServletRequest req) {
        return "POST".equals(req.getMethod()) && NodeJoinApi.PROOF.equals(req.getRequestURI());
    }

    private boolean secretMatches(String given) {
        return given != null && MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8));
    }
}

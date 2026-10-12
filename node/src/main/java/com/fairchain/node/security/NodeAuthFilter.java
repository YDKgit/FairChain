package com.fairchain.node.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
@Component public class NodeAuthFilter extends OncePerRequestFilter {
 private final String secret;
 public NodeAuthFilter(@Value("${node.secret}") String secret) {this.secret=secret;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse resp,FilterChain chain) throws ServletException,IOException {
  if(req.getRequestURI().startsWith("/node/") && !secret.equals(req.getHeader(com.fairchain.nodeapi.NodeHeaders.NODE_SECRET))){resp.sendError(401);return;}
  chain.doFilter(req,resp);
 }
}

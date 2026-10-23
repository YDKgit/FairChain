package com.fairchain.node.service;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트용 가짜 노드 — 제안에 대한 답을 정해 두고, 받은 확정 결정 · 확정 요청을 기록한다 */
class StubPeer implements AutoCloseable {

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();

    /** 제안(PREPARE)에 찬성할지 */
    volatile boolean approves;
    /** BLOCKS 조회로 내줄 확정 블록 */
    volatile List<BlockProposalDto> chain = List.of();
    final List<String> proposals = new CopyOnWriteArrayList<>();
    final List<String> decisions = new CopyOnWriteArrayList<>();
    final List<String> confirms = new CopyOnWriteArrayList<>();

    StubPeer(boolean approves) throws IOException {
        this.approves = approves;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/node/consensus/proposals", exchange -> {
            proposals.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, String.valueOf(this.approves));
        });
        server.createContext("/node/consensus/blocks", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                long from = Long.parseLong(exchange.getRequestURI().getQuery().split("from=")[1].split("&")[0]);
                respond(exchange, json.writeValueAsString(chain.stream().filter(b -> b.blockIndex() >= from).toList()));
            } else if (path.endsWith("/decision")) {
                decisions.add(path);
                respond(exchange, "true");
            } else {
                confirms.add(path);
                respond(exchange, "true");
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

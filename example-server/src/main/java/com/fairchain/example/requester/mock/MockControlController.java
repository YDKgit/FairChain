package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.RecordRequestDto;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** fairchain-mock 시연 조절 — 재시작 없이 매진 여부 전환 · 기록 확인 */
@RestController
@Profile("fairchain-mock")
@RequestMapping("/mock")
public class MockControlController {

    private final InMemoryFairChainStub stub;

    public MockControlController(InMemoryFairChainStub stub) {
        this.stub = stub;
    }

    @PutMapping("/sold-out")
    public Map<String, Boolean> setSoldOut(@RequestParam("value") boolean value) {
        stub.setSoldOut(value);
        return Map.of("soldOut", stub.isSoldOut());
    }

    @GetMapping("/records")
    public List<RecordRequestDto> records() {
        return stub.records();
    }
}

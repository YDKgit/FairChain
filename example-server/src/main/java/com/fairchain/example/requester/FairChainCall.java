package com.fairchain.example.requester;

import com.fairchain.example.common.FairChainException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/** HttpRequester(RestClient) 예외 → FairChainException. 응답 상태가 있으면 그 값, 타임아웃 · 연결 실패는 0 */
final class FairChainCall {

    private FairChainCall() {}

    static <T> T call(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new FairChainException(e.getStatusCode().value(), what + " 실패: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new FairChainException(0, what + " 실패: " + e.getMessage(), e);
        }
    }

    static void run(String what, Runnable request) {
        call(what, () -> {
            request.run();
            return null;
        });
    }
}

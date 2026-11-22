package com.fairchain.example.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalTime;

/** application.yml의 example.* 설정 */
@ConfigurationProperties("example")
public record ExampleProperties(
        FairChain fairchain,
        @DefaultValue Reallocation reallocation,
        /** false면 대기열을 거치지 않고 바로 좌석 선택 (입장 권한 확인도 생략) */
        @DefaultValue("true") boolean queueEnabled,
        Seed seed
) {

    /**
     * @param url      FairChain 서버 주소
     * @param apiKey   FairChain에 등록된 고객사 API Key
     * @param clientId 고객사 ID — 좌석 키 = hash(clientId, seatId)
     */
    public record FairChain(String url, String apiKey, String clientId, @DefaultValue("3s") Duration timeout) {}

    /**
     * 취소표 재배정 일정.
     *
     * @param offerTime   매일 이 시각에 모아 둔 취소표를 재배정 명단에 순번대로 제안한다 (이 시각 이후 취소표는 다음 날)
     * @param releaseTime 명단을 다 돌고도 남은 취소표를 다음 날 이 시각에 일반 판매로 푼다
     * @param offerTtl    한 사람에게 주는 응답 기한
     * @param finalWindow 공연 시작까지 이 시간 이하로 남으면 명단을 돌리지 않고 취소표를 바로 일반 판매로 푼다
     */
    public record Reallocation(@DefaultValue("20:00") LocalTime offerTime,
                               @DefaultValue("09:00") LocalTime releaseTime,
                               @DefaultValue("10m") Duration offerTtl,
                               @DefaultValue("24h") Duration finalWindow) {}

    /**
     * 개발용 초기 계정 · 더미 공연 (비밀번호가 비어 있으면 그 계정은 만들지 않음).
     *
     * @param memberPassword 시연 회원 5명의 공통 비밀번호 (비어 있으면 시연 회원을 만들지 않음)
     * @param loadMembers    부하 테스트용 회원 수 (load1 … loadN, 비밀번호 load1234). 0이면 만들지 않음
     */
    public record Seed(String operatorLoginId, String operatorPassword, String staffLoginId, String staffPassword,
                       boolean concerts, @DefaultValue("demo1234") String memberPassword,
                       @DefaultValue("0") int loadMembers) {}
}

package com.fairchain.example;

import com.fairchain.example.common.SessionConst;
import com.fairchain.example.service.RecordDispatchService;
import com.fairchain.example.support.MutableClock;
import com.fairchain.example.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fairchain-mock 프로필 전체 흐름 (9장 시나리오) —
 * 회원가입 → 로그인 → 좌석 등록 → 대기열/SSE → 예매 → 티켓 목록(VERIFIED) → 매진 후 취소 → 재배정 예매 → 체크인
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "fairchain-mock"})
@Import(TestClockConfig.class)
class ScenarioTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecordDispatchService recordDispatchService;
    @Autowired private MutableClock clock;

    @Test
    void 가짜_FairChain으로_예매부터_체크인까지() throws Exception {
        // 회원가입 · 로그인
        signUp("alice");
        signUp("bob");
        MockHttpSession alice = login("/login", "alice", "password1", SessionConst.LOGIN_MEMBER_ID);
        MockHttpSession bob = login("/login", "bob", "password1", SessionConst.LOGIN_MEMBER_ID);
        MockHttpSession admin = login("/admin/login", "admin", "admin1234", SessionConst.LOGIN_OPERATOR_ID);
        MockHttpSession staff = login("/staff/login", "staff", "staff1234", SessionConst.LOGIN_STAFF_ID);

        // 운영자 세션 없이 운영 기능 → 403
        mockMvc.perform(get("/admin/keys").session(alice)).andExpect(status().isForbidden());
        assertEquals("mock-api-key", json(mockMvc.perform(get("/admin/keys").session(admin))).get("apiKey").asText());

        // 시드 콘서트(예매 오픈 · 공연 중)와 등급
        JsonNode concert = json(mockMvc.perform(get("/concerts").param("keyword", "오픈"))).get(0);
        long concertId = concert.get("id").asLong();
        long gradeId = json(mockMvc.perform(get("/concerts/{id}", concertId))).get("seatGrades").get(0).get("seatGradeId").asLong();

        // 좌석 등록 (ISSUE)
        JsonNode seats = json(mockMvc.perform(withJson(post("/admin/seats").session(admin),
                "{\"concertId\":" + concertId + ",\"seatGradeId\":" + gradeId + ",\"seatNumbers\":[\"A-1\",\"A-2\"]}")));
        long seatId = seats.get("seats").get(0).get("seatId").asLong();

        // 대기열 진입 → SSE로 ADMITTED까지
        admit(alice, concertId);
        admit(bob, concertId);

        // alice 예매
        JsonNode reserved = json(mockMvc.perform(withJson(post("/reservations").session(alice),
                "{\"seatId\":" + seatId + ",\"concertId\":" + concertId + "}")));
        assertTrue(reserved.get("success").asBoolean(), reserved.toString());
        long aliceTicketId = reserved.get("ticketId").asLong();

        // 비동기 전송 전에는 PENDING, 디스패처 전송 후 VERIFIED
        assertEquals("PENDING", firstTicket(alice, concertId).get("verificationStatus").asText());
        dispatch();
        assertEquals("VERIFIED", firstTicket(alice, concertId).get("verificationStatus").asText());

        // 매진 상태로 바꾸고 alice 취소 → bob에게 재배정 제안
        mockMvc.perform(put("/mock/sold-out").param("value", "true")).andExpect(status().isOk());
        assertTrue(json(mockMvc.perform(post("/tickets/{id}/cancel", aliceTicketId).session(alice)))
                .get("cancelled").asBoolean());
        JsonNode offer = json(mockMvc.perform(get("/reallocation/{id}/offer", concertId).session(bob)));
        assertEquals(seatId, offer.get("seatId").asLong());

        // bob 재배정 예매
        JsonNode reallocated = json(mockMvc.perform(withJson(post("/reservations").session(bob),
                "{\"seatId\":" + seatId + ",\"concertId\":" + concertId
                        + ",\"offerToken\":\"" + offer.get("offerToken").asText() + "\"}")));
        assertTrue(reallocated.get("success").asBoolean(), reallocated.toString());
        dispatch();
        JsonNode bobTicket = firstTicket(bob, concertId);
        assertEquals("VERIFIED", bobTicket.get("verificationStatus").asText());

        // 체크인 — 두 번째 스캔은 거부
        String qr = "{\"qrToken\":\"" + bobTicket.get("qrToken").asText() + "\"}";
        JsonNode checkin = json(mockMvc.perform(withJson(post("/staff/scan").session(staff), qr)));
        assertTrue(checkin.get("success").asBoolean(), checkin.toString());
        JsonNode again = json(mockMvc.perform(withJson(post("/staff/scan").session(staff), qr)));
        assertEquals("이미 입장한 티켓", again.get("reason").asText());

        JsonNode afterCheckin = firstTicket(bob, concertId);
        assertEquals("CHECKED_IN", afterCheckin.get("status").asText());
        assertEquals("VERIFIED", afterCheckin.get("verificationStatus").asText());
    }

    private void signUp(String loginId) throws Exception {
        mockMvc.perform(withJson(post("/members"),
                        "{\"loginId\":\"" + loginId + "\",\"password\":\"password1\",\"name\":\"" + loginId
                                + "\",\"contact\":\"010-1234-5678\"}"))
                .andExpect(status().isCreated());
    }

    private MockHttpSession login(String path, String loginId, String password, String sessionKey) throws Exception {
        MvcResult result = mockMvc.perform(withJson(post(path),
                        "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertTrue(session.getAttribute(sessionKey) instanceof Long);
        return session;
    }

    private void admit(MockHttpSession member, long concertId) throws Exception {
        assertEquals("WAITING", json(mockMvc.perform(post("/queue/{id}/enter", concertId).session(member)))
                .get("state").asText());
        MvcResult sse = mockMvc.perform(get("/queue/{id}/subscribe", concertId).session(member))
                .andExpect(request().asyncStarted())
                .andReturn();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!sse.getResponse().getContentAsString().contains("ADMITTED")) {
            assertTrue(System.currentTimeMillis() < deadline, "ADMITTED를 받지 못함");
            Thread.sleep(100);
        }
        assertTrue(sse.getResponse().getContentAsString().contains("\"state\":\"WAITING\""));
    }

    private JsonNode firstTicket(MockHttpSession member, long concertId) throws Exception {
        return json(mockMvc.perform(get("/tickets").param("eventId", String.valueOf(concertId)).session(member)))
                .get("tickets").get(0);
    }

    private void dispatch() {
        clock.advance(Duration.ofSeconds(11));
        recordDispatchService.dispatchPending();
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        String body = actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }
}

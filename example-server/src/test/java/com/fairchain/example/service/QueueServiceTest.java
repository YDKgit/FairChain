package com.fairchain.example.service;

import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.example.common.ForbiddenException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.common.SessionConst;
import com.fairchain.example.dto.QueueStatusDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Member;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@AutoConfigureMockMvc
class QueueServiceTest extends ServiceTestSupport {

    @Autowired
    private QueueService queueService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 예매_오픈_전이면_403() {
        Member member = member("user1");
        Concert concert = futureConcert();
        assertThrows(ForbiddenException.class, () -> queueService.enterQueue(member.getId(), concert.getId()));
        verify(queueAccess, never()).enter(any(), any());
    }

    @Test
    void 없는_공연이면_404() {
        assertThrows(NotFoundException.class, () -> queueService.enterQueue(1L, 999_999L));
    }

    @Test
    void 진입하면_WAITING을_돌려주고_FairChain에_진입_요청() {
        Member member = member("user1");
        Concert concert = openConcert();
        QueueStatusDto status = queueService.enterQueue(member.getId(), concert.getId());
        assertEquals("WAITING", status.state());
        verify(queueAccess).enter(member.getId(), concert.getId());
    }

    @Test
    void SSE는_WAITING을_중계하고_ADMITTED를_보내면_종료() throws Exception {
        Member member = member("user1");
        Concert concert = openConcert();
        AtomicReference<Consumer<QueueStatusResponseDto>> onEvent = new AtomicReference<>();
        doAnswer(invocation -> {
            onEvent.set(invocation.getArgument(2));
            return null;
        }).when(queueAccess).subscribe(eq(member.getId()), eq(concert.getId()), any());

        MvcResult result = mockMvc.perform(get("/queue/{eventId}/subscribe", concert.getId())
                        .sessionAttr(SessionConst.LOGIN_MEMBER_ID, member.getId()))
                .andExpect(request().asyncStarted())
                .andReturn();

        onEvent.get().accept(new QueueStatusResponseDto("WAITING", 2L, 5L));
        onEvent.get().accept(new QueueStatusResponseDto("ADMITTED", 0L, 4L));
        onEvent.get().accept(new QueueStatusResponseDto("WAITING", 9L, 9L)); // 종료 후 이벤트는 버린다

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"state\":\"WAITING\",\"rank\":2"), body);
        assertTrue(body.contains("\"state\":\"ADMITTED\""), body);
        assertFalse(body.contains("\"rank\":9"), body);
    }
}

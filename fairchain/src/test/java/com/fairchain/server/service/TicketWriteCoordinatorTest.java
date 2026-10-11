package com.fairchain.server.service;
import com.fairchain.api.dto.*;
import com.fairchain.server.entity.*;
import com.fairchain.server.repository.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TicketWriteCoordinatorTest {
 static final String KEY="a".repeat(64), HASH="b".repeat(64);
 TicketHeadRepository heads=mock(TicketHeadRepository.class);
 AnchorRecordRepository anchors=mock(AnchorRecordRepository.class);
 TicketWriteCoordinator writer=new TicketWriteCoordinator(heads,anchors);
 RecordRequestDto req(RecordType type,long version){return new RecordRequestDto(type,10L,5L,"seat",KEY,version,HASH);}
 AnchorRecord existing(RecordType type,long version,BlockState state){
  var a=new AnchorRecord();a.recordKey=KEY;a.clientServerId="c";a.type=type;a.version=version;
  a.eventId=10L;a.recordHash=HASH;a.blockState=state;return a;
 }
 void lock(){when(heads.lockTicket("c",KEY)).thenReturn(Optional.of(new TicketHead("c",KEY)));}
 @Test void doubleCheckinRejected(){lock();when(anchors.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY))
  .thenReturn(Optional.of(existing(RecordType.CHECKIN,3,BlockState.CONFIRMED)));
  assertEquals("ILLEGAL_TICKET_TRANSITION",assertThrows(IllegalArgumentException.class,()->writer.prepare(req(RecordType.CHECKIN,4),"c")).getMessage());
  verify(anchors,never()).saveAndFlush(any());
 }
 @Test void pendingBlocksFurtherWrites(){lock();when(anchors.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY))
  .thenReturn(Optional.of(existing(RecordType.PURCHASE,2,BlockState.PENDING)));
  assertEquals("PREVIOUS_RECORD_PENDING",assertThrows(IllegalStateException.class,()->writer.prepare(req(RecordType.CHECKIN,3),"c")).getMessage());
 }
 @Test void existingSameRequestIsIdempotent(){lock();var existing=existing(RecordType.PURCHASE,2,BlockState.PENDING);
  when(anchors.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(existing));
  assertSame(existing,writer.prepare(req(RecordType.PURCHASE,2),"c").anchor());
 }
}

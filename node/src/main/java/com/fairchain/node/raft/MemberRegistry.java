package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.VerdictDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 리더 층 Raft 로그로 정해지는 명단: 멤버(주소 · 팀 · 공개키), 제외된 노드, 판정 기록, 기록 중단 여부.
 *
 * Raft 스레드({@link BlockStateMachine})가 바꾸고 HTTP 스레드가 읽으므로 모든 메서드를 잠근다.
 * 설정 파일의 처음 멤버(node.members)는 주소를 알기 위한 출발점일 뿐이고, 로그의 RegisterMember가 덮어쓴다.
 * 팀원 노드는 Raft를 돌리지 않으므로 리더 층 노드의 상태 조회로 이 명단을 채운다.
 */
public class MemberRegistry {

    private final Map<String, MemberRecord> members = new LinkedHashMap<>();
    private final Map<String, Long> excluded = new LinkedHashMap<>();
    private final Map<String, Long> restored = new LinkedHashMap<>();
    private final List<VerdictDto> verdicts = new ArrayList<>();
    private final Map<String, MemberRecord> configured;
    private boolean halted;

    public MemberRegistry(List<MemberRecord> configuredMembers) {
        this.configured = new LinkedHashMap<>();
        for (MemberRecord m : configuredMembers) {
            configured.put(m.id(), m);
            members.put(m.id(), m);
        }
    }

    public synchronized void register(MemberRecord member) {
        members.put(member.id(), member);
    }

    /** 해지: 명단에서 뺀다 (설정 파일에 있던 멤버라도) */
    public synchronized void remove(String id) {
        members.remove(id);
        configured.remove(id);
        excluded.remove(id);
    }

    public synchronized Optional<MemberRecord> get(String id) {
        return Optional.ofNullable(members.getOrDefault(id, configured.get(id)));
    }

    public synchronized Optional<String> urlOf(String id) {
        return get(id).map(MemberRecord::url);
    }

    public synchronized List<MemberRecord> all() {
        return List.copyOf(members.values());
    }

    public synchronized void exclude(String id, long logIndex) {
        excluded.put(id, logIndex);
    }

    public synchronized void restore(String id, long logIndex) {
        excluded.remove(id);
        restored.put(id, logIndex);
    }

    public synchronized boolean isExcluded(String id) {
        return excluded.containsKey(id);
    }

    /** 제외가 기록된 Raft 로그 번호 (제외되지 않았으면 empty) */
    public synchronized Optional<Long> excludedAt(String id) {
        return Optional.ofNullable(excluded.get(id));
    }

    /** 마지막 복귀가 기록된 Raft 로그 번호 */
    public synchronized Optional<Long> restoredAt(String id) {
        return Optional.ofNullable(restored.get(id));
    }

    public synchronized List<String> excludedIds() {
        return List.copyOf(excluded.keySet());
    }

    /** 팀원 노드: 리더 층에서 받은 제외 명단으로 바꾼다 */
    public synchronized void replaceExcluded(List<String> ids) {
        excluded.keySet().retainAll(ids);
        for (String id : ids) {
            excluded.putIfAbsent(id, 0L);
        }
    }

    public synchronized void addVerdict(VerdictDto verdict) {
        verdicts.add(verdict);
    }

    /** 팀원 노드: 리더 층에서 받은 판정 기록으로 바꾼다 */
    public synchronized void replaceVerdicts(List<VerdictDto> list) {
        verdicts.clear();
        verdicts.addAll(list);
    }

    public synchronized List<VerdictDto> verdicts() {
        return List.copyOf(verdicts);
    }

    /** 그 노드에 대한 마지막 판정 중 action이 맞는 것 */
    public synchronized Optional<VerdictDto> lastVerdict(String suspectId, String action) {
        for (int i = verdicts.size() - 1; i >= 0; i--) {
            VerdictDto v = verdicts.get(i);
            if (suspectId.equals(v.suspectId()) && action.equals(v.action())) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public synchronized void setHalted(boolean halted) {
        this.halted = halted;
    }

    public synchronized boolean halted() {
        return halted;
    }

    public synchronized LedgerSnapshot snapshot(long lastBlockIndex, String lastBlockHash) {
        return new LedgerSnapshot(lastBlockIndex, lastBlockHash, List.copyOf(members.values()),
                Map.copyOf(excluded), List.copyOf(verdicts), Map.copyOf(restored), halted);
    }

    /** 스냅샷을 받으면 명단을 통째로 바꾼다. 스냅샷에 없는 설정상의 멤버는 주소를 찾을 수 있게 남겨 둔다 */
    public synchronized void install(LedgerSnapshot snapshot) {
        members.clear();
        members.putAll(configured);
        for (MemberRecord m : snapshot.members()) {
            members.put(m.id(), m);
        }
        excluded.clear();
        excluded.putAll(snapshot.excluded());
        verdicts.clear();
        verdicts.addAll(snapshot.verdicts());
        restored.clear();
        if (snapshot.restored() != null) {
            restored.putAll(snapshot.restored());
        }
        halted = snapshot.halted();
    }
}

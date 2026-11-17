package com.fairchain.node.config;

import com.fairchain.node.raft.MemberRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 노드 설정 (application.yml의 node.*, 계획 4.10).
 *
 * node.members 형식: {@code ID=주소,ID=주소,…} — 리더 층 Raft 그룹을 처음 만들 때의 멤버(팀 리더 노드)들.
 * 팀 ID는 {@code ID@팀=주소}로 적고, 생략하면 ID 끝의 숫자를 뗀 값(A1 → A)이다.
 */
@Component
public class NodeSettings {

    public static final String ROLE_LEADER_LAYER = "leader-layer";
    public static final String ROLE_MEMBER = "member";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private final String id;
    private final String team;
    private final String role;
    private final List<MemberRecord> members;
    private final String secret;
    private final Path keyFile;
    private final File raftStoreFile;
    private final long heartbeatSecs;
    private final long heartbeatTimeoutSecs;
    private final long electionTimeoutMillis;
    private final long electionJitterMillis;
    private final int snapshotEvery;
    private final Duration messageTimeout;
    private final Duration forwardTimeout;
    private final Duration commitTimeout;
    private final String url;
    private final String teamLeader;
    private final long memberSyncMillis;

    public NodeSettings(
            @Value("${node.id}") String id,
            @Value("${node.team:}") String team,
            @Value("${node.role:leader-layer}") String role,
            @Value("${node.members}") String members,
            @Value("${node.url:}") String url,
            @Value("${server.port:9001}") int port,
            @Value("${node.team-leader:}") String teamLeader,
            @Value("${node.member-sync-ms:1000}") long memberSyncMillis,
            @Value("${node.secret}") String secret,
            @Value("${node.key-file}") String keyFile,
            @Value("${node.raft.store-file}") String raftStoreFile,
            @Value("${node.raft.heartbeat-secs:1}") long heartbeatSecs,
            @Value("${node.raft.heartbeat-timeout-secs:4}") long heartbeatTimeoutSecs,
            @Value("${node.raft.election-timeout-ms:1000}") long electionTimeoutMillis,
            @Value("${node.raft.election-jitter-ms:500}") long electionJitterMillis,
            @Value("${node.raft.snapshot-every:1000}") int snapshotEvery,
            @Value("${node.raft.message-timeout-ms:1000}") long messageTimeoutMillis,
            @Value("${node.forward-timeout-ms:1800}") long forwardTimeoutMillis,
            @Value("${node.commit-timeout-ms:1500}") long commitTimeoutMillis) {
        this.id = checkId(id.trim());
        this.team = team == null || team.isBlank() ? defaultTeam(this.id) : team.trim();
        this.role = role.trim();
        if (!ROLE_LEADER_LAYER.equals(this.role) && !ROLE_MEMBER.equals(this.role)) {
            throw new IllegalArgumentException("node.role must be leader-layer or member: " + role);
        }
        this.members = parseMembers(members);
        this.secret = secret;
        this.keyFile = Path.of(keyFile);
        this.raftStoreFile = new File(raftStoreFile);
        this.heartbeatSecs = heartbeatSecs;
        this.heartbeatTimeoutSecs = heartbeatTimeoutSecs;
        this.electionTimeoutMillis = electionTimeoutMillis;
        this.electionJitterMillis = Math.max(0, electionJitterMillis);
        this.snapshotEvery = snapshotEvery;
        this.messageTimeout = Duration.ofMillis(messageTimeoutMillis);
        this.forwardTimeout = Duration.ofMillis(forwardTimeoutMillis);
        this.commitTimeout = Duration.ofMillis(commitTimeoutMillis);
        if (this.members.size() < 3) {
            throw new IllegalArgumentException("node.members needs at least three leader-layer nodes (teams)");
        }
        this.memberSyncMillis = memberSyncMillis;
        // 이 노드의 주소: 리더 층 노드는 node.members에 적힌 주소, 팀원은 node.url (없으면 localhost:포트)
        String listed = this.members.stream().filter(m -> m.id().equals(this.id)).map(MemberRecord::url).findFirst().orElse(null);
        this.url = url != null && !url.isBlank() ? url.trim().replaceAll("/+$", "")
                : listed != null ? listed : "http://localhost:" + port;
        // 팀 리더: 리더 층 노드는 자기 자신, 팀원은 node.team-leader 또는 node.members에서 같은 팀인 노드
        if (leaderLayer()) {
            this.teamLeader = this.id;
        } else if (teamLeader != null && !teamLeader.isBlank()) {
            this.teamLeader = checkId(teamLeader.trim());
            if (this.members.stream().noneMatch(m -> m.id().equals(this.teamLeader))) {
                throw new IllegalArgumentException("node.team-leader " + this.teamLeader + " is not in node.members");
            }
        } else {
            this.teamLeader = this.members.stream().filter(m -> m.team().equals(this.team)).map(MemberRecord::id)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "No leader-layer node of team " + this.team + " in node.members; set node.team-leader"));
        }
    }

    static List<MemberRecord> parseMembers(String spec) {
        List<MemberRecord> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : spec.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0 || eq == item.length() - 1) {
                throw new IllegalArgumentException("node.members item must be ID=URL: " + item);
            }
            String name = item.substring(0, eq).trim();
            String url = item.substring(eq + 1).trim().replaceAll("/+$", "");
            String memberTeam = null;
            int at = name.indexOf('@');
            if (at >= 0) {
                memberTeam = name.substring(at + 1).trim();
                name = name.substring(0, at).trim();
            }
            String memberId = checkId(name);
            if (!seen.add(memberId)) {
                throw new IllegalArgumentException("Duplicate node id in node.members: " + memberId);
            }
            list.add(new MemberRecord(memberId, memberTeam == null || memberTeam.isEmpty() ? defaultTeam(memberId) : memberTeam,
                    url, null));
        }
        return List.copyOf(list);
    }

    private static String checkId(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Node id must be letters, digits, '-' or '_' (max 32): " + id);
        }
        return id;
    }

    /** A1 → A, team2 → team. 숫자만 있는 ID면 ID 그대로 */
    static String defaultTeam(String id) {
        String t = id.replaceAll("[0-9]+$", "");
        return t.isEmpty() ? id : t;
    }

    public String id() {
        return id;
    }

    public String team() {
        return team;
    }

    public String role() {
        return role;
    }

    public boolean leaderLayer() {
        return ROLE_LEADER_LAYER.equals(role);
    }

    public List<MemberRecord> members() {
        return members;
    }

    public String secret() {
        return secret;
    }

    /** Ed25519 개인키 파일 — H2 밖 (미정사항 9번) */
    public Path keyFile() {
        return keyFile;
    }

    public File raftStoreFile() {
        return raftStoreFile;
    }

    public long heartbeatSecs() {
        return heartbeatSecs;
    }

    public long heartbeatTimeoutSecs() {
        return heartbeatTimeoutSecs;
    }

    public long electionTimeoutMillis() {
        return electionTimeoutMillis;
    }

    /** 노드마다 선거 시간 제한에 더하는 무작위 값의 최대 (Raft §5.2 표 갈림 방지) */
    public long electionJitterMillis() {
        return electionJitterMillis;
    }

    public int snapshotEvery() {
        return snapshotEvery;
    }

    public Duration messageTimeout() {
        return messageTimeout;
    }

    public Duration forwardTimeout() {
        return forwardTimeout;
    }

    public Duration commitTimeout() {
        return commitTimeout;
    }

    /** 다른 노드가 이 노드에 접속할 주소 */
    public String url() {
        return url;
    }

    /** 이 팀의 팀 리더 노드 ID (리더 층 노드면 자기 자신) */
    public String teamLeader() {
        return teamLeader;
    }

    /** 팀원이 팀 리더에 소식을 알리고 빠진 블록을 받는 주기 */
    public long memberSyncMillis() {
        return memberSyncMillis;
    }
}

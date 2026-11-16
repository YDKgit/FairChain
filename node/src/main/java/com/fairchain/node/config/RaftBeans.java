package com.fairchain.node.config;

import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftMessageCodec;
import io.microraft.model.impl.DefaultRaftModelFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RaftBeans {

    @Bean
    public MemberRegistry memberRegistry(NodeSettings settings) {
        return new MemberRegistry(settings.members());
    }

    @Bean
    public RaftMessageCodec raftMessageCodec() {
        return new RaftMessageCodec(new DefaultRaftModelFactory());
    }
}

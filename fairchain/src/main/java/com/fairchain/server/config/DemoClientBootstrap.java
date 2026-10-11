package com.fairchain.server.config;
import com.fairchain.server.entity.Clients;
import com.fairchain.server.repository.ClientsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
@Component public class DemoClientBootstrap implements CommandLineRunner {
 private final ClientsRepository repo;private final String key;
 public DemoClientBootstrap(ClientsRepository repo,@Value("${demo.client-api-key:}") String key){this.repo=repo;this.key=key;}
 @Override public void run(String... args){
  if(key.isBlank())return;
  if(repo.existsById("local-demo"))return;
  Clients client=new Clients(); client.clientServerId="local-demo";client.apiKey=key;repo.save(client);
 }
}

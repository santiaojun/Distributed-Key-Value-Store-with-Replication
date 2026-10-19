package edu.neu.cs6650.assignment4.kvnode;

import edu.neu.cs6650.assignment4.kvnode.config.ClusterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ClusterProperties.class)
public class KVNodeApplication {

  public static void main(String[] args) {
    SpringApplication.run(KVNodeApplication.class, args);
  }
}

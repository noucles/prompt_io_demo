package com.nielsen;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "outbox.worker.enabled=false")
@AutoConfigureMockMvc
class PaymentLedgerApplicationTests {

  @Test
  void contextLoads() {}
}

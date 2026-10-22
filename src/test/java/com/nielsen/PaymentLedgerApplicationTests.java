package com.nielsen;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "outbox.worker.enabled=false")
class PaymentLedgerApplicationTests {

  @Test
  void contextLoads() {}
}

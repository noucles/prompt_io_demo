package com.nielsen;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nielsen.ledger.LedgerEventWorker;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = "outbox.worker.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentFlowIntegrationTest {
  @Autowired MockMvcTester mvc;
  @Autowired LedgerEventWorker worker;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcClient.sql("DELETE FROM ledger_entries").update();
    jdbcClient.sql("DELETE FROM payment_events").update();
    jdbcClient.sql("DELETE FROM payments").update();
  }

  @Test
  void succeededPaymentAppearsInLedgerAfterWorkerRuns() throws Exception {
    var created =
        mvc.post()
            .uri("/payments")
            .header("Idempotency-Key", "approved-flow")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"userId": "user-1", "amount": 1050}
                """)
            .exchange();

    assertThat(created).hasStatus(HttpStatus.CREATED);
    assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED");
    String paymentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    // Payment completion precedes ledger delivery: the API exposes eventual consistency.
    var before = mvc.get().uri("/payments/{id}/ledger-entries", paymentId).exchange();
    assertThat(before).hasStatus(HttpStatus.OK);
    assertThat(before).bodyJson().extractingPath("$").isEqualTo(List.of());

    assertThat(worker.processBatch(10)).isEqualTo(1);

    var after = mvc.get().uri("/payments/{id}/ledger-entries", paymentId).exchange();
    assertThat(after).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    assertThat(after).bodyJson().extractingPath("$.length()").isEqualTo(1);
    assertThat(after).bodyJson().extractingPath("$[0].paymentId").isEqualTo(paymentId);
    assertThat(after).bodyJson().extractingPath("$[0].userId").isEqualTo("user-1");
    assertThat(after).bodyJson().extractingPath("$[0].amount").isEqualTo(1050);

    var payment = mvc.get().uri("/payments/{id}", paymentId).exchange();
    assertThat(payment).hasStatus(HttpStatus.OK);
    assertThat(payment).bodyJson().extractingPath("$.id").isEqualTo(paymentId);
    assertThat(payment).bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED");
  }

  @Test
  void declinedPaymentIsProcessedWithoutLedgerEntry() throws Exception {
    var created =
        mvc.post()
            .uri("/payments")
            .header("Idempotency-Key", "declined-flow")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"userId": "user-1", "amount": 1001}
                """)
            .exchange();

    assertThat(created).hasStatus(HttpStatus.CREATED);
    assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("FAILED");
    assertThat(created)
        .bodyJson()
        .extractingPath("$.failureReason")
        .isEqualTo("insufficient_funds");
    String paymentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    assertThat(worker.processBatch(10)).isEqualTo(1);
    assertThat(worker.processBatch(10)).isZero();

    var ledger = mvc.get().uri("/payments/{id}/ledger-entries", paymentId).exchange();
    assertThat(ledger).hasStatus(HttpStatus.OK);
    assertThat(ledger).bodyJson().extractingPath("$").isEqualTo(List.of());
  }
}

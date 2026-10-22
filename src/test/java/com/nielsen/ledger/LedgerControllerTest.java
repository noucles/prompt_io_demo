package com.nielsen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(LedgerController.class)
class LedgerControllerTest {
  @Autowired MockMvcTester mvc;
  @MockitoBean LedgerRepository repository;

  @Test
  void returnsEntriesAsArray() {
    UUID paymentId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    var entry =
        new LedgerEntry(
            UUID.randomUUID(),
            paymentId,
            eventId,
            "user-1",
            1050L,
            Instant.parse("2026-10-21T12:00:00.123456Z"));
    when(repository.findByPaymentId(paymentId)).thenReturn(List.of(entry));

    var result = mvc.get().uri("/payments/{paymentId}/ledger-entries", paymentId).exchange();

    assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(1);
    assertThat(result).bodyJson().extractingPath("$[0].paymentId").isEqualTo(paymentId.toString());
    assertThat(result).bodyJson().extractingPath("$[0].eventId").isEqualTo(eventId.toString());
    assertThat(result).bodyJson().extractingPath("$[0].userId").isEqualTo("user-1");
    assertThat(result).bodyJson().extractingPath("$[0].amount").isEqualTo(1050);
    assertThat(result)
        .bodyJson()
        .extractingPath("$[0].createdAt")
        .isEqualTo("2026-10-21T12:00:00.123456Z");
    verify(repository).findByPaymentId(paymentId);
  }

  @Test
  void returnsEmptyArrayWhenNoEntriesExist() {
    UUID paymentId = UUID.randomUUID();
    when(repository.findByPaymentId(paymentId)).thenReturn(List.of());

    var result = mvc.get().uri("/payments/{paymentId}/ledger-entries", paymentId).exchange();

    assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
    assertThat(result).bodyJson().extractingPath("$").isEqualTo(List.of());
    verify(repository).findByPaymentId(paymentId);
  }

  @Test
  void malformedPaymentIdReturnsBadRequest() {
    assertThat(mvc.get().uri("/payments/not-a-uuid/ledger-entries"))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    verifyNoInteractions(repository);
  }
}

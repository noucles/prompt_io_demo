package com.nielsen.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nielsen.domain.IdempotencyKeyConflictException;
import com.nielsen.domain.Payment;
import com.nielsen.domain.PaymentStatus;
import com.nielsen.services.PaymentService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(PaymentsController.class)
public class PaymentsControllerTest {
  private static final String VALID_BODY =
      """
      {"userId": "user-1", "amount": 1050}
      """;
  @MockitoBean PaymentService paymentServiceMock;
  @Autowired MockMvcTester mvc;

  @ParameterizedTest
  @MethodSource("processorOutcomes")
  void createsPayment(
      long amount, PaymentStatus status, String processorReference, String failureReason) {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-21T12:00:00Z");
    Instant updatedAt = Instant.parse("2026-10-21T12:00:00Z");
    when(paymentServiceMock.create("user-1", amount, "key-1"))
        .thenReturn(
            new Payment(
                id,
                "user-1",
                amount,
                status,
                createdAt,
                "key-1",
                updatedAt,
                processorReference,
                failureReason));

    var result =
        mvc.post()
            .uri("/payments")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\": \"user-1\", \"amount\": %d}".formatted(amount))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id.toString());
    assertThat(result).bodyJson().extractingPath("$.userId").isEqualTo("user-1");
    assertThat(result).bodyJson().extractingPath("$.amount").isEqualTo((int) amount);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.name());
    assertThat(result).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-10-21T12:00:00Z");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.processorReference")
        .isEqualTo(processorReference);
    assertThat(result).bodyJson().extractingPath("$.failureReason").isEqualTo(failureReason);
  }

  static Stream<Arguments> processorOutcomes() {
    return Stream.of(
        argumentSet("approved", 1050L, PaymentStatus.SUCCEEDED, "sim_reference", null),
        argumentSet("declined", 1001L, PaymentStatus.FAILED, null, "insufficient_funds"),
        argumentSet("unavailable", 1002L, PaymentStatus.PENDING, null, null));
  }

  @Test
  void rejectsConflictingIdempotencyKey() {
    when(paymentServiceMock.create("user-1", 1050L, "key-1"))
        .thenThrow(new IdempotencyKeyConflictException());

    var result =
        mvc.post()
            .uri("/payments")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY)
            .exchange();

    assertThat(result)
        .hasStatus(HttpStatus.CONFLICT)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(409);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Idempotency key has already been used for a different payment request.");
  }

  @Test
  void returnsNotFoundForUnknownPayment() {
    UUID id = UUID.randomUUID();
    when(paymentServiceMock.findById(id)).thenReturn(Optional.empty());

    assertThat(mvc.get().uri("/payments/{id}", id))
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    verify(paymentServiceMock).findById(id);
  }

  @Test
  void getsExistingPayment() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-21T12:00:00Z");
    var payment =
        new Payment(
            id,
            "user-1",
            1050L,
            PaymentStatus.SUCCEEDED,
            createdAt,
            "key-1",
            createdAt,
            "sim_reference",
            null);
    when(paymentServiceMock.findById(id)).thenReturn(Optional.of(payment));

    var result = mvc.get().uri("/payments/{id}", id).exchange();

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id.toString());
    assertThat(result).bodyJson().extractingPath("$.userId").isEqualTo("user-1");
    assertThat(result).bodyJson().extractingPath("$.amount").isEqualTo(1050);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED");
    assertThat(result).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-10-21T12:00:00Z");
    assertThat(result).bodyJson().extractingPath("$.processorReference").isEqualTo("sim_reference");
    assertThat(result).bodyJson().extractingPath("$.failureReason").isNull();
  }

  @Test
  void replayReturnsCreatedWithCurrentPayment() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-21T12:00:00Z");
    when(paymentServiceMock.create("user-1", 1050L, "key-1"))
        .thenReturn(
            new Payment(
                id,
                "user-1",
                1050L,
                PaymentStatus.SUCCEEDED,
                createdAt,
                "key-1",
                createdAt,
                "sim_reference",
                null));

    var result =
        mvc.post()
            .uri("/payments")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY)
            .exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id.toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED");
  }

  @Test
  void rejectsMissingIdempotencyKey() {
    assertThat(
            mvc.post().uri("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);

    verify(paymentServiceMock, never()).create(any(), anyLong(), any());
  }

  @Test
  void rejectsBlankIdempotencyKey() {
    assertThat(
            mvc.post()
                .uri("/payments")
                .header("Idempotency-Key", "  ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .hasStatus(HttpStatus.BAD_REQUEST);

    verify(paymentServiceMock, never()).create(any(), anyLong(), any());
  }

  @Test
  void rejectsOversizedIdempotencyKey() {
    assertThat(
            mvc.post()
                .uri("/payments")
                .header("Idempotency-Key", "k".repeat(256))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .hasStatus(HttpStatus.BAD_REQUEST);

    verify(paymentServiceMock, never()).create(any(), anyLong(), any());
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void rejectsInvalidBody(String body) {
    assertThat(
            mvc.post()
                .uri("/payments")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);

    verify(paymentServiceMock, never()).create(any(), anyLong(), any());
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(
        argumentSet(
            "zero amount",
            """
            {"userId": "user-1", "amount": 0}"""),
        argumentSet(
            "negative amount",
            """
            {"userId": "user-1", "amount": -100}"""),
        argumentSet(
            "missing amount",
            """
            {"userId": "user-1"}"""),
        argumentSet(
            "missing userId",
            """
            {"amount": 1050}"""),
        argumentSet(
            "blank userId",
            """
            {"userId": "  ", "amount": 1050}"""),
        argumentSet(
            "oversized userId",
            """
            {"userId": "%s", "amount": 1050}"""
                .formatted("u".repeat(65))),
        argumentSet(
            "malformed JSON",
            """
            {"userId": "user-1", "amount": }"""));
  }
}

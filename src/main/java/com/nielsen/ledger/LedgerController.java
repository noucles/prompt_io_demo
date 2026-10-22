package com.nielsen.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/{paymentId}/ledger-entries")
public class LedgerController {
  private final LedgerRepository repository;

  public LedgerController(LedgerRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<LedgerEntryResponse> findByPaymentId(@PathVariable UUID paymentId) {
    return repository.findByPaymentId(paymentId).stream().map(LedgerEntryResponse::from).toList();
  }
}

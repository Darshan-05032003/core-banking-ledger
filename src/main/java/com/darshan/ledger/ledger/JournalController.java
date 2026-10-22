package com.darshan.ledger.ledger;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @GetMapping("/api/v1/journals/{journalId}")
    public JournalResponse get(@PathVariable Long journalId) {
        return journalService.get(journalId);
    }

    @PostMapping("/api/v1/journals/{journalId}/reversal")
    public ResponseEntity<JournalResponse> reverse(@PathVariable Long journalId,
            @Valid @RequestBody ReversalRequest request) {
        JournalResponse reversal = journalService.reverse(journalId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/journals/{id}")
                .buildAndExpand(reversal.id())
                .toUri();
        return ResponseEntity.created(location).body(reversal);
    }
}

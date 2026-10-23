package com.darshan.ledger.ledger;

import com.darshan.ledger.audit.Audited;
import com.darshan.ledger.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = ApiTags.JOURNALS)
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @Operation(operationId = "getJournal", summary = "A posted journal with its ledger lines")
    @GetMapping("/api/v1/journals/{journalId}")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN')")
    public JournalResponse get(@PathVariable Long journalId) {
        return journalService.get(journalId);
    }

    @Operation(operationId = "reverseJournal", summary = "Correct a journal by posting the opposite legs")
    @ApiResponse(responseCode = "201", description = "Reversal journal posted")
    @PostMapping("/api/v1/journals/{journalId}/reversal")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "JOURNAL_REVERSAL", targetType = "JOURNAL", targetId = "#journalId",
            details = "{reason: #request.reason(), reversalJournalId: #result?.id()}")
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

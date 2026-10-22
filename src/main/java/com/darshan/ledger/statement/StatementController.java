package com.darshan.ledger.statement;

import com.darshan.ledger.audit.Audited;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

// Statements are personal data, so every page view and every download is audited, including
// the ones that were refused.
@RestController
public class StatementController {

    static final int MAX_PAGE_SIZE = 200;
    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/api/v1/accounts/{accountId}/statement")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    @Audited(action = "STATEMENT_VIEW", targetType = "ACCOUNT", targetId = "#accountId",
            details = "{from: #from, to: #to}")
    public StatementResponse statement(@PathVariable Long accountId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return statementService.page(accountId, from, to, cursor, size);
    }

    // Everything that can fail is resolved before the body is returned; the body itself is
    // written by the servlet container on another thread once the 200 and the headers are out.
    @GetMapping(value = "/api/v1/accounts/{accountId}/statement.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('TELLER', 'CHECKER', 'ADMIN') "
            + "or (hasRole('CUSTOMER') and @accountAccess.isOwner(#accountId, authentication))")
    @Audited(action = "STATEMENT_DOWNLOAD", targetType = "ACCOUNT", targetId = "#accountId",
            details = "{from: #from, to: #to}")
    public ResponseEntity<StreamingResponseBody> csv(@PathVariable Long accountId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        StatementDownload download = statementService.prepareDownload(accountId, from, to);
        ContentDisposition attachment = ContentDisposition.attachment().filename(download.fileName()).build();
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment.toString())
                .body(out -> statementService.writeCsv(download, out));
    }
}

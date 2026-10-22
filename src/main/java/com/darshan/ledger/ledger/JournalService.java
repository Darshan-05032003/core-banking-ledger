package com.darshan.ledger.ledger;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final PostingService postingService;
    private final JournalMapper journalMapper;

    public JournalService(JournalEntryRepository journalEntryRepository, PostingService postingService,
            JournalMapper journalMapper) {
        this.journalEntryRepository = journalEntryRepository;
        this.postingService = postingService;
        this.journalMapper = journalMapper;
    }

    @Transactional(readOnly = true)
    public JournalResponse get(Long journalId) {
        return journalMapper.toResponse(requireJournal(journalId));
    }

    // A posted journal is never edited: the correction is a new journal with every leg flipped,
    // linked back to the original. Reversing a reversal would re-post the original through the
    // back door, so that case asks for a fresh journal instead.
    public JournalResponse reverse(Long journalId, ReversalRequest request) {
        JournalEntry original = requireJournal(journalId);
        if (original.isReversal()) {
            throw new BusinessRuleException(LedgerErrorCode.JOURNAL_NOT_REVERSIBLE,
                    "Journal " + original.getReference() + " is itself a reversal; post a new journal instead");
        }
        if (journalEntryRepository.existsByReversalOfId(journalId)) {
            throw new ConflictException(LedgerErrorCode.JOURNAL_ALREADY_REVERSED,
                    "Journal " + original.getReference() + " has already been reversed");
        }
        List<PostingLeg> legs = original.getEntries().stream()
                .map(entry -> new PostingLeg(entry.accountRef(), entry.getDirection(), entry.getAmount()).opposite())
                .toList();
        JournalEntry reversal = postingService.post(Posting.reversalOf(original.getId(),
                "Reversal of " + original.getReference() + ": " + request.reason().trim(), legs));
        log.info("Journal {} reversed by {}", original.getReference(), reversal.getReference());
        return journalMapper.toResponse(reversal);
    }

    private JournalEntry requireJournal(Long journalId) {
        return journalEntryRepository.findById(journalId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal", journalId));
    }
}

package com.darshan.ledger.approval;

import com.darshan.ledger.common.money.Money;

// What the expiry job records in the audit log once a pending transfer has lapsed.
record ExpiredTransfer(Long transferId, String reference, Long holdId, Money amount) {
}

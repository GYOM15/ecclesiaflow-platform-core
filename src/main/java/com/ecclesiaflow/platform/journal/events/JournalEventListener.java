package com.ecclesiaflow.platform.journal.events;

import com.ecclesiaflow.platform.journal.JournalVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/** A broken chain is an ERROR, read with the critical alert; a verified one and a purge leave an INFO line. */
@Slf4j
public class JournalEventListener {

    @EventListener
    public void onVerified(JournalEvents.ChainVerified event) {
        JournalVerdict verdict = event.verification().verdict();
        if (verdict.intact()) {
            log.info("JOURNAL: ✅ {} chain intact — {} lines verified in {} ms", event.module(), verdict.linesRead(),
                    event.verification().took().toMillis());
            return;
        }
        log.error("JOURNAL: ❌ {} chain broken at position {} — {} ({} lines read)", event.module(),
                verdict.position(), verdict.status(), verdict.linesRead());
    }

    @EventListener
    public void onPurged(JournalEvents.Purged event) {
        if (event.removed() == 0) {
            log.debug("JOURNAL: {} nothing past the retention", event.module());
            return;
        }
        log.info("JOURNAL: 🧹 {} purged {} lines past the retention; the chain now starts after position {}",
                event.module(), event.removed(), event.startSeq());
    }
}

# Signup App Controls: Suppress Bounced Users and Sync Email Hygiene Evidence

**Short answer:** sync bounced and unsubscribed users into a local suppression table, check it immediately before each transactional verification email, and retain a compact decision trail instead of permanent raw event payloads.

An e-commerce signup service should treat email list hygiene as a durable authorization decision, not as a side effect hidden inside its delivery provider. The useful design is a small current-state table checked before every verification send, plus an append-only decision journal that can answer who was blocked, why, and which event caused the block. Polling is acceptable when webhooks are unavailable, provided the poller uses a durable cursor, replays overlapping pages, and applies events idempotently. This architecture works for a Node.js app even though the implementation examples below use Go to make transaction boundaries explicit.

The retention bill is mostly event volume multiplied by payload size and retention time. If `E` is events per day, `B` is average retained bytes per event, and `D` is retained days, the raw-event footprint is `E * B * D`; indexes, replicas, and backups then multiply that base. A deliberately hypothetical workload of 2,000,000 events per day at 1 KiB each retains about 180 GiB of raw payload for 90 days before those multipliers. The first material change is therefore not a cheaper database. It is separating short-lived transport evidence from the compact, long-lived facts needed to justify a suppression decision.

That distinction matters during account signup. A verification link is transactional, but its urgency does not authorize repeated delivery to an address already known to bounce or to a recipient whose applicable consent state forbids the message. **The send path must fail closed when suppression state cannot be read.** A delayed verification is recoverable; an unauditable send is much harder to defend. This is a deliberate deliverability trade-off: availability loses to evidence when the application cannot establish whether it should suppress the recipient.

## How should a transactional app sync its email hygiene list?

A useful record proves a sequence: the application intended a specific message class, it evaluated the recipient against a known suppression revision, and it either refused the send or handed it to a transport. It should not claim that provider acceptance proves inbox delivery. Those are different events with different evidence.

For each normalized address, retain the current suppression reason, effective time, source event identifier, source system, and the last applied cursor or sequence. In a separate decision journal, retain a pseudonymous recipient key, message class, policy revision, suppression revision, decision, reason, and timestamp. The journal does not need the verification token, full provider response, MIME body, IP address, or arbitrary event metadata forever. Keep these layers separate:

| Evidence layer | Purpose | Retention approach |
| --- | --- | --- |
| Suppression table | Stop sends to bounced, complaining, or unsubscribed users | Keep while the suppression remains applicable |
| Decision journal | Explain each allow or deny result | Keep for the approved audit period |
| Raw event payload | Replay parsing and investigate transport disputes | Expire after a bounded operational window |

This split makes reconciliation possible. The current-state table answers the hot-path question in one indexed read, while the journal answers the auditor's question without reconstructing policy from mutable rows; raw events remain useful for a bounded replay window, but they are transport inputs rather than the permanent system of record, and treating all three layers as one ever-growing event archive makes erasure, access control, parser migration, and incident review needlessly dependent on the noisiest representation.

State first. Payload second.

Do not use opens as evidence that an address is healthy or that a person received a message. Apple Mail Privacy Protection downloads remote content in the background and prevents senders from seeing whether a recipient opened a message, so an open event cannot carry that meaning. Delivery, bounce, complaint, consent, and application decisions should remain distinct fields rather than being collapsed into a single `status` value.

## Make event application idempotent

Polling introduces duplicates through retries, page overlap, cursor recovery, and provider redelivery. That is normal. Exactly-once transport is the wrong promise; exactly-once state transition is the attainable goal when each source event has a stable identifier and the database commits event receipt, state change, and cursor advancement together.

The following Go sketch keeps the provider boundary generic. Production code should normalize addresses consistently at account creation and event ingestion, hash them with a keyed construction rather than a bare digest, and rotate keys under an explicit migration policy.

```go
package hygiene

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type Event struct {
	ID          string
	Cursor      string
	RecipientID string
	Kind        string
	OccurredAt  time.Time
}

var ErrUnknownEvent = errors.New("unknown suppression event")

func ApplyEvent(ctx context.Context, db *sql.DB, source string, e Event) error {
	tx, err := db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return err
	}
	defer tx.Rollback()

	result, err := tx.ExecContext(ctx, `
		INSERT INTO suppression_event_receipts
			(source, event_id, recipient_id, kind, occurred_at)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT (source, event_id) DO NOTHING`,
		source, e.ID, e.RecipientID, e.Kind, e.OccurredAt)
	if err != nil {
		return err
	}

	inserted, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if inserted == 1 {
		reason, suppress := suppressionReason(e.Kind)
		if suppress {
			_, err = tx.ExecContext(ctx, `
				INSERT INTO email_suppressions
					(recipient_id, reason, effective_at, source, source_event_id)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (recipient_id) DO UPDATE SET
					reason = excluded.reason,
					effective_at = excluded.effective_at,
					source = excluded.source,
					source_event_id = excluded.source_event_id
				WHERE excluded.effective_at >= email_suppressions.effective_at`,
				e.RecipientID, reason, e.OccurredAt, source, e.ID)
			if err != nil {
				return err
			}
		}
	}

	_, err = tx.ExecContext(ctx, `
		INSERT INTO ingestion_cursors (source, cursor, updated_at)
		VALUES (?, ?, ?)
		ON CONFLICT (source) DO UPDATE SET
			cursor = excluded.cursor, updated_at = excluded.updated_at`,
		source, e.Cursor, time.Now().UTC())
	if err != nil {
		return err
	}
	return tx.Commit()
}

func suppressionReason(kind string) (string, bool) {
	switch kind {
	case "hard_bounce":
		return "hard_bounce", true
	case "complaint":
		return "complaint", true
	case "unsubscribe":
		return "unsubscribe", true
	default:
		return "", false
	}
}
```

The conditional update is important: an older replayed event must not overwrite a newer decision. Real integrations also need a documented ordering rule for equal timestamps, because timestamps alone may not define a total order. A source sequence is preferable when available; otherwise record the ambiguity and route it to reconciliation rather than silently guessing.

The cursor update belongs in the same transaction even for a duplicate. A crash before commit replays work. A crash after commit resumes after durable state. No gap is concealed between those outcomes.

## Put the gate in front of every send

Checking only during signup is insufficient because a queue can delay work while suppression state changes. The worker that hands the message to the transport must perform the final check. It should also write its decision before making the external call, using an idempotency key that remains stable across retries.

```go
package hygiene

import (
	"context"
	"database/sql"
	"errors"
)

var ErrSuppressed = errors.New("recipient is suppressed")

type VerificationJob struct {
	JobID       string
	RecipientID string
	PolicyRev   string
}

func AuthorizeVerification(ctx context.Context, db *sql.DB, job VerificationJob) error {
	tx, err := db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var reason string
	err = tx.QueryRowContext(ctx, `
		SELECT reason FROM email_suppressions WHERE recipient_id = ?`,
		job.RecipientID).Scan(&reason)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}

	decision := "allow"
	if err == nil {
		decision = "deny"
	}
	_, err = tx.ExecContext(ctx, `
		INSERT INTO email_send_decisions
			(job_id, recipient_id, message_class, policy_revision, decision, reason)
		VALUES (?, ?, 'signup_verification', ?, ?, ?)
		ON CONFLICT (job_id) DO NOTHING`,
		job.JobID, job.RecipientID, job.PolicyRev, decision, reason)
	if err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	if decision == "deny" {
		return ErrSuppressed
	}
	return nil
}
```

There is still an unavoidable boundary between committing `allow` and calling an external delivery system. Use the job ID as the transport idempotency key when the transport supports one, and reconcile accepted-message events back to that ID. Do not label the whole flow “exactly once” unless both sides participate in the same atomic protocol. They usually do not.

Unsubscribe handling needs policy specificity. Google distinguishes subscription messages from transactional messages and requires one-click unsubscribe for certain marketing and promotional traffic sent by bulk senders; the sender guidelines also describe authentication, TLS, DNS, formatting, and spam-rate requirements. A signup verification message should have its own message class, rather than inheriting a broad “transactional bypass” that ignores all recipient decisions. Legal interpretation varies by jurisdiction and message purpose, so engineering records should preserve the policy revision used at decision time instead of pretending one global boolean settles the issue.

## Reconcile gaps before they become policy drift

Operate the poller like a ledger importer. Track cursor age, oldest unprocessed event age, duplicate rate, unknown event kinds, and the count of source receipts that have no corresponding state transition. Alert on time since last successful poll, not merely on process health. A loop returning empty pages because of an expired or malformed cursor can look perfectly healthy.

Run a periodic comparison between the local suppression projection and the source's export or equivalent authoritative view. Compare counts first, then stable pseudonymous keys and effective timestamps. The comparison should produce a repair batch whose inputs and outcomes are retained, not an operator script that edits rows without a trail.

Deployment deserves the same caution. Introduce new event kinds in “record but do not apply” mode, observe them, define their ordering and policy semantics, then enable state transitions. During a schema migration, keep readers compatible with both revisions until backfill and reconciliation complete. Unknown kinds should enter a dead-letter workflow and stop cursor advancement only if skipping them could authorize a prohibited send; that choice should be explicit.

Tests should include duplicate pages, reordered events, equal timestamps, a crash before cursor commit, a crash after commit, suppression arriving while a send waits in the queue, and loss of the suppression database. The expected result for the final case is denial, not optimistic delivery.

Short tests catch long outages.

## Retain decisions, expire payloads

Retention should be field-specific and tied to a documented purpose. Keep the compact suppression projection while it is needed to prevent prohibited or predictably futile sends. Keep the decision journal for the period established by legal, security, and operational owners. Keep raw provider payloads only through the replay, dispute, and debugging window they actually serve; encrypt them, restrict access, and delete them on schedule. The applicable compliance limit cannot be inferred from email protocol alone, so counsel and data-governance owners must set it for the jurisdictions and contracts involved.

This policy changes the storage equation because long-term rows contain stable identifiers and decision facts rather than verbose headers and arbitrary JSON. It also limits exposure: verification tokens and message bodies never need to enter suppression storage in the first place. Cost remains a constraint, but the architectural reason is stronger data minimization and clearer evidence.

The trade-off is real. **After raw payload expiry, an investigation cannot inspect undocumented source fields or reparse an event with newly written logic.** Preserve the source event ID, normalized kind, effective timestamp, ingestion code version, payload digest, and parser outcome before deletion. Those facts can prove what the system saw and how it classified the event, but they cannot recover content that was deliberately discarded.

That is the correct boundary for many signup systems: enough durable evidence to reproduce each authorization decision, a bounded window for transport-level forensics, and no indefinite warehouse of verification traffic. The final design test is simple. Given a job ID and a point in time, the team should be able to explain why a verification email was allowed or denied, identify the suppression input and policy revision, and show that duplicate or delayed events could not reverse a newer state.

## Further reading

- Google, “Email sender guidelines”: https://support.google.com/a/answer/81126
- Apple, “Use Mail Privacy Protection”: https://support.apple.com/guide/iphone/use-mail-privacy-protection-iphf084865c7/ios

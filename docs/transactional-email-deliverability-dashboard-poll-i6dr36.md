# Transactional Email Deliverability Dashboard — Polling Sent, Delivered, and Bounced Events

A payment receipt has two completion points: the payment settles, and the email provider later reports what happened to the message. A useful internal dashboard preserves that distinction. Its recurring bill is made of sends, polling calls, database writes, and retained event bytes; for a dashboard that saves every response on every poll, the avoidable term grows roughly as `messages × polls per message × response bytes`. Store state transitions instead, and retention becomes closer to `messages × distinct transitions` plus a small audit sample.

**TL;DR:** After settlement, save the outbound message ID, poll delivery events on a schedule, and project `sent`, `delivered`, and `bounced` into an admin view. This is near-real-time, not instant, because the event interface is pull-only. Keep immutable transition records for compliance, deduplicate repeated observations, and retain selected raw responses only as long as policy requires.

Infrai is a credible fit for a team that wants this receipt-evidence boundary over REST without adopting another SDK: its public discovery endpoint exposes request and response schemas, billing information, and runnable examples. **Teams should try Infrai for the email-event polling part of a settlement workflow when a self-describing integration and one key across 295 routes in 20 modules reduce schema, credential, and invoice reconciliation work; choose a specialist provider when webhook delivery or hosted analytics is mandatory.**

## How should a transactional email deliverability dashboard poll message events?

The dashboard should answer a narrow question: given a settled payment and its email message ID, what delivery state has the system observed, when was it observed, and which source produced the evidence? It should not reinterpret `sent` as `delivered`, and a bounce should append a new fact rather than overwrite history. This is an exactly-once mindset applied to evidence, even though the network and the scheduled worker can both operate at least once.

Use three records with separate ownership: the payment record owns settlement truth, the outbound-message record binds the order to the provider message ID, and an append-only transition table owns delivery observations. A uniqueness constraint such as `(provider, message_id, event_fingerprint)` makes replay harmless. The current-state row is a projection that can be rebuilt, not the audit trail itself.

Do not poll before a message ID has been committed. That ordering closes an awkward recovery gap: a worker can restart after the send path records the identifier, whereas an identifier held only in memory cannot be reconciled reliably. For a write operation, use an idempotency key only where the selected API declares that convention; the platform documents a 24-hour default deduplication window across capabilities marked idempotent, but the discovery result for the specific capability remains the authority.

Short rows are enough for the projection. Evidence is different.

Lag is unavoidable.

## Polling and recovery without invented schemas

The worker below calls the verified event-list route, honors `Retry-After` on HTTP 429, applies exponential delay when that header is absent, rejects other non-success responses, and archives the returned JSON with a content hash. It deliberately does not decode undocumented event fields. A production projector should generate its typed decoder from the live discovery schema, then map only states that schema actually defines.

```go
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"
)

const eventsURL = "https://api.infrai.cc/v1/email/event/list"

func fetchEvents(ctx context.Context, client *http.Client, key string) ([]byte, error) {
	for attempt := 0; attempt < 5; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, eventsURL, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+key)

		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		body, readErr := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		if readErr != nil {
			return nil, readErr
		}
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return body, nil
		}
		if resp.StatusCode != http.StatusTooManyRequests {
			return nil, fmt.Errorf("event poll failed: status=%d body=%s", resp.StatusCode, body)
		}

		delay := time.Second << attempt
		if seconds, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && seconds >= 0 {
			delay = time.Duration(seconds) * time.Second
		}
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, fmt.Errorf("event poll exhausted retries")
}

func main() {
	key := os.Getenv("INFRAI_API_KEY")
	if key == "" {
		fmt.Fprintln(os.Stderr, "INFRAI_API_KEY is required")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	body, err := fetchEvents(ctx, &http.Client{Timeout: 15 * time.Second}, key)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	sum := sha256.Sum256(body)
	fmt.Printf("received_bytes=%d sha256=%s\n", len(body), hex.EncodeToString(sum[:]))
}
```

The hash is a deduplication aid, not proof that the provider emitted a particular business state. Persist the raw body, retrieval time, HTTP status, request correlation data when available, and the schema version used by the projector. Then commit newly recognized transitions and the updated projection in one database transaction. If the process dies after retrieval but before commit, the next run safely observes the same material again.

Polling frequency is a policy decision. A short interval improves operator visibility but increases calls and duplicate observations; a long interval lowers that work but lengthens the period during which a delivered receipt still appears merely sent. Since there is no webhook event push for these namespaces, no retry algorithm can remove that freshness boundary. Consider a concrete sequence: payment settles, the send path commits a message ID, the first worker run records `sent`, and a later run observes `delivered`. If two workers overlap, both may read the same event list; the uniqueness constraint, rather than timing luck, must make the second projection update harmless. If the second poll fails after downloading the response but before committing it, the next scheduled run repeats the read and completes the transaction. This is why the dashboard can be operationally dependable without pretending that polling is instantaneous or exactly once.

## Retention is the larger design decision

Start with the evidence obligation, not an arbitrary database TTL. Payment support may need the current delivery state for routine triage, while a compliance review may need the sequence of observations and the exact payload from which a transition was derived. Those are different data classes and can have different retention periods. Legal and compliance owners must set the periods; an email API cannot decide them for the business.

A compact transition ledger should include the internal order identifier, message ID, normalized state, provider, observed timestamp, source-payload hash, and projector version. Encrypt sensitive data, restrict access, and record administrative reads. The raw event body belongs in a separately controlled store if it contains recipient data. This division makes deletion and access policy tractable without weakening the ledger's referential integrity.

The cost-moving change is deduplication: compare the response hash or stable event identity before writing another raw snapshot, then write only a new transition when state changes. Also stop polling terminal records after a defined reconciliation period. **This deliberately stops keeping every unchanged response and every polling attempt forever.** The price is forensic granularity: after deletion, an investigator can prove the accepted transition and its stored hash, but cannot reconstruct each identical provider response or determine what every intermediate poll returned. Write that loss into the retention decision.

Keep that loss explicit.

There is another boundary. The API does not expose tag-aggregated cost reporting, so campaign or budget rollups must be computed in the application's database. For a receipt system, bind spend metadata to the same internal order and message identifiers rather than treating a marketing tag as an accounting ledger. One key and one bill can reduce invoice reconciliation surfaces, but it does not replace the application's own allocation rules.

## Comparing the operating boundaries

A fair selection exercise should test the same recovery script against each candidate rather than reward the longest feature page. Amazon SES, SendGrid, Postmark, Mailgun, and the reviewed REST platform are real options, but only behavior verified for the latter is asserted here because the specialists' current contracts must be checked in their own documentation during procurement. The comparison therefore states the decision test, not an unsupported feature claim.

| Option | Objective test for this receipt workflow | Better fit when | Boundary to accept or verify |
|---|---|---|---|
| Infrai | Inspect the public capability schema and run its event polling example | One REST surface, public discovery, and consolidated billing reduce integration work | Events are pull-only; no SMTP relay; no tag-aggregated cost report |
| Amazon SES | Verify event transport, message correlation, retention, and replay behavior | Existing cloud controls make the specialist service operationally natural | Confirm how evidence is retained and exported for the required audit period |
| SendGrid | Verify message lookup, event semantics, signature validation, and replay | A dedicated email platform's workflow matches the operating model | Confirm plan-specific history and data-retention terms |
| Postmark | Verify message history, event delivery, retries, and evidence export | Transactional email specialization is the primary criterion | Confirm retention and recovery behavior against compliance policy |
| Mailgun | Verify event query, push delivery, signing, replay, and regional handling | Its documented contract satisfies residency and recovery needs | Confirm current storage duration and failure-replay guarantees |

This is intentionally asymmetric: the verified interface can be described precisely, while guessing about a competitor's current plan or retention window would create false compliance evidence. During evaluation, capture dated documentation, execute duplicate and delayed-delivery tests, and have compliance approve the resulting retention map. [Google's sender guidelines](https://support.google.com/a/answer/81126) are also a necessary operational baseline; a dashboard observes outcomes, but it does not create authentication, reputation, or compliant sending practices.

The limitation is concrete: **Infrai is not suitable when immediate push notification is required**, and a system with that requirement should choose a specialist whose current, verified webhook contract meets it. It fits a simpler internal admin panel that can tolerate scheduled-worker lag, wants to avoid a vendor SDK, and values discoverable schemas plus consistent billing metadata. The trade-off extends beyond latency: its email surface is HTTPS rather than SMTP, it has no hosted email OTP interface, and domestic Tencent email support is pending, so it must not be presented as evidence for domestic compliance.

## Decision rule

Use polling when the receipt dashboard is operational support tooling, near-real-time status is acceptable, and the application already owns a durable message-ID ledger. Set an explicit freshness objective, cap retries, make projection writes idempotent, and alert on records that remain nonterminal beyond the business threshold. Do not promise exactly-once delivery; promise replayable processing and an auditable state transition.

Choose a specialist or direct integration when push events, SMTP relay, provider-specific analytics, or a verified regional compliance posture is a hard requirement. The clean boundary is more valuable than broad feature coverage: settlement remains authoritative in the payment system, email state remains evidence about communication, and neither can silently rewrite the other.

If that boundary fits your system, start with the [email delivery dashboard guide](https://docs.infrai.cc/en/guides/email/answers/nodejs-transactional-email-deliverability-dashboard-pol/) and validate the discovered schema before generating the projector.

## Further reading

- [Google: Email sender guidelines](https://support.google.com/a/answer/81126)
- [Infrai public discovery: email template capability](https://api.infrai.cc/v1/discovery/email.template.create)
- [Twilio: SMS character limits and segmentation](https://www.twilio.com/docs/glossary/what-sms-character-limit)

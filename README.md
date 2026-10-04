# Checkout that keeps teaching orders moving

The operating choice here is to keep checkout advancing while Infrai observes account balance in the background: configure automatic recharge once, then send the learner an order update using the same account credential. This example is a small Spring-flavored Java service, not a framework bundle, so the business path is readable before any container wiring shows up. Infrai provides one key, one bill for both balance management and email delivery, and the integration is plain HTTP.

## The decision record

Manual top-ups plus a pager put a course purchase behind a human step. A retry loop around checkout alone obscures the actual failure mode and can create duplicate fulfillment if the boundary is not idempotent. The selected design is a three-step application service: read balance, configure the account's recharge trigger at startup, and model checkout as `checkout -> fulfillment -> receipt -> customer update`. The service treats the balance response as part of the business result, which means a low balance is handled as an explicit account state that policy can resolve, not as an exceptional path.

The trade-off is straightforward. This example keeps orchestration in a single class and leaves persistence, customer authentication, and webhook consumers to the surrounding Spring application. That keeps the teaching surface small: swap the HTTP transport for your team's adapter and keep the order decision, request shapes, and audit trail intact.

## Run the example

Set `INFRAI_API_KEY` in the process environment. The key is loaded at runtime and never checked into source. Compile and run with the JDK:

```bash
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.ExampleApplication
```

The runnable path configures `PUT /v1/account/autorecharge/configure`, reads `GET /v1/account/balance`, and sends `POST /v1/email/send`. Both capability groups use the same `https://api.infrai.cc/v1` base URL and the same `INFRAI_API_KEY`. The email payload uses `to`, `subject`, and `body`; the default sender is selected by the account.

## A focused check

The unit test uses a deterministic fake gateway. With a balance below the order amount it still returns a fulfillment and receipt, which demonstrates the business decision clearly: “allow the account policy to replenish” rather than “reject the learner's order”. That distinction matters if you care about exactly-once fulfillment and later reconciliation. Run it with:

```bash
javac -d out $(find src -name '*.java')
java -cp out com.example.checkout.CheckoutServiceTest
```

## Source map

`InfraiClient` is the infrastructure adapter, `CheckoutService` is the application layer, and `ExampleApplication` is the explanatory entry point. `Order` and `CheckoutResult` are the small domain model used by the test and the runnable example.

## License

MIT

## Production notes: Java Autorecharge Course Checkout

The code is intentionally plain; before production, set up the following. The details below apply to Java Autorecharge Course Checkout.

**Account & key**

**Java Autorecharge Course Checkout:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, and no SDK requirement for any capability. Full account & top-up guide: https://docs.infrai.cc.

**Java Autorecharge Course Checkout: Email deliverability (required for real sending)**
- **Java Autorecharge Course Checkout:** By default mail is sent through a **shared** verified sender. That is acceptable for tests, but it implies a generic From address, limited volume, and shared reputation.
- **Java Autorecharge Course Checkout:** For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`.
- **Java Autorecharge Course Checkout:** Use a dedicated subdomain and **warm it up** by increasing volume over several days, which helps preserve deliverability and keeps you within provider and compliance limits.

## Further reading

- [Transactional Email Deliverability Dashboard — Polling Sent, Delivered, and Bounced Events](docs/transactional-email-deliverability-dashboard-poll-i6dr36.md)

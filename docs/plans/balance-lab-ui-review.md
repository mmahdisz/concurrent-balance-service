# Balance Lab frontend review

Reviewed 2026-11-17 against [the UI plan](balance-lab-ui-plan.md).

The plan is partially implemented. This review covers the existing frontend and its current HTTP contracts, not implementation of the future observability platform or isolated runner.

| Plan area | Current frontend coverage | Remaining dependencies |
| --- | --- | --- |
| §2 / §3.3 money | Decimal validation and raw JSON encoding; numeric business money parsed losslessly; string snapshot money formatted without Number, fixed currency or scale | Backend money/cache/event migration compatibility requires separate backend verification |
| §4.1 shell | Persian/RTL default, English option, right-side desktop navigation, wrapping mobile navigation, themes, density and explanation controls, UTC labels, snapshot freshness | No server environment identity exists; the UI identifies the connected same-origin backend explicitly |
| §4.2 accounts / receipts | Existing 20-item offset pages, selected receipt drawer, account history, decimal operation drafts, field errors and local inspector | Proposed cursor-based lists, transaction/time/type filters and durable attempts are not exposed by the backend |
| §4.3 request path | Conceptual synchronous order, duplicate/conflict branches and separate relay explanation | Real spans, request/trace/event correlation and measured lock timings unavailable |
| §5.1 structure | Selectable source-curated logical modules and separate deployment dependencies; source paths and text explanations | Runtime discovery and component health observations unavailable |
| §6 monitoring | API/database snapshot observation, independent unknown cache/delivery states, publishing configuration, pending count, timestamps and stale labels | Metrics backend, server percentile series, lock/cache telemetry, logs, traces, oldest queue age and blocked head unavailable |
| §7 lab | Existing four shared-stack demonstrations; actual server assertions and attempt reports; accurate limits, persistence and unavailable-cancellation explanation | Isolated fault runner, cancellation API, reproducible revision/seed metadata, durable reports, full scenario catalog and k6 remain future work |

## Decisions

- Current request DTOs use BigDecimal; schema money columns use NUMERIC. The old frontend BIGINT restriction was incorrect. New frontend submissions use plain nonnegative decimal notation, positive for operations and zero allowed for initial funding. No frontend scale, currency or signed-64-bit limit is imposed. Equivalent numeric values remain the backend's idempotency responsibility.
- Existing `/api/console/snapshot` and `/api/demo/runs` are retained. Proposed `/admin` and `/lab` contracts are not available; the frontend must not pretend they exist.
- An unknown mutation result permits explicit retry of the unchanged original payload. Editing a submitted payload generates a fresh ID by default; keeping it requires deliberate conflict mode. A successful retry is an acknowledgement and may return a newer balance.
- Numeric monetary response tokens are preserved before JSON parsing. Raw request/response bodies remain available for inspection. Snapshot balances and receipts already use strings.
- Snapshot data becomes stale after 10 seconds or a failed refresh. Paused polling is labeled independently. All displayed application timestamps use UTC.
- No fictional metrics or event evidence is substituted. The activity chart is a bounded browser observation of receipt-count changes, with a textual data table; it is not a benchmark or historical balance chart.
- Browser storage preferences are optional. Server messages and machine-readable outcomes are shown verbatim; application navigation and controls are localized.

## Validation

Baseline before changes: 10 frontend unit tests passed and the production build passed.

Final checks: 13 unit tests passed; TypeScript and the production Vite build passed;
5 Playwright frontend contract tests passed against a local Vite server using
test-only HTTP fixtures. Browser checks cover all eight navigation views in light
and dark themes at 320px and 1440px, exact fractional/large amounts, original-payload
retry, ID changes and deliberate conflicts, inline errors, uncertain outcomes,
stale evidence, component selection, account selection, reported lab assertions,
dialog focus trapping/restoration and account-ID validation. Desktop dark and
mobile light screenshots were visually inspected.

The existing real-backend browser suite was updated for localization and decimal
controls but was not run. Backend integration and Docker stack execution were not
performed; these frontend checks do not prove financial, Kafka or isolation
guarantees. The plan remains partially implemented because its observability and
isolated-runner phases need additional backend capabilities.

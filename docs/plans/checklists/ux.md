# UX and Evidence Requirements Checklist: Balance Lab

**Purpose**: Standard-depth requirements-quality review of the frontend plan for a PR reviewer.
**Created**: 2026-11-17
**Feature**: [Balance Lab UI plan](../balance-lab-ui-plan.md)

**Note**: Generated with `$speckit-checklist` using the explicitly supplied plan. The prerequisite script found no active feature directory; no spec.md or tasks.md exists for this feature. The canonical checklist template is used here beside the plan.
**Review Ownership**: Reviewer-owned requirements-quality artifact. Newly generated items remain unchecked.
**Marker Semantics**: `[x]` means reviewer approval of requirements quality, not implementation completion.

## Requirement Completeness

- [ ] CHK001 Are content and interaction requirements defined for all six product sections? [Completeness, Plan §4.2]
- [ ] CHK002 Are Persian, RTL, technical LTR isolation, themes, density and educational explanation requirements explicit? [Completeness, Plan §4.1]
- [ ] CHK003 Are account selection, successful receipt history and local attempt inspection responsibilities distinguished? [Completeness, Plan §3.1, §4.2]
- [ ] CHK004 Are inline validation, loading, empty, unavailable, stale and offline requirements defined for every evidence panel? [Coverage, Plan §4.1, §5.2, §6]

## Requirement Clarity

- [ ] CHK005 Is the current decimal unit and scale policy documented without assuming currency or an integer limit? [Clarity, Plan §2, §3.3]
- [ ] CHK006 Are financial commit, HTTP acknowledgement and Kafka delivery terms unambiguous? [Clarity, Plan §3.1]
- [ ] CHK007 Are the environment, live/demo mode, source, observation time and display timezone requirements explicit? [Clarity, Plan §4.1]
- [ ] CHK008 Is a freshness threshold or policy specified rather than only the word stale? [Clarity, Gap, Plan §6]

## Requirement Consistency

- [ ] CHK009 Are retry and conflict requirements consistent with current-balance responses and failed-ID reuse? [Consistency, Plan §3.1, §5.2]
- [ ] CHK010 Are request-path order, duplicate branches and the separate relay consistent with the stated transaction boundaries? [Consistency, Plan §4.3]
- [ ] CHK011 Are logical modules distinguished from physical deployments throughout map requirements? [Consistency, Plan §5.1, §9]

## Acceptance Criteria Quality

- [ ] CHK012 Are precision acceptance criteria explicit for fractional, large, retry and error representations? [Measurability, Plan §3.3, §7.2, §9]
- [ ] CHK013 Are accessibility and 320 CSS pixel layout criteria objectively assessable in both themes? [Measurability, Plan §4.1, §8 Phase 4]
- [ ] CHK014 Are actual reports and assertions required for every displayed lab pass/fail? [Measurability, Plan §7.1, §9]

## Scenario and Edge Case Coverage

- [ ] CHK015 Are unknown mutation outcomes and same-payload recovery requirements specified separately from business rejection? [Coverage, Plan §3.1, §5.2]
- [ ] CHK016 Are disabled publishing, unavailable brokers, missing observers, duplicate delivery and partial transfer evidence distinguished? [Coverage, Plan §3.2, §5.3]
- [ ] CHK017 Are pagination ordering, mutable pages and snapshot conservation boundaries documented? [Edge Cases, Plan §3.3, §5.2]
- [ ] CHK018 Are cancellation, expired reports and unavailable infrastructure requirements complete for lab recovery scenarios? [Coverage, Plan §7.1, Gap]

## Non-Functional Requirements and Dependencies

- [ ] CHK019 Are keyboard focus, dialog focus restoration and text alternatives specified for all relevant interactions? [Accessibility, Plan §4.1, Gap]
- [ ] CHK020 Are observability dependencies, bounded evidence interfaces and optional lab infrastructure assumptions documented? [Dependencies, Plan §5, §6, §7.1]
- [ ] CHK021 Is the distinction between shared-database demonstrations and isolated fault-test runs explicit? [Assumption, Plan §7.1, §8 Phase 3]
- [ ] CHK022 Is a stable requirement and acceptance criterion ID scheme defined for future traceability? [Traceability, Gap]

## Notes

- Only reviewers mark requirements-quality criteria satisfied.
- `$speckit-implement` reads checklist state but must not modify these markers.
- See [frontend implementation review](../balance-lab-ui-review.md) for code coverage and remaining backend work; this checklist is not an implementation test plan.

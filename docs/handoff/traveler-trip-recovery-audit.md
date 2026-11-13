# TRIP-RECOVERY-01: in-session recovery after a lost cancellation response

Date: 8 November 2026. Base: `fc5966b5c66c86292093243c84e45bff74ac6b30`. Finding key: `trip-cancel-terminal-read-cross-target-recovery-20261108-0120`. This fix is separate from cached-reader and publication findings.

## Reproduced trigger and change

The actual adapter with mock HTTP reproduces Cancel A accepted with a lost response, followed by GET A returning `cancelled`. Reads deliberately retain the mutation journal. A different valid command B is consequently blocked locally before its POST. The production UI previously had no caller of the existing `reviewPendingMutation` / `startNewAction` protocol; the terminal reader no longer offered Cancel A either.

The state owner now exposes “Tinjau status Trip”. This calls the existing actor-bound GET review and displays the returned Trip statuses. “Mulai tindakan baru” is a separate explicit confirmation that calls the existing local release operation. It sends no Trip command. The user then chooses the next action normally. Dismissing the review, navigating, or refreshing does not clear or replay the journal.

Local journal conflicts carry `TRIP_MUTATION_PENDING`, distinct from a server business 409. Only Trip writes using this journal opt into recovery handling. Ordinary server conflicts do not enable a blanket clear. Matching legitimate retries retain their original key. The adapter also rejects a journal that changes during review, as well as the existing generation/snapshot changes checked at confirmation. Failed reviews expose one retry action and cannot enable release. Identity changes reset UI permission.

Figma [315:18795](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=315-18795) and [315:16416](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=315-16416) were inspected as journey context. Recovery reuses the existing notice, button, error and dialog components with localized copy. This does not claim to reconcile the complete cancellation/terminal screen layout with those frames.

## Executed evidence

- Traveler development APK and test APK build, host suite, and lint: **PASS**. Host: **485 tests, zero failures/errors/skips**, including eight new adapter/state-owner cases. Lint: zero errors, 45 warnings. Partner/core are unchanged by this fix.
- Four actual-adapter tests with MockEngine: lost-response cancellation → terminal read → blocked B → successful review → explicit release → exactly one B POST; matching retry keeps the old key; failed/changed review cannot release; changed actor/generation cannot reuse the ticket.
- Four state-owner tests with the actual adapter/MockEngine: B is first blocked, review/confirmation then permits exactly one create B with the same route operation key; failed review/stale journal retain recovery; actor change removes UI permission without clearing another actor's record; ordinary server 409 does not enable local release. Lifecycle jobs are joined before resetting the host Main dispatcher.
- Emulator 5580, package `com.floopel.traveler.dev`: **16 native tests PASS in 28.760s**, comprising three new recovery interactions and 13 existing journey regressions. Review, failed review/retry, explicit confirmation and native Back were exercised. No auto-cancel or auto-create occurred. Local source/APK hashes remained frozen during this run.
- The final APK was reopened with the existing real session and the Collection reader restored using read-only navigation, pausing five seconds per flow. No real Trip was cancelled, created, or otherwise mutated. No database reset, backend/API contract, provider, credential, merge or deployment change was made.

iOS compilation/native UI, TalkBack/VoiceOver, and a real lost-response cancellation are **NOT_RUN**. Mac/Xcode is required for iOS; no live cancellation was authorized. This is scoped deterministic evidence, not proof of a broken real account or permanent blocking. The unrelated local `styles.xml` was compiled but excluded from this commit, as recorded in the manifest.

Evidence: [manifest](traveler-android-audit/trip-recovery-20261108/manifest.json), [native output](traveler-android-audit/trip-recovery-20261108/native.log), [review confirmation](traveler-android-audit/trip-recovery-20261108/review-confirmation.png), and the two JUnit reports in the same directory. JSON/XML use canonical LF; console logs retain raw bytes. SHA-256 hashes bind the stored artifacts; the source fingerprint describes local execution bytes.

# Quest Group invitation: acknowledged decisions and pending recipient reader

Date: 8 November 2026. Base: `6de7d74a70f4e047c592f2ae1fed06c8c892da0f`. This follow-up is separate from the previously scoped recovery/pagination fixes and the withdrawn layer-name title finding.

## QUEST-GROUP-DECISION-STATE-01

Finding key: `quest-group-decline-back-stale-pending-20261108-0428`.

A recipient opens pending invitation I from page two, declines, and receives the adapter-validated 200 response for I with `status=declined`. The previous success callback went Back without copying that acknowledgement into the retained list. Because ordinary reader Back now preserves its page instead of reloading page one, I remained displayed as pending.

The state owner now replaces only the matching ID with the validated server row before Back. Page, order, other rows, index and offset remain unchanged. Authorized invitation GETs use the same reconciliation, so an accepted-but-lost response remains unknown locally until an actual read establishes the resulting status. Entering recovery preserves the list; it does not clear or replay the command. Definite rejection never invents a declined row. The existing identity generation/cancellation guards discard late results after an actor change. Recovery context prefers the current invitation's Quest title.

Five new host cases use the production VM, native adapter, mock HTTP PUT, recovery store and a profile whose ID actually equals the invitation recipient. They cover item 21 decline acknowledgement, ordinary read-only Back, a rejected decision, an accepted/lost response followed by authoritative GET, and a delayed decision across actor/generation change. A successful decline sends exactly one PUT, no POST or extra page-one read. Other rows remain pending. No real invitation was decided.

## VD-QG-INV-01

Finding key: `quest-group-pending-recipient-invitation-composition-20261108-0428`.

The pending recipient consumer now follows [Figma 397:8840](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=397-8840): shared pinned 20sp toolbar, 20dp gutters, 16dp gaps, a centered 144×96dp mail SVG inside the 360×96dp slot at 400dp width, 20sp/27sp bold Quest title, 13sp/17.55sp primary context, 24sp/32.4sp bold inviter heading, 13sp note and two full-width actions of at least 48dp. The exact downloaded SVG is local and its full artwork is retained; no temporary Figma URL is used by runtime. Titles/headlines expose heading semantics and decorative artwork has no redundant spoken label.

The existing nullable `inviterUsername` supplies the heading as `@username`; a missing inviter uses neutral localized copy. Data-owned titles wrap naturally. The invitation projection does not contain location, current member count or group capacity, so those sample values are omitted. No Arif/Magelang/1-of-4 fixture values enter production and no pre-membership group GET is introduced. Profile and invitation loading/errors cannot enable a decision from stale or unknown data. Terminal and manager-readonly states retain readable statuses without recipient controls; they are relevant regressions, not a claim of full visual parity for states without supplied frames.

## Executed verification

- Traveler APK, instrumentation APK and lint: **PASS**. The full host suite reports **514 tests**, zero failures/errors/skips, including the five new production-adapter/VM cases. Lint has zero fatal/errors and 45 warnings. The last edit concerned native test entry scaling only; the production/shared and host-test inputs stayed unchanged after the full host run. Both build logs are stored. Core/Partner were not changed.
- Emulator **5580**, package `com.floopel.traveler.dev`, 400×900dp: **36 native tests PASS in 66.085s**. Breakdown: nine new invitation reader/decision tests, seven prior Quest Group recovery/pagination, four itinerary recovery, three Trip recovery and 13 journey regressions. This is a single run, not a sum of historical batches. Local source/APK hashes stayed frozen.
- New native coverage uses an actual-recipient domain fixture: mail decoder readiness/144×96dp geometry and 360×96dp slot, 20dp gutters/16dp gaps, minimum touch sizes, ID decline/Back to item 21, EN 130% decline/status, EN 200% nullable inviter/long title/scroll/pinned toolbar, delayed loading, read failure/retry, accepted, declined and manager-readonly states. It asserts no group GET before a user chooses to open that resource. The 200% scale is applied after entering the requested reader; this is not proof of every entry screen or the whole app at 200%. No live PUT/POST occurred. Ten safe native fixture screenshots were inspected.
- The existing real session was reopened and Collection restored through read-only ngrok navigation with five-second pauses per manual flow. No app-data/database reset, clock change, API/schema/policy, provider, credential, merge or deployment change was made.

iOS compilation/native UI, TalkBack/VoiceOver, dark-mode/physical-device acceptance and a real invitation decision: **NOT_RUN**. Mac/Xcode and separate device/assistive checks remain necessary. This is scoped Android evidence, not release acceptance or full Quest Group parity. The unrelated local `styles.xml` was compiled but excluded together with the unrelated `.gitignore` edit; hashes are recorded.

Evidence: [manifest](traveler-android-audit/quest-group-invitation-20261108/manifest.json), [native log](traveler-android-audit/quest-group-invitation-20261108/native.log), [pending recipient](traveler-android-audit/quest-group-invitation-20261108/pending-id.png), [declined item 21](traveler-android-audit/quest-group-invitation-20261108/declined-list-id.png), and [EN at 200%](traveler-android-audit/quest-group-invitation-20261108/missing-inviter-en-font200.png). JSON/XML use LF, console logs preserve raw bytes, and hashes identify the stored artifacts. Source fingerprints describe local execution bytes, not portable checkout line endings.

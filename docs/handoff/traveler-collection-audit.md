# Collection: Figma reconciliation, 8 November 2026

The [requested node 276:4316](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=276-4316) contains reader notes. The visual targets are its sibling Android Collection frames [276:7604](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=276-7604), 276:7728 and empty state 276:8604. Failure templates are 276:8289 and 276:8394; their entity-specific unavailable copy is localized for Collection.

## Change

The Collection route previously used the generic catalog detail: full-bleed 240dp hero, generic headline/rating, and a “nearby” list. It now uses a dedicated commonMain reader beneath the existing pinned shared toolbar:

- 20dp horizontal/top gutters and 28dp bottom padding; 16dp content gaps.
- 360×184dp cover at a 400dp viewport, 16dp corner radius. API-owned imagery remains dynamic.
- COLLECTION label, 26sp/35.1sp bold title, authoritative location coverage, and actual `intro` HTML at 16sp/21.6sp.
- Member Spot rows reuse `FloopelDiscoveryCard`: 12dp row padding/gap, 72×80dp thumbnail, top-aligned labels, 16sp title and 12sp metadata. The absent-photo tile uses the mapped CollectionsBookmark icon. Known AI reference/count values retain provenance; unknown/suppressed values show `—` instead of invented zeroes. Itinerary members retain their existing reader/navigation.
- An empty archive uses the Figma illustration and pinned “Jelajahi Spot” action. A valid Collection with zero members keeps its own title/intro. Loading, network failure/retry and unavailable content are distinct. No sample Collection is injected into production.
- The exact Figma search and unavailable SVGs are local resources with their original 184×122.667 roots and rendered geometry. The filled archive remains the existing catalog because the supplied frames describe the detail reader and empty state.

## Executed verification

Both applications build and lint: **PASS**. Lint reports zero errors, with 45 Traveler / 2 Partner warnings. The host suite reports **477 tests, zero failures/errors/skips**. Native run on 5580: **22 tests PASS in 27.205s**, comprising 10 Collection tests and the 12 Home slider regressions; these are not 34 unique tests. Local source/APK hashes stayed frozen during the run.

Collection tests cover the measured cover/row/gutter geometry, pinned toolbar during scroll, actual intro, member navigation, unknown metrics, multiple location coverage, valid zero-member reader, delayed loading, retry, 404/Community return, empty archive/Explore action, 130% long title and 200% English empty state. Screenshots and both local SVG readiness states were inspected. The host's pixel rounding is checked as local gaps, avoiding cumulative floating-point rounding assumptions.

Live read-only smoke used the existing test session through ngrok: Home article slider → Collection archive → actual Collection reader → member Spot detail → Back to the same Collection. Each manual flow paused five seconds. No app data or database was reset. The reader remains open on emulator **5580**. Text length, identity and photographs follow the API rather than Figma's example objects.

iOS build/native UI and TalkBack: **NOT_RUN**; Windows lacks Mac/Xcode, and no assistive-technology session was performed. This is scoped Android evidence, not a whole-app or release acceptance claim. The unrelated local Android `styles.xml` was compiled but excluded from the commit.

Evidence: [manifest](traveler-android-audit/collection-20261108/manifest.json), [native output](traveler-android-audit/collection-20261108/native.log), [filled fixture](traveler-android-audit/collection-20261108/collection-filled.png), [empty state](traveler-android-audit/collection-20261108/collection-empty.png), [live API reader](traveler-android-audit/collection-20261108/live-collection-api.png). Stored JSON uses LF and console logs preserve raw bytes; artifact SHA-256 hashes are recorded in each manifest. The Home evidence manifest is corrected to hash its canonical LF-stored source fingerprint file.

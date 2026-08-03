Type: grilling
Status: resolved

## Question

When the player re-runs a scan after previously toggling a detected candidate *off* (rejecting it) in an earlier scan, should that machine:

(a) stay hidden/excluded from future scan results (rejection persists until the player does something to bring it back), or
(b) reappear as a candidate every time it's still in range and recipe-matches, regardless of prior rejection?

This directly shapes the review list's UX — resolve via a short grilling session, since it's a player-experience judgment call, not a technical constraint.

## Answer

**Rejection sticks.** Toggling off a detected candidate excludes it from reappearing in future scans for this goal, rather than every scan being a clean slate. Reasoning: without this, deliberately-excluded machines (e.g. a test rig running the same recipe off to the side) would need re-rejecting on every single rescan — the point of a persistent review list is that choices persist, not get re-litigated on a loop.

**Confirmation required, but only for candidate rejection.** Toggling off a newly-detected candidate (making it stick as rejected) prompts a confirmation first, since that's the harder-to-reverse consequence. Toggling off an already-linked machine (a plain unlink) stays a single click — no confirmation — since it's already undoable via `MachineLinkHistory` (ticket 01) and confirming a routine, reversible action would just be friction.

**Un-rejecting**: a "show rejected" filter/toggle in the review panel reveals previously-rejected candidates (hidden by default, grayed out or otherwise marked when shown) so a player can re-include one without needing to remember its location and walk out to it. This keeps the default view focused on active candidates while keeping rejection reversible through the same UI.

**Manual right-click linking is unaffected.** The existing `ForemanClipboardItem` right-click link/unlink mechanism is a separate, parallel input path to the same underlying `linkedMachines` set — it isn't gated behind or aware of rejection tracking. Physically walking up and right-clicking a rejected machine simply links it, implicitly clearing its rejected status the same way toggling it back on via the "show rejected" filter would.

No code changes from this ticket — it's a pure UX/behavior decision for the future review-list widget to implement. Recorded here so that build isn't guessing at the interaction later.

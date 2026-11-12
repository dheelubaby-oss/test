# CAREPLUS-7409 / DGL-BE-02: Build the dealer's allowed PA code list from onboarded dealerships

**Layer:** Gateway. Re-layered from User Service. **Wave:** 1. **Priority:** P0. **Points:** 5 now, likely 5-8 because of the validator change.
**Shared context:** see the Index (gates G-2, G-3 apply).

## Work
1. `AllowedPaService`: candidates are the primary PA plus the IAM PAs. Then normalize (trim, uppercase), deduplicate, check the format, check country (`dealerships:hash[pa].countryCode == token c`) and check onboarding (`dealerships:hash`). Sort in numerical ascending order (D5).
2. Log excluded entries at WARN. PA codes are not believed to be secret but this needs formal confirmation from Security. Keep them out of user-facing text.
3. Initial active PA order: restored session PA if still allowed, then primary if allowed, then the first allowed secondary.
4. Write the session attributes from the Index (primary, allowed list, active, version = 1, created-at, IAM status, `ownerUserId`).
5. **Replace the token-decoder dealer check.** In `CustomJWTDecoder` (around lines 509-537) stop requiring the primary PA to be onboarded for dealers. Add a session-aware `WebFilter` that returns 403 with errorCode 1001 (existing `ErrorResponseUtil`) when the allowed list is empty.
6. Empty list: the session is established without PA context, and the first proxied call (normally `csrf-token`) returns 1001, which the UI already turns into the Dealership access required screen.
7. A `dealerships:hash` miss during a rebuild is retried and must not eject an existing session or be read as "not onboarded".
8. Feature flag: off means primary-only dealer context as today.

The matching User Service change (`WebSecurityConfig.isValidDealer`) is in BE-03a. Both validators must change together.

## Proposed acceptance criteria changes
- AC2: country check uses token `c` against the dealer country in `dealerships:hash`. Not the IAM country.
- AC5: "Dealership access required" is a Gateway 403 with 1001 on the first proxied call, not a User Service 403.
- AC7: the Gateway session holds primary, allowed list and default active PA, not User Service request context.
- AC3: remove "(Confirm with D3.)". D3 is decided: exclude and log, deny only when none remain.
- AC4: default is the first onboarded secondary in numerical order when the primary is not onboarded (D6).
- Add: hash-miss tolerance and `ownerUserId` written to the session.

## Tests
Onboarded or not, country mismatch, duplicates, malformed codes, empty list, primary not onboarded with a secondary onboarded, IAM down, Redis down (503), hash-rebuild window, flag off, single-PA regression.

## Dependencies and open items
- Depends on BE-01 and G-2. Unblocks BE-03, BE-05, BE-07, BE-08, BE-09, FE-03.
- Open: which table is the source of truth for "onboarded" (`dealers` per the meeting, to confirm); whether Partner IAM's secondary PA format is always consistent.

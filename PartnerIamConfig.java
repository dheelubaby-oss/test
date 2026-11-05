Summary

Confirm where VCP will get a dealer user's primary and secondary PA codes, and whether VCP needs roles for each PA code or only the list of PA codes.

Wave: 0 – Decide

Context

VCP must support dealer users who are linked to more than one PA (site) code. The epic says "Partner IAM will not return multiple PA codes directly in the Entra token. Instead, VCP will consume an IAM API to get the primary and secondary PA codes by userId." But the Multiple PA Code Support for VCP: Options and Discussions page (Oct 15) says "No final decision has been made."

The options are:

Option A – Entra token / custom claims (persona): IAM sets up the extra sites and personas, and they arrive in the Entra token. A walkthrough with IAM (Adityan) is still pending.

Option B – Partner IAM Dealer Identity API: VCP sends the userId and gets back the primary and secondary PA codes. Pre-prod access was requested under REQ0947250.

Decision made on Option B.

Option C – AMA/DLSRoles (the FSR+ approach): Entra handles login, and AMA/DLSRoles provides roles for each PA code. FSR+ says roles must not be combined across locations.

CAREPLUS-6960 confirmed that today's Entra token does not include the secondary PA code (test user k-abern6, checked in dev, stage and prod).

Acceptance criteria

Confirm with Partner IAM and IAM which source is recommended (Option A, B or C).

Confirm whether VCP needs roles for each PA code, or only the list of PA codes.

Confirm whether the Dealer Identity API and AMA/DLSRoles are the same source, related sources or separate ones.

Get the API contract (Swagger), a sample request and response, and P95/P99 latency.

Identify the field that marks the primary PA code versus the secondary ones.

Check the stated rate limit (100–200 calls/min) against VCP's user volumes, including the extra calls for rechecking access during a session.

Check the status of REQ0947250, and make sure it includes the pre-prod and prod client IDs.

Agree how to test with dealer users who have more than one PA code, given that VCP uses the production user ID in every environment.

Define what happens if the source is unavailable, and how often access is rechecked.

Record the decision in Confluence, and update CAREPLUS-7182 and the Dealer Group Login https://ford.atlassian.net/browse/CARE-121 design page to match.

Note:

This applies to dealer users only. The admin flow does not change.

This spike blocks DGL-BE-01 (retrieve primary and secondary PA codes at login).

Other information

Contacts: Srinivasulu T (Partner IAM / Dealer Identity API), Adityan (IAM persona and multi-site rollout), Suman Gumudavelly (FSR+ AMA/DLSRoles approach), Anna Bobbala (EA).

Suggested assignee: J Shanmugapriya (leading the Partner IAM / IAM engagement).

Story points: TBD (refinement).

Related: CAREPLUS-6960 (Accepted), CAREPLUS-6961 (Defined), REQ0947250 (a Partner IAM request outside Jira).


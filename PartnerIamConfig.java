### Acceptance Criteria – DGL-SP-01

1. VCP retrieves the dealer user's primary and secondary PA codes using the Partner IAM Dealer Identity API (Option B).

2. VCP obtains the required access token through the Partner IAM token API before calling `groups-for-user`.

3. VCP extracts primary and secondary PA codes from the Partner IAM API response.

4. VCP validates the retrieved PA codes against existing dealer onboarding information and identifies the permitted PA codes.

5. VCP maintains the permitted PA-code list without requiring separate roles for individual PA codes.

6. Gateway stores the permitted PA codes and initial active PA code in the Redis session against the authenticated user.

7. The `/auth/me` API returns the permitted PA-code list and the currently active PA code.

8. Dealers can switch between their permitted PA codes without logging in again.

9. Gateway validates the selected PA code against the user's permitted PA list and propagates the active PA context to User Service for authorization and PA-specific data filtering.

10. If no permitted PA codes are available, the session is removed and the user is redirected to the existing Dealership Access Required page.

11. If Partner IAM is unavailable or the integration fails, the user is redirected to the existing error page.

12. The solution supports appropriate Redis TTL, PA-access revalidation, API rate limits and error handling.

13. Multi-PA dealer scenarios are documented, along with API contracts, sample requests/responses, performance details and environment configurations.

14. The agreed Option B architecture and sequence flow are documented in Confluence and reflected in CAREPLUS-7182 and CARE-121.

**Note:** This requirement applies to dealer users only. The existing admin login and authorization flow remains unchanged.

# CAREPLUS-7411 / DGL-BE-01: Implementation and unit test plan

**Source:** [DGL-BE-01_CAREPLUS-7411_Retrieve_PA_Codes.md](DGL-BE-01_CAREPLUS-7411_Retrieve_PA_Codes.md)
**Layer:** Gateway (`service-care-gateway-service`)
**Related analysis:** [dgl-analysis-gateway.md](dgl-analysis-gateway.md) (G2 session, G3 user types, G5.4 resilience, G6 tests/config)
**Status:** Plan only. No production code has been changed.

---

## 0. Summary

At `/dashboard/auth/callback`, after the Entra ID code exchange, the gateway will:

1. Check the feature flag. If it is off, nothing changes.
2. Verify the access token with the existing `ReactiveJwtDecoder`, then read `fordUserType`, the user ID claim (default `cdsid`), `fordSiteCode` and `c`.
3. Call the Partner IAM API through a `PaCodeSource`, but only for `Dealer` users.
4. Normalize the response and store the primary and secondary PA codes in the Spring Session, using JDK-only types.
5. If IAM fails, store `partnerIamStatus = PRIMARY_ONLY_FALLBACK`, log an alertable event code, and still allow the login. BE-02 decides whether the primary is onboarded.

This is a **login-time outbound client call**, not a proxy route. It belongs in Java, the same way the Entra token exchange does. The rule "routes only in properties" does not apply here.

---

## 1. Current-state findings that shape the design

| # | Finding | Evidence | Design consequence |
|---|---|---|---|
| F1 | `handleCallback` exchanges the code and stores `OAuthSession`. It **does not decode or verify claims** at callback. Verification happens later, per request, in `CustomJWTDecoder`. | `AuthServiceImpl.handleCallback` / `storeTokensInSession`; `WebSecurityConfig.jwtDecoder()` | To use a *verified* identity (Work item 2), inject `ReactiveJwtDecoder` and decode the access token at callback, **only when the flag is on**. There is no circular dependency: `WebSecurityConfig` does not depend on `AuthService`. |
| F2 | `CustomJWTDecoder.hasValidUserType` already **rejects** a missing, blank or unrecognized `fordUserType` (returns `false`, which becomes 401 on every API call). The `Dealer` match is case-sensitive (`equals`). | `CustomJWTDecoder.java` ~476-505 | The `[DECISION]` in Work item 4 is effectively already decided as **deny**. Sending unknown users to the "admin flow" would contradict the decoder, so the user would fail on the first API call anyway. Use exact `equals("Dealer")` for consistency. (`getUserInfo` uses `equalsIgnoreCase`; leave it unchanged.) |
| F3 | A dealer whose primary `fordSiteCode` is not in `dealerships:hash` is rejected by the decoder with `ForbiddenException`. | `CustomJWTDecoder.isValidFordSiteCodeAndCountryCode` | If we decode at callback, a dealer whose primary is not onboarded fails **at callback** instead of on the first API call. Login still fails either way. The "primary not onboarded, secondary onboarded" case needs BE-02 to relax the decoder. Record this as a dependency; do not solve it in BE-01. |
| F4 | Spring Session uses **JDK serialization**, and every request deserializes every session field. A read failure becomes 503. | `SessionConfig.java:58`; `SessionAuthenticationFilter.java:137-143` | New session attributes must use **JDK types only** (`String`, `ArrayList<String>`, `Long`). A custom class would break older revisions during a rolling deploy or rollback. |
| F5 | `WebClientConfig.webClientBuilder()` is a **singleton** builder shared with `AuthServiceImpl`, and it is proxy-enabled in deployment. | `WebClientConfig.java:76-80`; `application.properties` (`webclient.proxy.*`) | `PartnerIamClient` must call `webClientBuilder.clone()` before customizing, to avoid mutating the shared builder. Whether IAM must bypass the proxy is an open item (OI-7). |
| F6 | There is no Resilience4j, Spring Cloud CircuitBreaker, or feature-flag framework on the runtime classpath. `reactor-core` 3.7.18 is present. | `.\gradlew.bat dependencies --configuration runtimeClasspath`; analysis G5.4 and G6.2 | **No new dependencies.** Timeouts and retry use `reactor-core` (`Mono.timeout`, `reactor.util.retry.Retry`). Use a plain `@ConfigurationProperties` boolean as the flag, following the `webclient.proxy.enabled` precedent. |
| F12 | The existing SAGE integration in `service-care-userservice` is the team's reference pattern for outbound API calls. Its API calls (OpenFeign) use a connect timeout and a read timeout, with **no retry and no circuit breaker**. Its OAuth2 client-credentials token call caches the token in memory and uses `TokenRetryInterceptor`, which retries **only** I/O errors, timeouts and 5xx responses, with a **fixed** backoff (`sage.token.timeout-ms`, `sage.token.max-retries` = total attempts, `sage.token.backoff-ms`). Errors are mapped in one place (`SageUtils.handleFeignException`). | user-service `clientconfig\SAGEClient.java:33`, `FeignTokenConfig.java:35-80`, `TokenRetryInterceptor.java`, `SAGETokenFeignInterceptor.java`, `application.yml` (`spring.cloud.openfeign.client.config.sage-application-service`) | The IAM client follows the same pattern (D-8). Feign and `RestClient` are blocking, so the gateway uses the reactive equivalents: `WebClient` with Netty connect and response timeouts, and `Retry.fixedDelay(...).filter(transient)`. **Never use `Thread.sleep` on the Netty event loop.** |
| F7 | `BearerTokenSessionFilter` creates sessions for direct bearer tokens **without** going through the callback. | `BearerTokenSessionFilter.java` ~101-125 | Those sessions never get PA attributes. BE-02 must treat "attributes absent" the same as flag off (primary only, from the token). This is documented, not changed, in BE-01. |
| F8 | Error codes follow `VCPGWERR{category}{module}{seq}`. Modules `00`-`09` and `90` are in use. `APIErrorCodeTest` enforces the pattern and metadata. | `APIErrorCode.java`; `APIErrorCodeTest.java` | Use the new module **`10`** for DGL PA codes. |
| F9 | Alerting uses GCP log-based metrics on `[EVT_*]` prefixes in `CommonConstant`. No log-metric Terraform exists in this repo. | `CommonConstant.java` (`EVT_*`); `infrastructure\` | Add `[DGL_PA_*]` event prefixes. Alert policies are created outside this repo (ops task). |
| F10 | `build.gradle` sets `test.ignoreFailures = true`. | `build.gradle:53` | A green Gradle exit does **not** mean tests passed. Check `build\test-results\test\*.xml` or the HTML report. |
| F11 | Dealer and site codes must be masked in logs. | Copilot instructions; `GatewayServiceFilter` | Never log PA code values at INFO. Log counts, plus masked values only at DEBUG. |

---

## 2. Decisions required (with recommendations)

| ID | Decision | Recommendation | Rationale |
|---|---|---|---|
| D-1 | Missing or unrecognized `fordUserType` (AC1) | **Deny and log** (fail closed). Throw `AuthFlowException.UnsupportedUserTypeException`, invalidate the session, and redirect to `postLoginFailureUri`. Applies only when the flag is on. | Consistent with F2. The admin-flow option is not viable because the decoder rejects these users anyway. |
| D-2 | Identity used as `userId` | Read it from a configurable claim, `dgl.partner-iam.user-id-claim`, defaulting to `cdsid`. | OI-1 (CDSID, CommonName or `oid`) can then be resolved by changing configuration, not code. |
| D-3 | IAM primary differs from token `fordSiteCode` | The **token `fordSiteCode` is the session primary**. The IAM primary is kept as a regular PA in the list. Log `[DGL_PA_PRIMARY_MISMATCH]` at WARN. | The token claim is already what the decoder authorizes. This avoids two competing sources of truth until OI-2 is answered. |
| D-4 | IAM returns 200 with zero PAs | Status `VERIFIED`, list = [token primary]. | IAM has positively confirmed that there are no secondaries. This does not grant extra access. |
| D-5 | Response `userId` differs from the requested `userId` | Treat it as an invalid payload, use `PRIMARY_ONLY_FALLBACK`, and log at ERROR. | Never accept data for a different identity. |
| D-6 | Decode at callback with the full `CustomJWTDecoder` (signature, issuer, client and user-type checks) | **Yes**, when the flag is on. | Meets "verified identity". F3 is the accepted side effect: a dealer whose primary is not onboarded already fails today. |
| D-7 | Stub allowed in deployed environments | Only when `dgl.partner-iam.stub.allow-in-deployment=true`. This is needed in dev until G-1 is available. | Prevents stub data from being used in prod by accident. |
| D-8 | Resilience approach (Work item 5) | **Follow the SAGE pattern (F12):** connect and read timeouts, plus a bounded fixed-delay retry on transient errors only, plus a hard total timeout. **No circuit breaker** and no new dependencies. | Decided by the team (use the same pattern as the SAGE calls). Work item 5 lists a circuit breaker, so the **story owner must acknowledge this change** (OI-10). The risk is small: IAM is called only at login, volume is far below the 100-200 calls per minute limit, the total timeout caps how long a login can wait during an outage, and every failure falls back to primary-only. |

---

## 3. Target design

### 3.1 Callback sequence (flag on)

```
AuthController.processCallback
 └─ AuthServiceImpl.handleCallback(code, state, session)
     ├─ validateLoginFlow                      (unchanged)
     ├─ exchangeCodeForTokens                  (unchanged)
     ├─ buildSession(tokenResponse)            (unchanged; extracted so OAuthSession is available)
     ├─ dealerPaCodeService.resolve(accessToken)          ◄── NEW
     │     ├─ flag off            → Mono.empty()  (no decode, no call)
     │     ├─ jwtDecoder.decode(accessToken)  → verified Jwt
     │     ├─ classify fordUserType
     │     │     ├─ Dealer        → continue
     │     │     ├─ employee/SharedMBX → Mono.empty()  (no call; regression-tested)
     │     │     └─ missing/unknown → error UnsupportedUserTypeException (D-1)
     │     ├─ userId = claim[user-id-claim]; blank → UnsupportedUserType/identity error
     │     ├─ paCodeSource.fetchPaCodes(userId)   (SAGE pattern: timeouts + fixed-delay retry)
     │     ├─ PaCodeNormalizer.normalize(result, tokenPrimary, userId)
     │     └─ on PartnerIamException → DealerPaContext.fallback(tokenPrimary)
     └─ storeTokensInSession(oauthSession, Optional<DealerPaContext>, session)
           ├─ put oauthSession, TTL, postLogoutUri    (unchanged)
           ├─ DealerPaSessionAttributes.write(session, ctx)  ◄── NEW
           ├─ remove loginFlow                        (unchanged)
           └─ session.save()                          (single save, unchanged)
```

Key points:

- The PA lookup runs **before** `session.save()` and before the success redirect, so the session the browser receives is already complete.
- IAM failures are **never** propagated as errors. They become a fallback context, so the login succeeds.
- Only `UnsupportedUserTypeException` and decoder failures abort the login. They reach the existing `onErrorResume(AuthFlowException.class)` and generic handlers in `AuthController.processCallback`, which invalidate the session and redirect to the failure page.

### 3.2 Internal contract (Work item 1, AC4)

```java
public record PaCode(String paCode) implements Serializable {}
public record PaCodeResult(String userId, String primaryPaCode, List<PaCode> paCodes) {}
```

The IAM country is intentionally absent. Country always comes from the token `c` claim.

### 3.3 Session attributes (AC5: the session is the cache)

All keys are defined in `DealerPaSessionAttributes` and stored as **JDK types only** (F4):

| Key | Type | Value |
|---|---|---|
| `dgl.partnerIamStatus` | `String` | `VERIFIED` or `PRIMARY_ONLY_FALLBACK` (the enum `name()`) |
| `dgl.primaryPaCode` | `String` | Token `fordSiteCode` (D-3) |
| `dgl.paCodes` | `ArrayList<String>` | Primary first, then deduplicated secondaries. Fallback = `[primary]` |
| `dgl.paCodesRetrievedAt` | `Long` | Epoch millis, used by the G-3 revalidation policy (a later story) |

When the attributes are absent (flag off, non-dealer, or bearer-token session), consumers treat the user as primary-only from the token. `DealerPaSessionAttributes.read(session)` returns `Optional.empty()` in that case. Malformed values (wrong type) also return `Optional.empty()` with a WARN, and never throw.

### 3.4 Resilience (Work item 5): SAGE pattern, no circuit breaker (D-8)

This mirrors the SAGE integration (F12), using reactive building blocks that already ship in `reactor-core`, `reactor-netty` and `spring-webflux`. **No new dependencies.**

| SAGE (user-service, blocking) | Partner IAM (gateway, reactive equivalent) |
|---|---|
| Feign `connect-timeout` / `read-timeout` per client | Dedicated Netty `HttpClient`: `option(CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)` and `responseTimeout(readTimeoutMs)` |
| Feign API call: no retry | IAM API call: bounded fixed-delay retry on transient errors only. Work item 5 asks for bounded retry, and the retry semantics are copied from SAGE's `TokenRetryInterceptor`. |
| `TokenRetryInterceptor`: retries I/O errors, `ResourceAccessException` (timeout) and 5xx; fixed `Thread.sleep` backoff; `max-retries` = total attempts | `Retry.fixedDelay(maxRetries - 1, backoff).filter(PartnerIamException::isTransient)`. Non-blocking delay; same "total attempts" meaning. |
| Client-credentials token cached in memory (`InMemoryOAuth2AuthorizedClientService`) | `PartnerIamTokenProvider` caches the token in memory until `expiresAt - 60s` (4.5) |
| `SageUtils.handleFeignException`: one place maps errors | `PartnerIamClient.mapError(...)`: one place maps errors to `PartnerIamException.Reason` |
| No circuit breaker | No circuit breaker |

Composition:

```java
tokenProvider.getToken()                                  // has its own SAGE-style retry (4.5)
    .flatMap(token -> Mono.defer(() -> callIam(token, userId))   // Netty connect + read timeouts per attempt
            .onErrorMap(this::mapError)                   // → PartnerIamException(reason)
            .retryWhen(Retry.fixedDelay(maxRetries - 1, backoff)
                    .filter(PartnerIamException::isTransient)       // I/O, timeout, 5xx only (as SAGE); never 4xx or payload errors
                    .onRetryExhaustedThrow((spec, rs) -> rs.failure())))
    .timeout(totalTimeout)                                // hard cap on extra login latency (gateway-specific guard)
```

The token fetch sits **outside** the API retry so the two retry loops do not multiply.

Defaults use SAGE-style property names (`*-ms` integers, `max-retries` = total attempts). The values are shorter than SAGE's because the call is on the interactive login path. Pre-prod P95 is about 1 s; OI-4 will tune these.

| Property | Default | SAGE equivalent |
|---|---|---|
| `dgl.partner-iam.connect-timeout-ms` | `2000` | `connect-timeout: 3000` |
| `dgl.partner-iam.read-timeout-ms` | `2000` | `read-timeout: 5000` |
| `dgl.partner-iam.max-retries` (total attempts) | `2` | n/a (Feign does not retry) |
| `dgl.partner-iam.backoff-ms` | `200` | n/a |
| `dgl.partner-iam.token.timeout-ms` | `2000` | `sage.token.timeout-ms: 2000` |
| `dgl.partner-iam.token.max-retries` (total attempts) | `2` | `sage.token.max-retries: 3` |
| `dgl.partner-iam.token.backoff-ms` | `500` | `sage.token.backoff-ms: 1000` |
| `dgl.partner-iam.total-timeout-ms` | `6000` | n/a (gateway-only cap) |

4xx responses and invalid payloads are **never retried**, because they are configuration, identity or contract problems. They **are** logged with alertable codes. Every failure, after retries, falls back to primary-only (4.8).

### 3.5 Error codes and log events

New `APIErrorCode` entries (module `10`, F8):

| Code | Status / category | Meaning |
|---|---|---|
| `VCPGWERR31001` | `FORBIDDEN` / AUTHORIZATION | Missing or unknown `fordUserType` at callback (D-1) |
| `VCPGWERR11001` | `BAD_GATEWAY` / VALIDATION | IAM payload malformed, identity mismatch, or empty after normalization |
| `VCPGWERR71001` | `SERVICE_UNAVAILABLE` / DEPENDENCY | IAM 5xx or I/O error |
| `VCPGWERR71002` | `GATEWAY_TIMEOUT` / DEPENDENCY | IAM timeout (connect, read or total) |
| `VCPGWERR71004` | `BAD_GATEWAY` / DEPENDENCY | IAM 4xx (auth, config, not-found or rate limit) |
| `VCPGWERR71005` | `SERVICE_UNAVAILABLE` / DEPENDENCY | IAM client-credentials token acquisition failed |
| `VCPGWERR81001` | `INTERNAL_SERVER_ERROR` / INTERNAL | Unexpected error during PA resolution (still falls back) |

New `CommonConstant` event prefixes (each one is a log-based metric target):

| Constant | Level | When |
|---|---|---|
| `EVT_DGL_PA_RESOLVED` = `[DGL_PA_RESOLVED]` | INFO | Success. Logs the count of PAs only. |
| `EVT_DGL_PA_FALLBACK` = `[DGL_PA_IAM_FALLBACK]` | WARN | Any fallback. Includes the reason, error code and number of attempts. **Alert on this.** With no circuit breaker (D-8), a sustained rise in this metric is the outage signal. |
| `EVT_DGL_PA_PRIMARY_MISMATCH` = `[DGL_PA_PRIMARY_MISMATCH]` | WARN | D-3 |
| `EVT_DGL_PA_SANITIZED` = `[DGL_PA_SANITIZED]` | WARN | Blank, invalid, duplicate or over-cap entries were dropped. Counts only. |
| `EVT_DGL_UNSUPPORTED_USER_TYPE` = `[DGL_UNSUPPORTED_USER_TYPE]` | WARN | D-1 |

**Logging rules (Work item 7, F11):** never log the client secret, IAM bearer token, Entra tokens, `Authorization` headers, or raw response bodies. PA codes and user IDs are logged **masked** (`****23`) and at DEBUG only. Truncate any IAM error body to 0 chars by default, because the bodies may contain PII.

---

## 4. Implementation tasks

Paths are relative to `src\main\java\com\careplus\gatewayservice\` unless stated otherwise. The tasks follow the existing package layering (`configs`, `service`, `serviceimpl`, `model`, `exception`, `util`).

### 4.1 Build (`build.gradle`)

- **No changes.** Everything needed is already on the runtime classpath (F6): `reactor-core` (`Mono.timeout`, `reactor.util.retry.Retry`), `reactor-netty` (connect and response timeouts) and `spring-webflux` (`WebClient`).
- No new test dependencies. WebClient is stubbed with `ExchangeFunction` (see 5.1).
- The SAGE token code uses `spring-security-oauth2-client` (`AuthorizedClientServiceOAuth2AuthorizedClientManager`), which is **not** on the gateway classpath. The gateway copies its *behavior* (in-memory cache plus retry on transient errors) in a small `PartnerIamTokenProvider` that uses the existing `WebClient`. This is the same approach `AuthServiceImpl.exchangeCodeForTokens` already uses for Entra.

### 4.2 Configuration properties

**New:** `configs\dgl\PartnerIamProperties.java`, a `@ConfigurationProperties("dgl.partner-iam")` `@Validated` record or class:

- `enabled` (boolean, default `false`): **the feature flag (Work item 8)**
- `source` (enum `IAM | STUB`, default `IAM`)
- `baseUrl`, `paCodesPath`, `tokenUrl`, `clientId`, `clientSecret`, `scope`
- `userIdClaim` (default `cdsid`)
- `connectTimeoutMs`, `readTimeoutMs`, `maxRetries` (total attempts, 1-3), `backoffMs`, `totalTimeoutMs` (SAGE-style names; defaults in 3.4)
- `token.timeoutMs`, `token.maxRetries` (total attempts, 1-3), `token.backoffMs` (mirror `sage.token.*`)
- `maxPaCodes` (default `100`, range 1-500)
- `stub.allowInDeployment` (default `false`), plus `stub.users` (`Map<String, StubUser>`: `primary`, `paCodes`, `behavior` = `OK | TIMEOUT | ERROR_5XX | ERROR_4XX | MALFORMED`)
- Override `toString()` to **mask `clientSecret`**.

**New:** `configs\dgl\PartnerIamConfig.java` (`@Configuration`, `@EnableConfigurationProperties(PartnerIamProperties.class)`):

- Startup validation (`@PostConstruct` or `InitializingBean`):
  - If `enabled && source==IAM` and any of `baseUrl`, `tokenUrl`, `clientId` or `clientSecret` is blank, **fail fast** with `IllegalStateException`. The message names the property, never its value.
  - If `source==STUB`, the `deployment` profile is active, and `!stub.allowInDeployment`, fail fast (D-7).
  - If `source==STUB`, log WARN `"Partner IAM STUB source active"` at startup.
- `@Bean("partnerIamWebClient") WebClient` built from `webClientBuilder.clone()` (F5), with the base URL and a dedicated `ReactorClientHttpConnector` (`option(CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)` and `responseTimeout(readTimeoutMs)`, the reactive equivalent of SAGE's Feign `connect-timeout`/`read-timeout`). The token provider gets its own connector with `token.timeoutMs` for both, like `FeignTokenConfig`. Proxy settings are inherited only if OI-7 confirms they are needed.

**Properties files:**

| File | Changes |
|---|---|
| `src\main\resources\application.properties` | Commented block under a `DGL PARTNER IAM` header: `dgl.partner-iam.enabled=false`, the timeout and retry defaults from 3.4, `user-id-claim=cdsid`, `max-pa-codes=100`. |
| `application-deployment.properties` | `dgl.partner-iam.enabled=${sm://dgl_partner_iam_enabled}` (or a plain per-environment value), and `base-url`, `pa-codes-path`, `token-url`, `client-id`, `client-secret`, `scope` as `${sm://partner_iam_*}`. `source=IAM`. |
| `application-local.properties` | `enabled=true`, `source=STUB`, with sample stub users (one multi-PA dealer, one timeout user, one malformed user). |
| `application-test.properties` | `enabled=false`, `source=STUB`, so Spring context tests do not need Secret Manager. |

### 4.3 Model

| New file | Content |
|---|---|
| `model\pa\PaCode.java` | `record PaCode(String paCode) implements Serializable` |
| `model\pa\PaCodeResult.java` | `record PaCodeResult(String userId, String primaryPaCode, List<PaCode> paCodes)`, with a compact constructor that copies the list immutably |
| `model\pa\PartnerIamStatus.java` | `enum { VERIFIED, PRIMARY_ONLY_FALLBACK }` |
| `model\pa\DealerPaContext.java` | `record (PartnerIamStatus status, String primaryPaCode, List<String> paCodes, Instant retrievedAt)`, plus factories `verified(...)` and `fallback(primary)`. **Never stored directly**; see 4.7. |
| `model\pa\PartnerIamPaCodesResponse.java` | **Provisional** Jackson DTO for the IAM payload, `@JsonIgnoreProperties(ignoreUnknown = true)`. Field names are confirmed against the Swagger contract at G-1 (OI-2). |

### 4.4 Exceptions

- **New:** `exception\PartnerIamException.java` extends `RuntimeException` and implements `GatewayApiException`. It has an enum `Reason { TIMEOUT, SERVER_ERROR, CLIENT_ERROR, INVALID_PAYLOAD, IDENTITY_MISMATCH, TOKEN_ERROR, UNEXPECTED }`, each mapped to a 3.5 error code, plus `boolean isTransient()`. Like SAGE's `TokenRetryInterceptor`, this is true **only** for `TIMEOUT` and `SERVER_ERROR` (I/O errors and 5xx). The message must never contain response bodies or tokens.
- **Modify:** `exception\AuthFlowException.java` to add `UnsupportedUserTypeException`, which uses `VCPGWERR31001`.
- **Modify:** `constant\APIErrorCode.java` to add the 3.5 codes, each with an `/** Origin: ... */` Javadoc.
- **Modify:** `constant\CommonConstant.java` to add the `EVT_DGL_*` constants from 3.5.

### 4.5 `PaCodeSource` and implementations (Work item 1)

- **New:** `service\PaCodeSource.java`

  ```java
  /** Source of a dealer user's primary and secondary PA codes. */
  public interface PaCodeSource {
      /** @param userId verified identity from the access token; never browser-supplied. */
      Mono<PaCodeResult> fetchPaCodes(String userId);
  }
  ```

- **New:** `serviceimpl\StubPaCodeSource.java`, annotated `@ConditionalOnProperty(name="dgl.partner-iam.source", havingValue="STUB")`.
  - It looks up `stub.users[userId]` and simulates `behavior` (TIMEOUT means `Mono.never()`, which the caller's timeout cuts off).
  - An unknown user returns an empty list with a null primary, so the token primary is used.
  - It must still pass through the same normalizer and fallback path, so dev exercises the real logic.

- **New:** `serviceimpl\PartnerIamTokenProvider.java`, conditional on `source=IAM`.
  - Performs a client-credentials grant against `tokenUrl`, with the secret sent in the form body (`client_secret`).
  - Caches the token in an `AtomicReference<CachedToken>` until `expiresAt - 60s`. This is the reactive equivalent of SAGE's `InMemoryOAuth2AuthorizedClientService`. Concurrent refreshes are de-duplicated with a shared cached `Mono` (`Mono.defer(...).cache(...)`, reset on error).
  - Retry follows SAGE's `TokenRetryInterceptor`: up to `token.maxRetries` total attempts with a fixed `token.backoffMs` delay, on I/O errors, timeouts and 5xx only. A 4xx is not retried. The delay is `Retry.fixedDelay`, never `Thread.sleep`.
  - Any failure after retries becomes `PartnerIamException(TOKEN_ERROR)`. Nothing from the token or body is ever logged.
  - *If G-1 shows IAM is fronted by Apigee with a different auth scheme, only this class changes.*

- **New:** `serviceimpl\PartnerIamClient.java`, which implements `PaCodeSource` and is conditional on `source=IAM`.
  - Request: `GET {baseUrl}{paCodesPath}` with the `userId` as a URI template variable (URL-encoded by the framework, never concatenated), `Authorization: Bearer <token>` and `Accept: application/json`. This is provisional until the Swagger contract is available.
  - `onStatus(4xx)` maps to `CLIENT_ERROR`, including 429, as in SAGE. On a 401, invalidate the cached token so the next login fetches a new one.
  - `onStatus(5xx)` maps to `SERVER_ERROR`. I/O errors (`WebClientRequestException`, `ConnectException`) also map to `SERVER_ERROR`.
  - A Jackson decode error maps to `INVALID_PAYLOAD`. A Netty `ReadTimeoutException`, a `ConnectTimeoutException` or the total `TimeoutException` maps to `TIMEOUT`.
  - All of this mapping lives in one `mapError(Throwable)` method, the same idea as `SageUtils.handleFeignException`.
  - Apply the resilience chain from 3.4.
  - Map the DTO to `PaCodeResult`. Mapping only; validation is done in the normalizer.

### 4.6 Normalizer (pure logic, heavily unit-tested)

**New:** `util\PaCodeNormalizer.java`, a final class with static or stateless methods:

```
DealerPaContext normalize(PaCodeResult raw, String requestedUserId, String tokenPrimary, int maxPaCodes, Clock clock)
```

Rules, applied in order:

1. If `raw == null` or `raw.paCodes() == null`, throw `PartnerIamException(INVALID_PAYLOAD)`.
2. If `raw.userId()` is non-blank and does not equal `requestedUserId` (case-insensitive, trimmed), throw `IDENTITY_MISMATCH` (D-5).
3. For each entry: skip null entries, trim, uppercase (`Locale.ROOT`), drop blanks, and drop values not matching `CommonConstant.REGEX_SITE_CODE` (`^[A-Z0-9]+$`) or longer than 10 characters (aligned with `NotificationEventDto`).
4. Deduplicate while preserving order (`LinkedHashSet`).
5. Primary = `tokenPrimary` (normalized). If the IAM primary is non-blank and differs, log `EVT_DGL_PA_PRIMARY_MISMATCH` (D-3). Remove the primary from its current position and put it first.
6. Cap the list at `maxPaCodes`, with the primary always kept. Log `EVT_DGL_PA_SANITIZED` with counts if anything was dropped.
7. Return `DealerPaContext.verified(primary, list, clock.instant())`.

### 4.7 Session attributes helper

**New:** `model\pa\DealerPaSessionAttributes.java`, a final utility class:

- Key constants: `ATTR_STATUS`, `ATTR_PRIMARY`, `ATTR_PA_CODES`, `ATTR_RETRIEVED_AT`.
- `static void write(WebSession, DealerPaContext)` writes `String`, `new ArrayList<>(list)` and `Long`, and **removes stale keys first** to cover re-login on the same session.
- `static Optional<DealerPaContext> read(WebSession)` is type-safe and never throws. A wrong type gives `Optional.empty()` plus a WARN. This is for BE-02 and later consumers.
- `static void clear(WebSession)`.

### 4.8 Orchestration service

- **New:** `service\DealerPaCodeService.java`:

  ```java
  /** Resolves PA context for the user identified by a freshly issued access token. */
  Mono<DealerPaContext> resolve(String accessToken);   // empty = no PA context to store
  ```

- **New:** `serviceimpl\DealerPaCodeServiceImpl.java`. Constructor dependencies: `PartnerIamProperties`, `ReactiveJwtDecoder`, `PaCodeSource`, and `Clock` (a `Clock.systemUTC()` bean for testability).

  ```
  if (!props.enabled()) return Mono.empty();
  return jwtDecoder.decode(accessToken)                   // errors propagate → login denied (D-6)
     .flatMap(jwt -> {
        String userType = jwt.getClaimAsString(USER_TYPE);
        if (DEALER_USER.equals(userType)) return resolveDealer(jwt);
        if (isKnownNonDealer(userType)) return Mono.empty();          // no IAM call
        log WARN EVT_DGL_UNSUPPORTED_USER_TYPE (sanitized type, no PII);
        return Mono.error(new UnsupportedUserTypeException(...));     // D-1
     });

  resolveDealer(jwt):
     userId  = jwt.getClaimAsString(props.userIdClaim());             // verified claim only
     primary = jwt.getClaimAsString(SITE_CODE);
     if blank(userId) || blank(primary) → Mono.error(UnsupportedUserTypeException / identity)  // fail closed
     return paCodeSource.fetchPaCodes(userId)
        .timeout(props.totalTimeout())                                  // guards stub/other sources too
        .map(raw -> PaCodeNormalizer.normalize(raw, userId, primary, props.maxPaCodes(), clock))
        .doOnNext(ctx -> log INFO EVT_DGL_PA_RESOLVED count=ctx.paCodes().size())
        .onErrorResume(ex -> {                                          // Work item 6
            log WARN EVT_DGL_PA_FALLBACK reason, code (no values)
            return Mono.just(DealerPaContext.fallback(primary, clock.instant()));
        });
  ```

  - `isKnownNonDealer` reuses `AuthProviderSettings` (the allowed employee types and `SharedMBX`), so the classification stays in sync with `CustomJWTDecoder`.
  - The fallback context is `[primary]` with `PRIMARY_ONLY_FALLBACK`. **Secondaries are never added on any failure path.** BE-02 applies the "only if the primary is onboarded" rule (AC6).

### 4.9 `AuthServiceImpl` integration (Work item 2)

Keep the change surgical:

- Add `DealerPaCodeService` to the constructor: `AuthServiceImpl(WebClient.Builder, DealerPaCodeService)`.
- Change `handleCallback` to:

  ```java
  return validateLoginFlow(state, session)
          .flatMap(arr -> exchangeCodeForTokens(authCode, arr[0], arr[1]))
          .map(this::buildSession)
          .flatMap(oauthSession -> dealerPaCodeService.resolve(oauthSession.getAccessToken())
                  .map(Optional::of)
                  .defaultIfEmpty(Optional.empty())
                  .flatMap(paCtx -> storeTokensInSession(oauthSession, paCtx, session)));
  ```

- In `storeTokensInSession`, accept the pre-built `OAuthSession` and `Optional<DealerPaContext>`. Call `DealerPaSessionAttributes.write` when present and `clear` when absent, before the existing single `session.save()`.
- Update the Javadoc for the class, `handleCallback` and `storeTokensInSession`.

### 4.10 `AuthController` (optional, for clarity)

Add `onErrorResume(AuthFlowException.UnsupportedUserTypeException.class, ...)` **before** the generic `AuthFlowException` handler in `processCallback`. It logs at WARN, not ERROR, and uses the same `invalidateSessionAndRedirectToFailure(...)` call. There is no behavior change other than the log level and message.

### 4.11 Documentation

- `.github\copilot-instructions.md` and `AGENTS.md`: add a "Partner IAM / DGL PA codes" bullet covering the flag, `PaCodeSource`, the JDK-only session attribute rule, and the "not a proxy route" note.
- `README.md`: add the new properties and the local stub usage.
- OpenAPI: **no change**. No endpoint changes; `/auth/me` is not extended in BE-01.

### 4.12 Rollout (rolling-deploy safety)

1. Deploy with `enabled=false` in all environments. The new code path is inert.
2. Once **all** revisions run the new build, enable the flag in dev (stub, if G-1 is still pending), then QA.
3. Rollback note: older builds can still read the JDK-only attributes (F4), so rollback is safe.
4. Ops: create GCP log-based metrics and an alert on the **rate** of `[DGL_PA_IAM_FALLBACK]`, split by `reason`. With no circuit breaker (D-8), this is the only signal that IAM is down.

### 4.13 Suggested PR slicing

| PR | Content | Behavior change |
|---|---|---|
| 1 | Properties, model, exceptions, error codes, `PaCodeSource`, stub, normalizer, session helper, plus their tests | None (nothing is wired) |
| 2 | `DealerPaCodeServiceImpl` and the `AuthServiceImpl`/`AuthController` integration, plus tests | Flag off by default. Local uses the stub. |
| 3 | `PartnerIamClient` and token provider (SAGE-pattern timeouts and retry), plus tests (after the G-1 Swagger contract) | Flag off |
| 4 | Deployment `sm://` configuration, docs, enable in dev | Dev only |

---

## 5. Unit test plan

### 5.1 Conventions and tooling (no new test dependencies)

- JUnit 5, Mockito (`@ExtendWith(MockitoExtension.class)`), AssertJ, Reactor `StepVerifier`, and `StepVerifier.withVirtualTime` for the total timeout and the fixed-delay backoff (so tests do not actually wait).
- **WebClient stubbing:** `WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(status).header(...).body(json).build()))`. Capture `ClientRequest` to assert the URL, headers and the absence of a browser identifier. Count invocations with an `AtomicInteger` to check retry counts. Use `Mono.error(new ReadTimeoutException())` or `Mono.never()` for timeouts, and `Mono.error(new ConnectException())` for I/O failures.
- **Netty timeouts** (connect and read) are configuration, so they are checked by reading the built `HttpClient` configuration in P-08, not by real sockets.
- **Sessions:** `MockWebSession`, as in `AuthServiceImplTest`.
- **Log capture:** Logback `ListAppender`, as in `ClientErrorServiceImplTest`, attached to the class loggers and detached in `@AfterEach`.
- Test naming: `method_condition_expectedResult`, matching the existing tests.
- Run the tests:

  ```powershell
  .\gradlew.bat test --tests "com.careplus.gatewayservice.serviceimpl.*" --tests "com.careplus.gatewayservice.util.PaCodeNormalizerTest" --tests "com.careplus.gatewayservice.configs.dgl.*" --tests "com.careplus.gatewayservice.model.pa.*" --tests "com.careplus.gatewayservice.controller.AuthControllerTest" --tests "com.careplus.gatewayservice.constant.APIErrorCodeTest"
  ```

  Because `ignoreFailures=true` (F10), **check `build\test-results\test\TEST-*.xml` for `failures="0" errors="0"`**.
- Coverage target: at least 90% line and branch coverage on new classes (JaCoCo), to keep the Sonar new-code quality gate green.

### 5.2 `PaCodeNormalizerTest` (`src\test\java\...\util\`)

| ID | Test | Expectation |
|---|---|---|
| N-01 | `normalize_validResponse_mapsPrimaryFirstThenSecondaries` | `[P, S1, S2]`, `VERIFIED`, `retrievedAt` = fixed clock |
| N-02 | `normalize_duplicatePaCodes_dedupedPreservingOrder` | `[P, A, A, B, a]` gives `[P, A, B]` (case-folded duplicate removed) |
| N-03 | `normalize_blankAndNullEntries_dropped` | `null`, `""`, `"  "` and `PaCode(null)` are removed and the sanitized log reports counts only |
| N-04 | `normalize_invalidCharactersOrTooLong_dropped` | `"12-34"`, `"<script>"` and an 11-character value are dropped |
| N-05 | `normalize_whitespaceAndLowercase_trimmedAndUppercased` | `" ab12 "` becomes `"AB12"` |
| N-06 | `normalize_iamPrimaryDiffersFromToken_tokenPrimaryWinsAndWarns` | Primary = token value, the IAM primary stays in the list, `[DGL_PA_PRIMARY_MISMATCH]` is logged (D-3) |
| N-07 | `normalize_iamPrimaryMissing_tokenPrimaryUsed` | Primary = token value, no mismatch warning |
| N-08 | `normalize_primaryNotInList_primaryInsertedFirst` | `[P, ...]` |
| N-09 | `normalize_emptyList_verifiedWithPrimaryOnly` | `[P]`, `VERIFIED` (D-4) |
| N-10 | `normalize_nullResult_throwsInvalidPayload` | `PartnerIamException(INVALID_PAYLOAD)` |
| N-11 | `normalize_nullPaCodesList_throwsInvalidPayload` | same |
| N-12 | `normalize_userIdMismatch_throwsIdentityMismatch` | `IDENTITY_MISMATCH` (D-5) |
| N-13 | `normalize_userIdCaseAndWhitespaceDiffer_accepted` | Accepted |
| N-14 | `normalize_moreThanMax_cappedPrimaryRetained` | Size = max, primary kept, sanitized WARN logged |
| N-15 | `normalize_returnedListIsImmutable` | `UnsupportedOperationException` on mutation |

### 5.3 `PartnerIamClientTest` (`serviceimpl\`)

Uses an `ExchangeFunction` stub with an invocation counter and a mocked `PartnerIamTokenProvider`. Properties: `maxRetries=2`, `backoffMs=200`, `totalTimeoutMs=6000`.

| ID | Test | Expectation |
|---|---|---|
| C-01 | `fetchPaCodes_200_mapsToPaCodeResult` | **Mapping:** the DTO maps to `{userId, primaryPaCode, paCodes[{paCode}]}`, with no country field |
| C-02 | `fetchPaCodes_requestUsesVerifiedUserIdAndBearer` | Captured request path has the encoded `userId`, `Authorization: Bearer <tok>`, `Accept: application/json` |
| C-03 | `fetchPaCodes_userIdWithReservedChars_isUrlEncoded` | `a/b?c` is encoded, with no path traversal |
| C-04 | `fetchPaCodes_unknownJsonFields_ignored` | Success |
| C-05 | `fetchPaCodes_malformedJson_invalidPayload` | **Malformed payload** gives `INVALID_PAYLOAD`, not retried |
| C-06 | `fetchPaCodes_emptyBody_invalidPayload` | same |
| C-07 | `fetchPaCodes_400_clientErrorNotRetried` | **4xx** gives `CLIENT_ERROR`, exactly 1 exchange invocation |
| C-08 | `fetchPaCodes_404_clientError` | same |
| C-09 | `fetchPaCodes_401_invalidatesCachedTokenAndFails` | Token provider `invalidate()` called once, `CLIENT_ERROR` |
| C-10 | `fetchPaCodes_500_retriedOnceThenServerError` | **5xx** gives 2 invocations, then `SERVER_ERROR` |
| C-11 | `fetchPaCodes_503ThenSuccess_returnsResult` | Retry recovers |
| C-12 | `fetchPaCodes_429_clientErrorNotRetried` | Same as SAGE: 429 is a 4xx, so `CLIENT_ERROR` after exactly 1 invocation |
| C-13 | `fetchPaCodes_readTimeout_retriedThenTimeout` (virtual time) | **Timeout:** `ReadTimeoutException` on every attempt gives 2 invocations, a 200 ms fixed delay between them, then `TIMEOUT` |
| C-14 | `fetchPaCodes_totalTimeoutExceeded_timeout` (virtual time) | `Mono.never()` response: fails with `TIMEOUT` at `totalTimeoutMs` |
| C-15 | `fetchPaCodes_connectException_retriedThenServerError` | I/O counts as transient (same as SAGE) |
| C-16 | `fetchPaCodes_retryUsesFixedDelay_notExponential` (virtual time) | With `maxRetries=3`, the gaps between invocations are both exactly `backoffMs` |
| C-17 | `fetchPaCodes_maxRetriesOne_noRetry` | `maxRetries=1` means 1 total attempt (SAGE meaning) |
| C-18 | `fetchPaCodes_malformedPayload_notRetried` | Exactly 1 invocation |
| C-19 | `fetchPaCodes_tokenFetchedOnceAcrossApiRetries` | Two 5xx answers: `tokenProvider.getToken()` subscribed only once (the retry loops do not multiply) |
| C-20 | `fetchPaCodes_tokenProviderFails_tokenError` | `TOKEN_ERROR`, no IAM call made |
| C-21 | `fetchPaCodes_logsContainNoSecretsOrTokens` | **Log scan** (see 5.10) across C-01, C-07, C-10 and C-13 |

### 5.4 `PartnerIamTokenProviderTest`

| ID | Test | Expectation |
|---|---|---|
| T-01 | `getToken_firstCall_postsClientCredentialsForm` | `grant_type=client_credentials`, `client_id`, `client_secret`, `scope` |
| T-02 | `getToken_cachedUntilNearExpiry` | Second call makes no HTTP request |
| T-03 | `getToken_withinSkewOfExpiry_refreshes` | Fixed `Clock` advanced past `exp-60s` triggers a new request |
| T-04 | `getToken_concurrentCallers_singleRequest` | 10 parallel subscribers produce 1 exchange invocation |
| T-05 | `getToken_errorResponse_tokenErrorAndCacheNotPoisoned` | The next call retries the HTTP request |
| T-06 | `getToken_missingAccessToken_tokenError` | Malformed token response |
| T-07 | `invalidate_forcesRefresh` | |
| T-08 | `getToken_logsNeverContainSecretOrToken` | Log scan |
| T-09 | `getToken_5xxOrIoError_retriedWithFixedBackoffUpToMaxRetries` (virtual time) | Same as SAGE `TokenRetryInterceptor`: `token.maxRetries=2` gives 2 attempts, a `token.backoffMs` gap, then `TOKEN_ERROR` |
| T-10 | `getToken_4xx_notRetried` | Exactly 1 attempt, then `TOKEN_ERROR` |

### 5.5 `StubPaCodeSourceTest`

| ID | Test | Expectation |
|---|---|---|
| S-01 | `fetchPaCodes_configuredUser_returnsConfiguredPas` | |
| S-02 | `fetchPaCodes_unknownUser_returnsEmptyResult` | |
| S-03 | `fetchPaCodes_behaviorTimeout_neverCompletes` | Virtual time; the caller's timeout cuts it off |
| S-04 | `fetchPaCodes_behavior5xx_serverError` | `PartnerIamException(SERVER_ERROR)` |
| S-05 | `fetchPaCodes_behaviorMalformed_invalidPayload` | |

### 5.6 `DealerPaCodeServiceImplTest` (orchestration, mocked decoder and source)

| ID | Test | Expectation |
|---|---|---|
| O-01 | `resolve_flagOff_returnsEmptyWithoutDecodeOrCall` | **Flag off:** `verifyNoInteractions(jwtDecoder, paCodeSource)` |
| O-02 | `resolve_dealer_callsSourceWithVerifiedClaimUserId` | `fetchPaCodes("cdsid-from-jwt")`, `VERIFIED` context |
| O-03 | `resolve_customUserIdClaim_usesConfiguredClaim` | `user-id-claim=oid` uses the `oid` value (D-2) |
| O-04 | `resolve_level0400_noCallReturnsEmpty` | **Admin regression (no call)**: `verifyNoInteractions(paCodeSource)` |
| O-05 | `resolve_level0100_and_level0300_noCall` | Parameterized |
| O-06 | `resolve_sharedMbx_noCall` | |
| O-07 | `resolve_missingUserType_deniesUnsupportedUserType` | Error `UnsupportedUserTypeException`, `[DGL_UNSUPPORTED_USER_TYPE]` logged, no call (D-1) |
| O-08 | `resolve_unknownUserType_denies` | e.g. `"Supplier"` |
| O-09 | `resolve_dealerLowercase_treatedAsUnknown` | `"dealer"` denied, consistent with the decoder's `equals` (F2) |
| O-10 | `resolve_dealerBlankUserId_failsClosed` | Error, no call |
| O-11 | `resolve_dealerBlankSiteCode_failsClosed` | Error, no call |
| O-12 | `resolve_decoderError_propagates` | `OAuth2AuthenticationException` propagates (login denied), no call |
| O-13 | `resolve_sourceTimeout_fallbackPrimaryOnly` | `PRIMARY_ONLY_FALLBACK`, `paCodes=[primary]`, `[DGL_PA_IAM_FALLBACK]` with code `VCPGWERR71002` |
| O-14 | `resolve_source5xx_fallback` | code `71001` |
| O-15 | `resolve_source4xx_fallback` | code `71004` |
| O-16 | `resolve_tokenError_fallback` | code `71005` |
| O-17 | `resolve_invalidPayload_fallback` | code `11001` |
| O-18 | `resolve_identityMismatch_fallbackNeverGrantsSecondaries` | The result contains **only** the primary, even though the stubbed result had secondaries |
| O-19 | `resolve_unexpectedRuntimeException_fallback` | code `81001` |
| O-20 | `resolve_sourceNeverCompletes_totalTimeoutFallback` | Virtual time: the service-level timeout guards any source |
| O-21 | `resolve_success_logsCountOnlyNoPaValues` | INFO log contains `count=3` and does **not** contain any PA value or the user ID |

### 5.7 `DealerPaSessionAttributesTest`

| ID | Test | Expectation |
|---|---|---|
| A-01 | `write_storesJdkTypesOnly` | Each value is `String`, `ArrayList` or `Long`. No class under `com.careplus` (F4). |
| A-02 | `writeThenRead_roundTrips` | |
| A-03 | `write_overwritesStaleAttributes` | Re-login with a fallback removes the old list |
| A-04 | `read_absent_returnsEmpty` | |
| A-05 | `read_wrongTypes_returnsEmptyAndWarns` | Never throws |
| A-06 | `read_unknownStatus_returnsEmpty` | |
| A-07 | `attributes_javaSerializationRoundTrip` | Serialize the attribute map with `ObjectOutputStream`/`ObjectInputStream` to prove compatibility with older revisions |
| A-08 | `clear_removesAllKeys` | |

### 5.8 `AuthServiceImplTest` (extend the existing class)

Add `@Mock DealerPaCodeService dealerPaCodeService` and update `setUp()` to `new AuthServiceImpl(webClientBuilder, dealerPaCodeService)`. Default stub: `when(dealerPaCodeService.resolve(any())).thenReturn(Mono.empty())` (use `lenient()` where unused).

| ID | Test | Expectation |
|---|---|---|
| AS-01 | `handleCallback_flagOffEquivalent_noPaAttributes_existingBehaviorUnchanged` | `oauthSession`, TTL and `postLogoutUri` set, `loginFlow` removed, **no `dgl.*` keys**. All existing callback tests stay green unchanged. |
| AS-02 | `handleCallback_dealerVerified_storesPaAttributesBeforeSave` | Attributes present at the time `save()` is subscribed (`MockWebSession` plus a spy on save order) |
| AS-03 | `handleCallback_dealerFallback_storesFallbackStatusAndLoginSucceeds` | Completes successfully, status `PRIMARY_ONLY_FALLBACK` |
| AS-04 | `handleCallback_unsupportedUserType_errorsAndDoesNotPersistTokens` | `UnsupportedUserTypeException` and `oauthSession` **not** saved |
| AS-05 | `handleCallback_resolveCalledWithExchangedAccessToken_notBrowserInput` | `ArgumentCaptor` equals the token from the token endpoint response |
| AS-06 | `handleCallback_tokenExchangeFails_resolveNeverCalled` | `verifyNoInteractions(dealerPaCodeService)` |
| AS-07 | `handleCallback_stateMismatch_resolveNeverCalled` | |
| AS-08 | `handleCallback_malformedTokenResponse_resolveNeverCalled` | Missing `access_token` |
| AS-09 | `handleCallback_sessionSaveFails_errorPropagates` | Existing behavior is kept |
| AS-10 | `handleCallback_reloginOnSameSession_staleDglAttributesCleared` | |

### 5.9 `AuthControllerTest` (extend)

| ID | Test | Expectation |
|---|---|---|
| AC-01 | `callback_unsupportedUserType_invalidatesSessionAndRedirectsToFailureUri` | 302 to `postLoginFailureUri`, `session.invalidate()` called |
| AC-02 | `callback_dealerFallback_redirectsToPostLoginUri` | A fallback is still a successful login |

### 5.10 Cross-cutting: secret and PII log scan

Create the helper `src\test\java\...\testsupport\LogScan.java` (`ListAppender` attached to `com.careplus.gatewayservice`). Use it in C-21, T-08 and O-21, and in a dedicated `PartnerIamLogScanTest` that drives success and every failure path end to end (client, then service, with stubs). The assertions require that **no** log event's formatted message, arguments or throwable message contains:

- the configured client secret, the IAM access token or the Entra access token
- the substrings `Bearer `, `client_secret=` or `Authorization`
- any raw PA code value or the raw user ID at INFO level or above

Also assert that `PartnerIamProperties.toString()` does not contain the secret (P-04).

### 5.11 `PartnerIamPropertiesTest` / `PartnerIamConfigTest` (`configs\dgl\`)

Use `ApplicationContextRunner`; it is lightweight and needs no Redis.

| ID | Test | Expectation |
|---|---|---|
| P-01 | `defaults_flagOff` | `enabled=false` and the timeout defaults are bound |
| P-02 | `enabledIam_missingBaseUrl_failsFast` | Context fails; the message names the property, not a value |
| P-03 | `stubInDeploymentProfile_withoutAllowFlag_failsFast` | D-7 |
| P-04 | `toString_masksClientSecret` | |
| P-05 | `sourceStub_registersStubBeanOnly` | No `PartnerIamClient` bean |
| P-06 | `sourceIam_registersClientBeanOnly` | |
| P-07 | `partnerIamWebClient_usesClonedBuilder_sharedBuilderUnmodified` | F5 |
| P-08 | `partnerIamWebClient_appliesConnectAndReadTimeoutsFromProperties` | The Netty `HttpClient` config has `CONNECT_TIMEOUT_MILLIS = connectTimeoutMs` and `responseTimeout = readTimeoutMs`. The token connector uses `token.timeoutMs`. |

### 5.12 Other existing tests to update

- `APIErrorCodeTest`: new codes must pass the pattern and metadata checks. No test change is expected; just run it.
- `GatewayServiceApplicationTests` / Spring context tests: must still start with `application-test.properties` (flag off, stub).
- Any test that constructs `AuthServiceImpl` directly needs the new constructor argument. Find them with a search for `new AuthServiceImpl(`.

### 5.13 Traceability: required tests from the work doc

| Work-doc test | Covered by |
|---|---|
| Mapping | N-01 to N-09, C-01, C-04 |
| Timeout | C-13, C-14, O-13, O-20, S-03, P-08 |
| 4xx and 5xx | C-07 to C-12, C-15, O-14, O-15 |
| Malformed payload | C-05, C-06, N-10, N-11, O-17 |
| Duplicate or blank PAs | N-02, N-03, N-04, N-05 |
| Circuit breaker | **Replaced by the SAGE pattern (D-8):** bounded fixed-delay retry and timeouts are covered by C-10, C-13 to C-19, T-09 and T-10. Needs story-owner sign-off (OI-10). |
| Admin regression (no call) | O-04, O-05, O-06, AS-01 |
| Log scan for secrets | 5.10, C-21, T-08, O-21, P-04 |
| Flag off | O-01, AS-01, P-01 |

### 5.14 Acceptance-criteria traceability

| AC (proposed) | Implementation | Tests |
|---|---|---|
| AC1: missing or unknown `fordUserType` denied | 4.8 (D-1), 4.10 | O-07, O-08, O-09, AS-04, AC-01 |
| AC4: output `userId`, `primaryPaCode`, `paCodes[]` with no IAM country | 3.2, 4.5 | C-01 |
| AC5: the session is the cache | 3.3, 4.7, 4.9 | A-01 to A-08, AS-02 |
| AC6: fallback to primary only (onboarding check in BE-02) | 4.8 fallback | O-13 to O-19, AS-03, AC-02 |
| Primary not onboarded, secondary onboarded | **BE-02** (F3 dependency) | Out of scope; BE-02 test plan |
| Never use a browser-supplied identifier | 4.8 (claim only), 4.9 | AS-05, C-02 |
| No secrets in logs | 3.5 rules | 5.10 |

---

## 6. Open items carried from the work doc (with plan impact)

| ID | Item | Impact if unresolved | Plan mitigation |
|---|---|---|---|
| OI-1 | Is `userId` the CDSID, CommonName or Entra `oid`? | Wrong identity sent | `user-id-claim` is configurable (D-2) |
| OI-2 | How the response marks the primary; exact payload shape | DTO and mapping change | DTO is isolated in `PartnerIamPaCodesResponse` and the client mapping; the token primary is authoritative (D-3) |
| OI-3 | Are the Dealer Identity API and AMA/DLSRoles the same source? | Possible second `PaCodeSource` | The interface allows swapping without changing the orchestration |
| OI-4 | P95/P99 latency (about 1 s in pre-prod) | Timeouts too tight or too loose | All timeouts are property-driven; tune after the dev soak |
| OI-5 | REQ0947250 and production access (about 1 week lead) | Prod enablement blocked | Flag stays off in prod; request access now |
| OI-6 | IAM auth scheme (client credentials, Apigee key, mTLS) | Token provider rewrite | Isolated in `PartnerIamTokenProvider` |
| OI-7 | Is IAM reached through the corporate proxy? | Connection failures | Dedicated `partnerIamWebClient`; proxy inheritance decided at G-1 |
| OI-8 | BE-02 decoder change for "primary not onboarded, secondary onboarded" | Those dealers are still denied at callback | Documented as F3; BE-02 owns it |
| OI-9 | G-3 revalidation policy (refresh cadence) | The PA list can go stale for long sessions | `dgl.paCodesRetrievedAt` is stored so G-3 can be implemented without a schema change |
| OI-10 | Story owner signs off on replacing the circuit breaker (Work item 5) with the SAGE pattern (D-8) | AC or story text still lists a circuit breaker | Update the story's Work item 5 and the "Circuit breaker" entry in its Tests list. If a breaker is required later, it can be added inside `PartnerIamClient` without changing the interface or the orchestration. |

---

## 7. Definition of done

- All tasks in 4.1-4.11 are merged behind `dgl.partner-iam.enabled=false`.
- The tests in 5.2-5.12 pass. Verify via `build\test-results`, not just the Gradle exit code (F10).
- JaCoCo coverage on new classes is at least 90%, and the Sonar quality gate passes in both GitHub Actions and Tekton.
- `.\gradlew.bat generateOpenApiDocs` produces no `swagger.json` diff (no API change).
- Docs are updated (4.11), and log-metric alerts have been requested from ops (4.12).
- The PR follows `.github\PULL_REQUEST_TEMPLATE.md`, including the CAREPLUS-7411 link, a test evidence section, and an AI-assistance note.

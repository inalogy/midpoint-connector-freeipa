# Changelog

All notable changes to this project will be documented in this file.

## [1.2.1.3] - 2026-10-20
### Fixed
- Memory / connection leak (midPoint 4.10.5): the `login()` response was never
  consumed nor closed, so every re-login (`401 - no cookie detected` warning)
  left one pooled HTTP connection leased forever. Pool exhaustion then blocked
  worker threads and heap kept growing.
- Session cookie was a `public static` field shared by all connector instances,
  resources and threads: the first request always got 401, parallel threads
  logged in concurrently and multiple FreeIPA resources overwrote each other's
  session. The cookie is now per connector instance, login is synchronized and
  skipped when another thread already refreshed the session, and the first
  request logs in up front.
- Response is now closed on every path in `callRequest()` (also on errors), and
  a repeated 401 after re-login fails with `ConnectorSecurityException`.
- `preserved_user_list` is now a per-instance concurrent set (was a static,
  unsynchronized `HashSet` written by parallel tasks).

## [1.2.1.2] - 2026-10-07
### Security
- Bumped `httpclient5` 5.5 → 5.6.3, which also brings transitive
  `httpcore5` / `httpcore5-h2` 5.3.4 → 5.4.3. Fixes connection leak on
  Content-Encoding decode error (pool exhaustion DoS), HTTP/1 header
  parsing memory-exhaustion DoS, and HPackDecoder unlimited header list
  size before SETTINGS ACK.

## [1.2.1.1] - 2026-07-17
### Fixed
- FreeIPA (4.12.2, API 2.254) / midPoint (4.10.3): Corrected remaining single-valued
  membership attributes to multi-valued, in line with FreeIPA documentation. This
  extends the earlier fix that made `member_` and `memberof_` attributes multivalued
  to other membership-related attributes that were inadvertently left single-valued.
  Verified working as intended.

## [1.2.1.0] - 2026-05-30
### Fixed
- search: filter translator was setting `byName` / `byCn` but `executeQuery`
  only read `byUid`, so equality filters on `__NAME__` or `cn` silently
  returned every object of the requested class. Added
  `FreeIpaFilter.getLookupKey()` and switched all three branches
  (user/role/group) to use it.
- schema: extended-schema `memberof_*` / `member_*` attributes (e.g.
  `memberof_sudorule`, `memberof_hbacrule`) were declared single-valued
  because FreeIPA's introspected schema reports them that way. Replaced
  the explicit three-attribute workaround with a prefix check so every
  membership-style attribute is multi-valued.

## [1.2.0.0] - 2025-12-19
### Added
- support for updateDelta OP

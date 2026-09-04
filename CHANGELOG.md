# Changelog

All notable changes to this project will be documented in this file.

## [1.3.0.1] - 2026-09-03
Round-4 findings from testing outbound user provisioning. 1.3.0.0 was an internal build,
superseded before release — the version was bumped because an unchanged version string gives
no way to tell whether a redeployed jar's code is actually live.

### Fixed
- **A group or role missing from FreeIPA no longer kills the account's shadow.** FreeIPA
  answers `NotFound` when the group named in `memberof_group` / `memberof_role` does not
  exist, and `processFreeIpaResponseErrors()` maps every `NotFound` to
  `UnknownUidException`. Mid-modify, midPoint reads that as *"the object I am modifying no
  longer exists"*: it marked the user's shadow dead and tombstoned it, and the next recompute
  created a duplicate. `addRemoveMember()` now reports it as a plain `ConnectorException`
  naming the missing group. Pre-existing in 1.2.9.0 and in the 1.2.x line — not a 1.3.0.0
  regression.

  The original exception is deliberately **not** chained as the cause:
  `ConnIdUtil.lookForKnownCause()` walks the entire cause chain, so a wrapper that keeps the
  cause is found anyway, translated to `ObjectNotFoundException`, and the shadow is
  tombstoned regardless. Breaking the chain is what makes the fix work.
- A `*_show` for an object that does not exist now yields an empty search result instead of
  raising (`showOrNull()`, applied to all five object classes). ConnId treats
  `UnknownUidException` as "the operation's target is gone", which is right for
  get/update/delete by Uid but wrong for a search.

### Verified against live FreeIPA
Independently confirmed by two separate test passes: account create through the full mapping
chain (including the `initials` Groovy), group add / remove / redundant re-add /
remove-absent, missing-group now non-fatal with the shadow surviving and no duplicate,
disable and re-enable via `nsaccountlock`, and deprovision/delete.

### Verified once only
Password provisioning — `has_password` flipped false → true and `krblastpwdchange` appeared,
which is what confirms the `params` aliasing in `createUser`/`updateDeltaUser` is intact. The
second pass could **not** corroborate it: REST refuses credential modification (403) and a
local security policy blocked the scripted alternative. Treat this row as observed once, not
independently verified.

### Not reachable in this configuration
User rename. midPoint did not attempt to rename the account when the focus was renamed, so
the connector never received a rename delta and the null-Uid concern — the same shape of bug
fixed for `hostgroup` and `hbacrule` — is unproven either way for `user`.

### Configuration notes, not connector behaviour
- Unassigning `APP:freeipa-personal-account` does **not** deprovision the account: the
  Employee archetype still induces it. Disabling the focus does (the metarole conditions drop
  the construction), and so does deleting it. Easy to mistake for a connector fault.
- Group correlation matches on `name`, so a role named `MP:type-employee` never correlates
  with the FreeIPA group `type-employee`. Those groups stay unlinked, which is what made the
  tombstone defect above reachable in normal operation. The connector now survives it; the
  mismatch still means such roles do not manage their groups.

## [1.3.0.0] - 2026-09-02 (internal, superseded)
Reconciles the independently developed 1.2.9.0 build into the maintained tree. 1.2.9.0
shipped only as a jar; its features were re-implemented here, its regressions dropped and
its defects fixed. Verified against FreeIPA 4.12.2 / midPoint 4.10.

### Added
- `hostgroup` and `hbacrule` object classes: schema, search, create, updateDelta and delete.
- `buildStaticObjectClass()` declares the attribute set for those two classes, which
  FreeIPA's introspected `schema` does not report usably. It runs *after* introspection and
  only fills gaps, so anything FreeIPA does report keeps its own definition.
- HBAC and host-group membership management for `memberuser_user`, `memberuser_group`,
  `memberhost_host`, `memberhost_hostgroup`, `memberservice_hbacsvc`,
  `memberservice_hbacsvcgroup`, `member_host` and `member_hostgroup`. All of them support
  add, remove and replace; replace is computed as a diff against the current value.
- `hbacrule_find` results are re-read through `hbacrule_show`, because the find summaries
  omit membership and reconciliation would otherwise unassign every member.
- `ipaenabledflag` is routed to `hbacrule_enable` / `hbacrule_disable`, which is how
  FreeIPA maintains it; `hbacrule_mod` rejects the attribute.
- Rename support for `hostgroup` and `hbacrule`.

### Fixed
- **hostgroup / hbacrule schemas are static, not introspection-first.** An earlier revision
  of this release made `buildStaticObjectClass()` a supplement layered on top of FreeIPA's
  introspected schema. FreeIPA does report both classes - but wrongly. It declares
  `ipaenabledflag` as `type: "bool"` while `hbacrule_show` answers with the string `"TRUE"`,
  so the attribute was declared `Boolean` and delivered `String`, and midPoint rejected
  **every** HBAC rule with *"The value 'PPV(String:true)' does not conform to the
  definition ... boolean"* - taking down HBAC reconciliation and the whole SSH-group flow.
  It also reports the `memberuser_*` / `memberhost_*` / `memberservice_*` / `sourcehost_*`
  lists as single-valued. Introspection is now skipped for these two classes, as 1.2.9.0
  did, and the static declaration is authoritative: untyped (`xsd:string`) and explicitly
  multi-valued where it should be.
- `dn` is now declared on `group` and `role`. FreeIPA returns it for every object but
  declares it only for `user`, so `group_show` / `role_show` delivered an attribute midPoint
  had no definition for - the real cause of the long-standing "reconciliation
  partial_error", which had been attributed to a focus-type mismatch.
- `updateDelta` now returns the new Uid after a `hostgroup` or `hbacrule` rename. `cn` is
  the primary identifier for these classes, so returning `null` left midPoint holding the
  pre-rename Uid, marking the shadow dead and letting the next reconciliation create a
  duplicate.
- `mepmanagedentry` is filtered out of the host-group converter rather than declared.
  Declaring it looked like the tidier fix, but midPoint answers an attribute missing from a
  resource's *cached* schema with an infinite recursion in
  `LensProjectionContext.getCompositeObjectDefinition()` rather than a clean error, making
  every host group that owns a managed netgroup unreadable.
- **`create` on hostgroup / hbacrule is now atomic.** FreeIPA rejects membership as an
  `*_add` parameter, so it has to follow the add - which made create non-atomic. Once refused
  members started raising instead of being swallowed, a failed host add left the bare rule in
  FreeIPA while midPoint discarded the shadow. The orphan stayed invisible until the next
  reconciliation adopted it, after which the provisioning task saw a live shadow, skipped
  creation, and the rule stayed permanently hostless while looking provisioned - plausibly
  how the existing hostless `ar-teleport-*-ssh` rules arose. The follow-up steps are now
  wrapped: on failure the just-created object is deleted and the original error is rethrown.
  Rollback is only ever attempted when the preceding `*_add` returned success, so it cannot
  remove a pre-existing object; a failed rollback is logged, never masking the real cause.
- **Membership failures are no longer swallowed.** FreeIPA answers `*_add_member`,
  `*_remove_member` and `hbacrule_add_*` with HTTP 200 and `"error": null`, listing what it
  refused under `result.failed`. `processFreeIpaResponseErrors()` only inspects `error`, so
  every rejected member was reported to midPoint as a successful operation. Now checked, and
  raised as a `ConnectorException`, except where the requested end state already holds
  (adding an existing member, removing an absent one) so midPoint's repeated deltas stay
  idempotent. This also covers user role/group membership via `addRemoveMember()`.
- Role and group conversion dropped every scalar value: only `JSONObject` and `JSONArray`
  were handled, so a bare string or boolean was discarded. `String` and `Boolean` are now
  handled, matching the user converter.
- Multi-valued conversion uses `String.valueOf` instead of `JSONArray.getString`, so a
  non-string element degrades to its text form rather than throwing away the whole object.
- `has_password`, `has_keytab`, `membermanager_user` and `membermanager_group` were declared
  **required**: FreeIPA omits the `required` flag for computed and membership params and the
  connector defaults it to `true`. They are never accepted as input and are now optional.
- A schema parameter missing `type` or `name` no longer aborts the whole schema fetch
  (`optString` plus a skip, rather than `getString` and a `JSONException`).
- Duplicate attribute declarations are suppressed when introspection, the per-class
  workarounds and the static supplement overlap.
- Corrected malformed log calls in `addRemoveMember()` and the three `delete()` branches,
  which passed more arguments than the format string had placeholders.

### Known issue, not a connector defect
Fixing the swallowed-failure bug exposed that none of the `ar-teleport-*-ssh` rules has a
`memberhost_host` or a `hostcategory`. The provisioning task writes the VM identifier
(`inlg44789`, …), no FreeIPA host of that name exists, `hbacrule_add_host` is refused - and
1.2.9.0 answered HTTP 200 and reported success. **Those rules grant nothing and never have.**
Creating a new rule now fails loudly instead of silently, so a host-naming strategy has to
be settled before this release is used for SSH provisioning.

### Notes on 1.2.9.0
Deliberately **not** carried over from 1.2.9.0:
- Its `FreeIpaFilter` without `getLookupKey()`. 1.2.9.0 reads only `byUid` in the user
  branch, so an equality filter on `__NAME__` returned every user. All five branches now go
  through `getLookupKey()`.
- Its two-prefix multi-value widening. This tree keeps the five-prefix rule from 1.2.1.1, so
  `memberindirect_*`, `memberofindirect_*` and `membermanager_*` stay multi-valued.
- Its `roleSkippedAttrs`, which discarded `memberof_privilege` and `memberof_permission`
  outright. `memberof_privilege` is declared and multi-valued here, so its values now reach
  midPoint. `memberof_permission` is absent from FreeIPA's introspected role schema, so it
  is declared explicitly rather than skipped - an undeclared attribute would make midPoint
  reject the whole role object.
- Its unreachable `hostgroup`/`hbacrule` blocks inside the introspected schema branch.

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

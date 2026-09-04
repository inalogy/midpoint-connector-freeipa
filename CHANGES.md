# connector-freeipa 1.3.0.1 — code changes and reasoning

This document covers every change between the upstream connector (`1.2.1.1`) and this
branch (`1.3.0.1`), why each was made, and what behaviour changes as a result.

It exists because the diff alone is misleading. Several changes *revert* an apparently
obvious improvement back to what upstream did, because upstream was right for a reason that
is not visible in the code. Those are marked **Reverted to upstream behaviour** and each one
records why, so the next reader does not "fix" it again.

---

## 1. Scope and provenance

**What this branch is.** The features of an independently developed `1.2.9.0` build,
re-implemented on top of the maintained upstream source, plus the defects found while doing
so.

**What it is not.** `1.2.9.0` shipped only as a jar. Its `.java` files were recovered with
Fernflower and are decompiler output, not original source. They were used as a
*specification of observed behaviour* and never as a build input. Nothing was copied from
them.

That constraint drove the whole approach:

- Upstream source stays the tree of record; every feature was re-expressed by hand.
- Upstream's comments and its `1.2.1.0` / `1.2.1.1` fixes are preserved. A decompile has no
  comments, so porting the fork *over* upstream would have destroyed the rationale for
  every workaround in the file.
- A textual diff between the two trees is almost entirely decompiler noise (`this.`
  prefixes, inverted conditionals, `new Object[]{…}` log varargs, hoisted `else` blocks).
  Reading that diff as "changes" produces a broken merge.

The `1.2.9.0` directory is kept on disk for provenance and is `.gitignore`d. It is not part
of this branch.

> **Baseline.** `edee145` (`feat(project): CHANGELOG.md & version`), which already contains
> the `1.2.1.1` release. `git diff --stat edee145` covers exactly the five tracked files
> listed in §2 plus the new `CHANGES.md`, so this branch's diff is this release's work and
> nothing else.

---

## 2. File structure

Identical to upstream. No files added, moved or removed.

```
.editorconfig
.github/workflows/release.yml
.gitignore                                    modified — excludes the 1.2.9.0 reference tree
.run/connector-freeipa [clean,install].run.xml
CHANGELOG.md                                  modified — 1.3.0.1 and 1.3.0.0 entries
CHANGES.md                                    new — this document
LICENSE
README.md                                     unchanged — byte-identical to edee145
pom.xml                                       modified — version 1.2.1.1 -> 1.3.0.1
sample/
    metarole-FreeIPA-Group.xml
    metarole-FreeIPA-Role.xml
    resource.xml
    role-FreeIPA-Account.xml
src/main/assembly/connector.xml
src/main/java/com/inalogy/midpoint/connectors/freeipa/
    FreeIpaConfiguration.java                 unchanged
    FreeIpaConnector.java                     modified — 1,523 -> 2,320 lines
    FreeIpaFilter.java                        modified — doc comment only
    FreeIpaFilterTranslator.java              unchanged
src/main/resources/com/inalogy/midpoint/connectors/freeipa/Messages.properties
```

`FreeIpaConfiguration` and `FreeIpaFilterTranslator` are untouched: they are functionally
identical in both branches, and the three configuration properties (`sizelimit`,
`timelimit`, `support_preserved`) are unchanged.

### Untouched code paths

Deliberately not modified, because they are identical in both branches and already proven in
production. A diff here would be decompiler artefact, not divergence:

`init`, `execute`, `login`, `dispose`, `test`, `schema`, the three `getIpaRequest` overloads,
`callRequest`, `processFreeIpaResponseErrors`, `getMultiAsSingleValue`, `createUser`,
`updateDeltaUser`, `CreateRole`, `createGroup`, `updateDeltaGroup`, `updateDeltaRole`,
`putFieldValueIfExists`, `handleRoles`, `handleGroups`, `handleEnable`,
`createFilterTranslator`.

`addRemoveMember` is the one exception — see §5.1 and §5.2.

---

## 3. What the connector can do now

| Capability | 1.2.1.1 | 1.3.0.1 |
|---|---|---|
| Object classes | `user`, `group`, `role` | **+ `hostgroup`, `hbacrule`** |
| HBAC user membership | — | add · remove · replace |
| HBAC host membership | — | add · remove · replace |
| Host-group membership | — | add · remove · replace |
| Rename | user, group, role | **+ hostgroup, hbacrule** |
| Membership failure reporting | silently discarded | **raised** |
| Create atomicity (new classes) | — | rolled back on failure |
| Live sync · script execution · paging | no | no (unchanged) |

`README.md` is left byte-identical to upstream, so this document is the only place the new
capabilities are written down. Two points a resource author needs and will not find there:

- **Activation goes through `nsaccountlock`, not midPoint's native activation capability.**
  The connector deliberately does not advertise `__ENABLE__` — see
  `DISABLE_ADMINISTRATIVE_STATUS`, a workaround for MID-5883 — so map `ri:nsaccountlock`
  rather than relying on `administrativeStatus` alone. Unchanged from upstream, but the
  README's "Activation: YES" overstates it and always has.
- **Host-group and HBAC-rule membership cannot be set through `*_mod`.** The connector
  maintains it with the `*_add_member` / `*_remove_member` family; see §4.4.

---

## 4. New object classes

### 4.1 `hostgroup` and `hbacrule`

`CLASS_NAMES` grows from three entries to five, with matching branches in `executeQuery`,
`create`, `updateDelta` and `delete`.

### 4.2 Static schemas — **Reverted to upstream behaviour**

`buildStaticObjectClass()` declares the complete attribute set for these two classes, and
`buildObjectClass()` skips FreeIPA's introspected schema for them entirely
(`useIntrospection`).

An earlier revision of this work made the static declaration a *supplement* layered on top of
introspection — strictly more information, apparently better. It was not, and it is worth
being explicit about why, because the reasoning looks wrong until you see the data.

FreeIPA **does** report both classes. It reports them incorrectly:

- `ipaenabledflag` is declared `type: "bool"`, but `hbacrule_show` answers with the string
  `"TRUE"`. Declared `Boolean`, delivered `String`, so midPoint rejected **every** HBAC rule
  with *"The value 'PPV(String:true)' does not conform to the definition … boolean"* — taking
  down HBAC reconciliation and the entire SSH-group flow.
- The `memberuser_*`, `memberhost_*`, `memberservice_*` and `sourcehost_*` lists are reported
  single-valued, though every one of them is a list.

So the static schema is authoritative: untyped (`xsd:string`), matching what the JSON-RPC
layer really returns, and explicitly multi-valued where it should be. `1.2.9.0` bypassed
introspection for exactly this reason.

Attribute names and shapes were taken from live `hostgroup_show` / `hbacrule_show` responses
on FreeIPA 4.12.2.

### 4.3 `mepmanagedentry` is dropped, not declared — **Reverted to upstream behaviour**

`convertCnKeyedToConnectorObject()` filters `mepmanagedentry` out for these classes.

Declaring it instead looked like the tidier fix — dropping data is normally a defect. It is
not, here: midPoint answers an attribute missing from a resource's **cached** schema with an
infinite recursion in `LensProjectionContext.getCompositeObjectDefinition()`, a
`StackOverflowError`, rather than a clean error. Every host group owning a managed netgroup
(`objectClass mepOriginEntry`) became unreadable.

Isolated by contrast on the dev instance: `iwg-hosts`, which has a managed entry, broke;
`ipaservers`, which does not, was unaffected.

The attribute is a read-only, derived DN pointer of no provisioning value, so dropping it
costs nothing.

### 4.4 Membership on the new classes

Every membership attribute routes through one `applyMembershipDelta()`, which handles add,
remove **and** replace identically. `membershipBinding()` maps each attribute to its FreeIPA
commands and request parameter, plus the attribute to read the current value back from.

Handling attributes one at a time is what left `memberhost_host` in `1.2.9.0` with add and
remove but no replace branch, so a replace delta was silently discarded. A single code path
makes that class of omission impossible.

Two details worth knowing:

- **Replace is a diff.** `readCurrentMembers()` reads the current value and the difference is
  applied. If that read fails it raises rather than returning an empty set — an empty set
  would make a replace look like "remove nothing", leaving stale members behind while
  reporting success.
- **`memberhost` vs `member_host`.** FreeIPA accepts the former as input on some versions but
  always answers `hostgroup_show` with the latter, so the binding records which name to read
  back. Diffing against the wrong name would conclude the group is empty and never remove
  anything.

### 4.5 `ipaenabledflag` is routed to enable/disable

FreeIPA rejects `ipaenabledflag` as an `hbacrule_mod` parameter; it is maintained with
`hbacrule_enable` / `hbacrule_disable`. `handleHbacruleEnabledFlag()` does that, so a mapping
targeting the attribute works instead of failing.

### 4.6 Create is atomic

FreeIPA rejects membership as an `*_add` parameter, so it has to follow the add — which makes
create non-atomic. Once refused members started raising (§5.1), a failed host add left the
bare rule in FreeIPA while midPoint discarded the shadow. The orphan stayed invisible until
the next reconciliation adopted it, after which the provisioning task saw a live shadow,
skipped creation, and the rule stayed permanently hostless **while looking provisioned**.
That is plausibly how the existing hostless `ar-teleport-*-ssh` rules arose.

`createCnKeyed()` now wraps its follow-up steps and `rollbackCreate()` deletes the
just-created object on failure, rethrowing the original error. Two guardrails:

- Rollback runs only when the preceding `*_add` returned success, so it can never remove
  something that existed beforehand.
- A failed rollback is logged, never thrown — it must not mask the real cause.

**Not extended to `createUser`.** The same non-atomicity exists there, but rolling back would
mean deleting a real account on a transient membership error. That is a worse failure than the
orphan it prevents. Recorded as accepted, not missed.

---

## 5. Correctness fixes

### 5.1 Membership failures are no longer swallowed

**This is the most consequential change in the release.**

FreeIPA answers `*_add_member`, `*_remove_member` and `hbacrule_add_*` with HTTP 200 and
`"error": null`, listing whatever it refused under `result.failed`:

```json
{"result": {"completed": 0,
            "failed": {"memberhost": {"host": [["web01", "no such entry"]],
                                      "hostgroup": []}}}}
```

`processFreeIpaResponseErrors()` inspects only `error`, so **every such rejection was reported
to midPoint as a successful operation** — the shadow recorded a member FreeIPA never stored.
This affected user role and group membership too, not just the new classes.

`checkMemberOperationResult()` now walks the `failed` block and raises a `ConnectorException`,
except where the requested end state already holds — adding an existing member, removing an
absent one — because midPoint reissues those routinely and they must stay idempotent.

**Behaviour change:** operations that used to report success while doing nothing now fail.
That is correct, and it is also why the release exposed a configuration problem that had been
invisible: see §7.

### 5.2 A missing group no longer kills the account's shadow

FreeIPA answers `NotFound` when the group named in `memberof_group` / `memberof_role` does not
exist, and `processFreeIpaResponseErrors()` maps *every* `NotFound` to `UnknownUidException`.
Mid-modify, midPoint reads that as *"the object I am modifying no longer exists"*: it marked
the **user's** shadow dead and tombstoned it, and the next recompute created a duplicate.

Pre-existing in `1.2.9.0` and in the `1.2.x` line — not a regression of this release. It is
reachable in normal operation wherever a role's group is absent or unlinked.

`addRemoveMember()` now reports it as a plain `ConnectorException` naming the missing group.

**The original exception is deliberately not chained as the cause.**
`ConnIdUtil.lookForKnownCause()` walks the entire cause chain, so a wrapper that keeps the
cause is found anyway, translated to `ObjectNotFoundException`, and the shadow is tombstoned
regardless. Breaking the chain is what makes the fix work. Removing that detail silently
reintroduces the defect.

### 5.3 A search for something absent returns empty

`showOrNull()` — applied to all five object classes — makes a `*_show` for a non-existent
object yield no results instead of raising. ConnId treats `UnknownUidException` as "the
operation's target is gone", which is right for get/update/delete by Uid and wrong for a
search.

### 5.4 Equality filters — upstream fix retained and extended

Upstream's `FreeIpaFilter.getLookupKey()` (from `1.2.1.0`) is kept and **all five**
`executeQuery` branches now route through it.

`1.2.9.0` had re-fixed this inline for role and group but missed the user branch, where it
matters most: a filter on `__NAME__` produced a filter with only `byName` set, fell through to
the unfiltered `user_find`, and returned **every user on the resource**. Adopting the fork
wholesale would have reintroduced that.

### 5.5 Multi-value widening — upstream fix retained

Upstream's five-prefix rule (`memberof_`, `member_`, `memberindirect_`, `memberofindirect_`,
`membermanager_`) is kept. `1.2.9.0` widened only the first two, leaving ten membership
attributes single-valued, so a second value on any of them failed schema validation.

### 5.6 Computed attributes are no longer required

FreeIPA omits the `required` flag for computed and membership params, and the connector
defaults it to `true`. That surfaced four attributes to midPoint as mandatory that FreeIPA
never accepts as input: `has_password`, `has_keytab`, `membermanager_user`,
`membermanager_group`. Now forced optional via `READ_ONLY_COMPUTED` and
`isMembershipAttribute()`.

The default itself is left alone — flipping it would change every object type at once, and the
observed damage was limited to those four.

### 5.7 `dn` is declared on `group` and `role`

FreeIPA returns `dn` for every object but declares it only for `user`, so `group_show` and
`role_show` delivered an attribute midPoint had no definition for. This was the real cause of
a long-standing "reconciliation partial_error" that had been attributed to a focus-type
mismatch. Both reconciliations are clean now.

### 5.8 Role privilege attributes reach midPoint

`1.2.9.0` carried a `roleSkippedAttrs` set that discarded `memberof_privilege` and
`memberof_permission` outright. Not ported:

- `memberof_privilege` is declared and multi-valued here, so its values now reach midPoint.
  Verified against a live IPA role that has privileges and previously returned none.
- `memberof_permission` is genuinely absent from FreeIPA's introspected role schema, so it is
  **declared explicitly** rather than skipped. Un-skipping without declaring would hand
  midPoint an undeclared attribute and make it reject the whole role object.

### 5.9 Rename returns the new Uid

`cn` is the primary identifier for `hostgroup` and `hbacrule`, so a rename changes the Uid.
`updateDeltaCnKeyed()` returns it as a side-effect delta; returning `null` left midPoint
holding the pre-rename Uid, marking the shadow dead and letting the next reconciliation create
a duplicate.

**`user` is not covered.** midPoint did not attempt to rename the account when the focus was
renamed, so the connector never received a rename delta and the same shape of bug is unproven
either way there. Left alone rather than fixed speculatively.

### 5.10 Schema introspection is hardened

`optString` with a null-name guard replaces `getString` for a parameter's `type` and `name`. A
single malformed parameter used to throw `JSONException` and lose the entire schema, making
the resource unusable. Duplicate declarations are also suppressed, so introspection, the
per-class workarounds and the static declaration can be layered without
`ObjectClassInfoBuilder` throwing.

### 5.11 Role and group converters no longer drop scalars

`convertRoleToConnectorObject` and `convertGroupToConnectorObject` handled only `JSONObject`
and `JSONArray`, so a bare `String` or `Boolean` on a role or group was silently discarded.
They now handle both, matching the user converter. Multi-value conversion uses
`String.valueOf` instead of `JSONArray.getString`, so a non-string element degrades to its
text form rather than throwing away the whole object.

### 5.12 Password aliasing documented, not changed

`createUser` and `updateDeltaUser` build and log the request, then mutate the same `params`
object the request already holds by reference — that is how the password reaches FreeIPA
without reaching the log. It reads like a bug and it is load-bearing: any refactor that
defensively copies `params` silently stops provisioning passwords. Left exactly as-is, with a
comment saying so.

### 5.13 Log-call fixes

`addRemoveMember` and the three `delete` branches passed more arguments than their format
strings had placeholders. Cosmetic.

---

## 6. Verification status

Four independent test rounds against live FreeIPA 4.12.2 and midPoint 4.10.2, each fix
re-verified by whoever did not write it.

**Confirmed by two or more passes.** All five object classes reconcile clean; equality filter
returns exactly one hit on all five branches; zero single-valued membership attributes; zero
wrongly-required attributes; `dn` on all five classes; `ipaenabledflag` delivered as string;
HBAC and host-group membership add / remove / replace, including redundant-add and
remove-absent staying no-ops; refused membership raising instead of reporting success;
create rollback leaving no orphan, with its guardrails tested separately (valid member, bad
member on an existing object, and create-over-existing all behave correctly); account create
through the full mapping chain; group add and remove; disable and re-enable; deprovision.

**Observed once, not corroborated.** Password provisioning — `has_password` flipped false to
true and `krblastpwdchange` appeared. The second pass could not reproduce it: REST refuses
credential modification and a local security policy blocked the scripted alternative. Treat
this row as thin.

**Open.** User rename, per §5.9.

---

## 7. Deployment requirements

### Refresh the resource schema

The schema changed in this release — ten membership attributes widened to multi-valued, `dn`
added on `group` and `role`, `memberof_permission` declared, four attributes no longer
required. A resource still serving a schema cached by the previous version is inconsistent
with what the connector now returns, and midPoint answers that with an infinite recursion in
`LensProjectionContext.getCompositeObjectDefinition()` — a `StackOverflowError` — rather than
a clear message.

So the upgrade order is:

1. Deploy the jar and restart midPoint.
2. Repoint the resource's `connectorRef` at the new version.
3. **Force a schema refetch** — in the GUI, Resources → *resource* → Refresh schema.

> **Replacing a jar in place and restarting is not enough.** The cached resource schema
> survives both, so the connector runs new code against an old schema, and if the version
> string is unchanged nothing in midPoint signals that the schema is stale.
>
> A version bump also proves only that *a* build of that version is loaded, not *which*
> build. When iterating on a fixed version, confirm the running code is the one you just
> built — compare the jar's mtime against the container's start time — before drawing any
> conclusion from a test. Two wrong diagnoses during this release traced back to exactly
> that. The reliable loop is: build → deploy → restart → force a schema refetch → confirm
> build identity → test.

### Settle the host-naming strategy

This is the one blocker to using the release for SSH provisioning, and it is configuration,
not code.

Fixing §5.1 exposed that none of the `ar-teleport-*-ssh` rules has a `memberhost_host` or a
`hostcategory`. The provisioning task writes the VM identifier (`inlg44789`, …), no FreeIPA
host of that name exists, `hbacrule_add_host` is refused — and `1.2.9.0` answered HTTP 200 and
reported success. **Those rules grant nothing and never have.** Creating a new rule now fails
loudly instead, which is correct but not yet functional. Three viable options, all confirmed
compatible: enrol the VMs under the identifiers the task writes, map identifier to enrolled
FQDN in the task, or set `hostcategory=all` where a rule is meant to be host-wide. Creating
with a genuinely enrolled host is verified working.

### Configuration issues found, outside this connector

- The deployed project-role template computes the rule name from `vm.identifier`, not
  `vm.name`; the repository copy uses `vm.name` and is stale against what runs. A VM named
  `uni-ipahost` with identifier `ipa.lab.inalogy.net` produced
  `ar-teleport-uni-ipa.lab.inalogy.net-ssh`.
- Group correlation matches on `name`, so a role named `MP:type-employee` never correlates
  with the FreeIPA group `type-employee`. Those groups stay unlinked — which is what made
  §5.2 reachable. The connector survives it now; the mismatch still means such roles do not
  manage their groups.
- Unassigning `APP:freeipa-personal-account` does **not** deprovision: the Employee archetype
  still induces the account. Disabling or deleting the focus does. Easy to mistake for a
  connector fault.
- HBAC provisioning order is `PROVISION → RECON → PROVISION`. PASS 2 only syncs members for
  shadows reconciliation has linked, so a freshly created rule is invisible to it until
  `[RECON] FreeIPA:HBACRule` runs. It presents as a silent no-op.

---

## 8. Not included in this branch

- The `1.2.9.0` reference tree — `.gitignore`d, kept on disk for provenance only.
- Resource switch XMLs used for version testing — they carry a live instance's encrypted
  password cipher and are `.gitignore`d. Regenerate them from the live object instead.
- Three midPoint objects that exist on the dev instance but have never had a file:
  `[PROVISION] FreeIPA HBAC Rules`, `[PROVISION] FreeIPA Project Group Memberships`, and
  `[RECON] FreeIPA:OrgHostGroup`. The first is the only consumer of the HBAC feature this
  release exists to fix; capturing it is worth doing separately.

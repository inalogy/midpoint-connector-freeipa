# connector-freeipa 1.3.1.0 — code changes and reasoning

This document covers every change between the upstream connector (`1.2.1.1`) and this
branch (`1.3.1.0`), why each was made, and what behaviour changes as a result.

It exists because the diff alone is misleading. Several changes *revert* an apparently
obvious improvement back to what upstream did, because upstream was right for a reason that
is not visible in the code. Those are marked **Reverted to upstream behaviour** and each one
records why, so the next reader does not "fix" it again.

---

## 0. What this document covers, and what it does not

**The connector is general-purpose.** It has to work against any midPoint instance and any
FreeIPA deployment, so nothing in `src/` is allowed to depend on the dev instance this work
was developed against. Two rules follow from that, and both were breached during development
and then corrected:

- **Attribute sets come from FreeIPA, not from a list in the connector.** Version, installed
  plugins and local schema extensions all change what a deployment returns, so every object
  class is enumerated from FreeIPA's own introspected schema. Hand-maintained lists appear
  only as a gap-filler for attributes a deployment's schema demonstrably omits, and they are
  additive — introspection always wins.
- **Decisions are structural, never based on FreeIPA's message text.** FreeIPA localises its
  messages, so matching on English wording works only against an English-language server.

Sections 1-6 are the connector. **Section 7 is the configuration of one particular
deployment** — resource, metaroles and tasks — recorded here because the connector changes
cannot be judged without it, and because fixing the connector exposed several configuration
defects. None of section 7 ships with the connector: those files live in the customer project
and are versioned there. Anyone deploying this connector elsewhere needs their own
equivalent, and should read section 7 as worked example rather than as installation
instructions.

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
CHANGELOG.md                                  modified — 1.3.1.0, 1.3.0.1, 1.3.0.0 entries
CHANGES.md                                    new — this document
LICENSE
README.md                                     modified — capabilities, membership, upgrading
pom.xml                                       modified — version 1.2.1.1 -> 1.3.1.0
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

| Capability | 1.2.1.1 | 1.3.1.0 |
|---|---|---|
| Object classes | `user`, `group`, `role` | **+ `hostgroup`, `hbacrule`, `host`** |
| HBAC user membership | — | add · remove · replace |
| HBAC host membership | — | add · remove · replace |
| Host-group membership | — | add · remove · replace |
| Rename | user, group, role | **+ hostgroup, hbacrule** (refused for host — fqdn is immutable) |
| Membership failure reporting | silently discarded | **raised** |
| Create atomicity (new classes) | — | rolled back on failure |
| Live sync · script execution · paging | no | no (unchanged) |

`README.md` now states these accurately: the six object classes, activation through
`nsaccountlock` rather than midPoint's native capability, the membership semantics, and the
schema-refresh step required on upgrade. It points here for the reasoning. Upstream's
"USER, ROLE and GROUP" and unqualified "Activation: YES" were both wrong before — the
activation claim had always been misleading, independently of this release.

---

## 4. New object classes

### 4.1 `hostgroup` and `hbacrule`

`CLASS_NAMES` grows from three entries to five, with matching branches in `executeQuery`,
`create`, `updateDelta` and `delete`.

### 4.2 `hostgroup` and `hbacrule` are enumerated from FreeIPA but treated as untyped

These two classes need care because FreeIPA describes them inaccurately. The two available
failure modes pull in opposite directions:

- **Trusting FreeIPA's declared types** makes midPoint reject every object of the class.
- **Declaring the attribute set by hand** risks omitting something a deployment returns, and
  an attribute midPoint has no definition for makes it recurse until it throws
  `StackOverflowError` rather than reporting a clear error (§4.3).

`1.2.9.0` took the second route and declared both classes entirely by hand. This release
takes neither wholesale: attributes are **enumerated from FreeIPA's introspected schema**, so
nothing a deployment returns can be missing, while the **declared type is ignored** for these
two classes (`UNTRUSTED_TYPE_CLASSES`), leaving every attribute untyped — which is what the
JSON-RPC layer actually sends. Cardinality is decided separately, by prefix, in
`isMembershipAttribute()`.

That matters for portability: a hand-maintained list is only ever correct for the FreeIPA it
was written against, and this one was written against a single 4.12.2 lab. Enumeration works
on any version. `buildStaticObjectClass()` survives only as an additive safety net for a
deployment whose schema omits these classes; introspection runs first and wins, so on a
FreeIPA that reports them it contributes nothing.

An intermediate revision made the hand-written list a *supplement* on top of introspection
while still honouring FreeIPA's types. That is the one combination that does not work, and
it is worth recording why:

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

### 4.4a The `host` object class

Added in 1.3.1.0, to resolve the host-binding problem in §7. FreeIPA host entries are keyed
on `fqdn`, not `cn`, so `primaryKeyAttr()` selects the key per object class and the shared
converter, create and update paths are keyed generically rather than on `cn`.

`host_add` always sends `force=true`: without it FreeIPA refuses any name with no DNS A
record, which is the normal case when host entries are provisioned ahead of DNS. FreeIPA
still requires a fully-qualified name, so the resource must map `icfs:name` to an FQDN —
a bare VM identifier is rejected.

`host` is enumerated from FreeIPA's introspected schema and its declared types are honoured,
because they are accurate: the three boolean attributes — `ipakrbokasdelegate`,
`ipakrboktoauthasdelegate`, `ipakrbrequirespreauth` — were verified to come back as real JSON
booleans, so `host` does not share the `ipaenabledflag` inconsistency that forces
`hostgroup` and `hbacrule` to be treated as untyped (§4.2).

Four attributes still need declaring by hand, because `host_show` returns them while
introspection describes none of them: `cn`, `krbextradata`, `krblastpwdchange` and
`krbpwdpolicyreference`. The three Kerberos ones are exactly what upstream already
hand-declares for `user`, so this is a consistent FreeIPA omission rather than something
peculiar to `host`. They were found the way the rest of this release was — by importing a
real host and reading the error.

Rename is refused with an explanatory message rather than attempted: a host's `fqdn` is
immutable in FreeIPA because the Kerberos principal and any issued certificates derive from
it, and `host_mod` has no rename option.

`managedby_` was added to the membership prefixes, which makes `managedby_host` multi-valued,
optional and skipped on write — it is maintained with `host_add_managedby` /
`host_remove_managedby`, and sending it to `host_mod` would draw error 3005.

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

**That exception is decided structurally, not from FreeIPA's message text.** A refused
*remove* is always benign: the member is not there, which is the requested outcome. A refused
*add* is benign only when the member is already present, which `isBenignMemberFailure()`
establishes by reading the object back — one extra round trip, on the error path only.

An earlier revision matched the English string `"already a member"` instead. FreeIPA
localises its messages, so that worked only against an English-language server; on any other
locale a redundant add would have started failing and taken user provisioning with it, since
`addRemoveMember()` is what assigns every user's groups and roles.

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

### The host-naming problem, and how it was settled

This was the one blocker to using the release for SSH provisioning. It was configuration
rather than code, and it is now resolved — see *The configuration side* below.

Fixing §5.1 exposed that none of the `ar-teleport-*-ssh` rules has a `memberhost_host` or a
`hostcategory`. The provisioning task writes the VM identifier (`inlg44789`, …), no FreeIPA
host of that name exists, `hbacrule_add_host` is refused — and `1.2.9.0` answered HTTP 200 and
reported success. **Those rules grant nothing and never have.** Creating a new rule now fails
loudly instead, which is correct but not yet functional.

What the dev instance actually contains, established by direct probe:

- The task derives the host reference from `vm.getIdentifier()`, giving a bare identifier
  such as `inlg44789`.
- **No VM is enrolled in FreeIPA as a host, under any name.** Both `inlg44789` and
  `inlg44789.lab.inalogy.net` are refused with *"no such entry"*. The only enrolled host is
  `ipa.lab.inalogy.net`, the IPA server itself.
- The eight `*-hosts` host groups exist, clearly built to hold these machines, and every one
  of them is empty.
- The connector has **no `host` object class**, so midPoint cannot enrol or manage FreeIPA
  hosts at all. `CLASS_NAMES` covers `user`, `group`, `role`, `hostgroup`, `hbacrule`.

That ruled out mapping the identifier to an enrolled FQDN, which looked like the cheap fix:
there was no enrolled FQDN to map to.

**Resolved in 1.3.1.0 by adding the `host` object class** (§4.4a), so midPoint provisions the
host entries itself rather than waiting on infrastructure. The alternative of setting
`hostcategory=all` was rejected deliberately: it would convert "these users may reach this
host" into "these users may reach every host", widening access rather than repairing it.

End-to-end verification: a host entry was created for a VM identifier with no DNS record,
bound to `ar-teleport-iwg-inlg44789-ssh`, and the rule then carried both a user list and a
host for the first time. The test host was unbound and deleted afterwards, so FreeIPA is back
to its baseline single host.

### The configuration side, now done

Three pieces were needed to turn the connector capability into working provisioning. All
three are in the customer project (`Midpoint_groups_test`), not in this repository:

1. **A `host` objectType** on `ABSTRACT_Resource_FreeIPA.xml` — `kind=entitlement`,
   `intent=host`, focus `ServiceType`, since VM inventory is held as midPoint services.
   Deliberately no correlation and no synchronization reactions: the host `fqdn` is the VM
   identifier plus a domain suffix, so no plain attribute equals the VM's own name or
   identifier, and a guessed correlator would risk binding a host to the wrong service.
   Discovered hosts therefore stay `UNMATCHED`, which is correct — midPoint provisions hosts
   but does not adopt pre-existing ones.
2. **FQDN derivation** in `Provision_FreeIPA_HBACRules.xml`. The task derived the host
   reference from `vm.getIdentifier()`, giving a bare `inlg44789`. It now qualifies it with a
   `FREEIPA_HOST_DOMAIN` constant, and treats an identifier that already contains a dot as
   already qualified so a deliberately FQDN-shaped identifier is not suffixed twice.
   Qualifying here rather than lengthening the identifier is the point: the identifier also
   forms part of the rule name, so lengthening it corrupts every rule name.
3. **Host entries created before the rule references them**, in the same task. This is not
   optional any more: `hbacrule_add_host` is refused when the host is absent, and since
   1.3.0.1 that refusal is reported *and* rolls the rule back — so a missing host now yields
   no rule at all rather than a hostless one.

   The task also binds the host to rules that already exist. `memberhost_host` was only ever
   set at creation time, so the pre-existing rules would otherwise have stayed hostless
   forever, since the task skips anything already present. The bind is issued unconditionally
   and relies on the connector treating an add of an existing member as a successful no-op
   (§5.1), which makes it idempotent and needs no live read.

Verified end to end: the task created `inlg44789.lab.inalogy.net`,
`inlg57894.lab.inalogy.net` and `inlg10002.lab.inalogy.net` in FreeIPA from the short VM
identifiers, then bound each to its rule. Live reads confirm all three
`ar-teleport-*-ssh` rules now carry both `memberuser_user` and `memberhost_host` — **the
first time those rules have granted anything.**

One rule is left hostless by design: `ar-teleport-slt-inlg19223-ssh` has no corresponding
project role, so the task has no VM to derive a host from. It is an orphan from earlier
testing rather than a defect.

A host entry created this way is a FreeIPA record, not an enrolled machine. It is enough for
HBAC rules to be structurally valid and to grant access once the machine itself enrols with
`ipa-client-install`; it does not replace enrolment.

### Configuration issues found, outside this connector

- **The naming chain is intentional, and the identifier now carries a conflict.** An earlier
  revision of this document reported the deployed project-role template as stale against its
  repository copy. That was wrong — they match, and the naming is by design. Traced end to
  end in `Midpoint_groups_test`:

  | Step | Rule |
  |---|---|
  | VirtualMachine template | `vm.name = "${org.extension.shortname}-${vm.identifier}"` |
  | Project-role template | `role.name = "${vm.name}-${service.name.toLowerCase()}"` |
  | Project-role template | `role.identifier = "ar-teleport-${role.name}"` |
  | HBAC provisioning task | rule name ← `role.identifier`; host ← `vm.identifier` |

  So rule and group names come from the **role**, not from the VM fields directly — the VM
  fields feed role and service creation, exactly as intended. The VirtualMachine template
  documents the convention itself: *"used in Entra group and FreeIPA HBAC rule naming:
  ar-teleport-{shortname}-{vmIdentifier}-{service}"*. The observation that produced the
  original report — a VM whose identifier was set to `ipa.lab.inalogy.net` yielding
  `ar-teleport-uni-ipa.lab.inalogy.net-ssh` — is the template working correctly on an
  FQDN-shaped identifier, not a defect.

  The real issue it exposes matters for §4.4a: **`vm.identifier` now serves two purposes that
  pull apart.** As a name component it wants to be short (`inlg44789`); as the FreeIPA host
  reference it has to be fully qualified. Setting the identifier to an FQDN to satisfy the
  second corrupts the naming convention, as that accidental test demonstrated. The FQDN
  should therefore be **derived in the provisioning task** —
  `vm.identifier + '.' + domain` — leaving the identifier short. One line in
  `Provision_FreeIPA_HBACRules.xml`, where `hostname` is currently
  `basic.stringify(vm?.getIdentifier())?.trim()`.
- **The `group` correlator does not match what the outbound mapping produces.** Confirmed
  against the live instance and the repository copy, which agree.

  The group's name is set by a **strong** mapping in `MP:freeIpa-group-metarole`, sourced
  from the role's `identifier`. The resource-level outbound from `name` is weak and loses.
  So role `MP:type-employee` (identifier `type-employee`) correctly produces the FreeIPA
  group `type-employee`. But the `group` objectType correlates the focus `name` against
  `$projection/attributes/icfs:name` — it looks for a role *named* `type-employee`, and the
  role is named `MP:type-employee`. No match.

  The `group` correlator has been changed to `identifier`, matching what its own construction
  produces and matching what the `hbacrule` objectType already does correctly. **That change
  is correct but not sufficient**, and the reason is the more important finding.

  **The real blocker is classification, not correlation.** There are two objectTypes for
  `ri:group`, and the design is deliberate:

  | objectType | focus | name produced from | targeted by |
  |---|---|---|---|
  | `group` | `RoleType` | `role.identifier` | `MP:freeIpa-group-metarole` (`intent=group`) |
  | `org-group` | `OrgType` | org `name` / `orgKey` | `MP:FreeIpa-org-group-metarole` (`intent=org-group`) |

  Each metarole names its intent explicitly, so **provisioning** routes correctly. But both
  objectTypes delineate on nothing more than `<objectClass>ri:group</objectClass>`, with no
  distinguishing filter and neither marked `default`. So when reconciliation **discovers** a
  group it cannot tell which objectType applies and takes the first — `org-group`.

  Verified: all 25 group shadows carry `intent=org-group`. Not one is `intent=group`. The
  `group` objectType is unreachable for discovered objects, which is why correcting its
  correlator changed nothing on its own — reconciliation confirmed the three `type-*` groups
  still `UNMATCHED` afterwards.

  So a role-owned group that already exists in FreeIPA is classified into an **Org-focused**
  objectType, and can never correlate to its Role regardless of the correlator path. The 15
  `LINKED` groups are all org-owned and linked because midPoint provisioned them; the three
  `type-*` groups pre-existed, were discovered, and are stranded.

  Symptoms, both reproduced: the three `type-*` groups sit permanently `UNMATCHED`, and
  `recompute` on their roles fails with **HTTP 409 AlreadyExistsException** — midPoint tries
  to create a group that already exists but which it cannot adopt.

  **Fixed** by delineation filters on both `ri:group` objectTypes, so exactly one matches any
  given group and classification no longer depends on document order:

  ```xml
  <!-- group -->
  <filter><q:text>attributes/icfs:name startsWith "type-"</q:text></filter>
  <!-- org-group -->
  <filter><q:text>not (attributes/icfs:name startsWith "type-")</q:text></filter>
  ```

  Filtering both sides rather than only `group` is deliberate: leaving `org-group` unfiltered
  would keep it matching everything, so a `type-*` group would still satisfy both types.

  The `type-` convention is safe here rather than merely convenient — `MP:freeIpa-group-metarole`
  has exactly three consumers (`MP:type-employee`, `MP:type-contractor`, `MP:type-part-timer`)
  and all three carry a `type-*` identifier. A fourth role-owned group outside that convention
  would need the filter widened, which the inline comment in the resource records.

  **A delineation change alone does not repair existing shadows.** A shadow's kind and intent
  are recorded when it is created and are sticky: reconciliation matched the three `type-*`
  groups by primary identifier, reused the existing shadows, and left them on `org-group`.
  Verified — the first reconciliation after the filter change reported success and changed
  nothing. The repair was to delete those three shadows from the repository with
  `ModelExecuteOptions.create().raw()`, which leaves the FreeIPA groups untouched, and let
  reconciliation re-discover them. Nothing referenced the three shadows, checked first.

  Result, verified end to end:

  | | before | after |
  |---|---|---|
  | `type-*` groups | `intent=org-group`, `UNMATCHED` | **`intent=group`, `LINKED`** |
  | `recompute` on the three roles | HTTP 409 AlreadyExistsException | **succeeds** |
  | org-owned groups | 15 `LINKED` | 15 `LINKED`, untouched |
  | FreeIPA built-ins | `UNMATCHED` | `UNMATCHED` — correct, midPoint does not own them |

  Group shadow count is unchanged at 25, and all five reconciliations pass afterwards
  (account 12, group 27, role 10, hostgroup 11, hbacrule 9).

  Correlator status across the resource, for completeness:

  | objectType | name produced from | correlates on | verdict |
  |---|---|---|---|
  | `hbacrule` | `role.identifier` | `identifier` | correct |
  | `role` | `role.name` (weak, resource-level) | `name` | correct |
  | `group` | `role.identifier` (strong, metarole) | `identifier` (fixed) | correct |
  | `org-group` | org `name` / `orgKey` | `name` with `polyStringNorm` | correct — now receives only org-owned groups and built-ins |
  | `hostgroup`, `org-hostgroup` | `orgKey + "-hosts"` | `name` | latent — textually cannot match; currently masked because midPoint provisioned every one of them |

  An earlier revision of this document claimed this mismatch was what made §5.2 reachable.
  That was overstated: §5.2 fires when a group named in `memberof_group` is absent from
  FreeIPA, which is a different condition from a group that exists but is unlinked. The two
  are independent defects and the §5.2 fix stands on its own.
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
- The midPoint deployment configuration, including the tasks that consume these features.
  An earlier revision of this document claimed three live objects had never had a file; that
  was wrong. `[PROVISION] FreeIPA HBAC Rules`, `[PROVISION] FreeIPA Project Group
  Memberships` and `[RECON] FreeIPA:OrgHostGroup` are all versioned in the
  `Midpoint_groups_test` project as `tasks/provision/Provision_FreeIPA_HBACRules.xml`,
  `tasks/provision/Provision_FreeIPA_ProjectGroupMemberships.xml` and
  `tasks/reconciliation/Recon_FreeIPA_OrgHostGroup.xml`. That is the right place for them —
  they are deployment configuration, not connector code — so nothing needs importing here.

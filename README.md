# connector-freeipa

Polygon/ConnId connector for FreeIPA

## Description

Connector for [FreeIPA](https://www.freeipa.org/) using [REST API](https://www.freeipa.org/page/API_Examples).

## Capabilities and Features

* Schema: YES
* Provisioning: YES
* Live Synchronization: No
* Password: YES
* Activation: YES — through the `nsaccountlock` attribute, not midPoint's native activation
* Script execution: No

FreeIPA Connector contains support for USER, GROUP, ROLE, HOSTGROUP, HBACRULE and HOST
entities.

> [!note]
>
> The connector deliberately does not advertise `__ENABLE__` (see
> `DISABLE_ADMINISTRATIVE_STATUS`, a workaround for MID-5883), so map `ri:nsaccountlock` in
> the resource rather than relying on `administrativeStatus` alone.

### Membership

Host-group and HBAC-rule membership cannot be set through FreeIPA's `*_mod` commands — it
rejects those attributes as unknown options. The connector maintains membership with the
`*_add_member` / `*_remove_member` family instead, and supports add, remove and replace;
replace is computed as a diff against the current value.

FreeIPA reports a refused member inside the `result.failed` block of an otherwise successful
response. The connector inspects that block and fails the operation, so a member FreeIPA
never stored is not reported to midPoint as provisioned. Adding a member that already exists,
and removing one that does not, remain successful no-ops.

`host` entries are keyed on `fqdn` and are created with `force`, so a name that has no DNS
record is still accepted — but FreeIPA does require the name to be fully qualified. A host's
`fqdn` is immutable, so rename is refused rather than attempted.

See [CHANGES.md](/CHANGES.md) for the reasoning behind each of these.

## Build

[Download](https://github.com/inalogy/midpoint-connector-freeipa) and build the project with usual:

```
mvn clean install
```

> [!note]
>
> The version number, contained within the jar name, will correspond the one set for the project within the `pom.xml`.

After successful the build, you can find `connector-freeipa-x.y.z.b.jar` in `target` directory.

## Upgrading

The schema changed in 1.3.x — ten membership attributes widened to multi-valued, `dn` added
on `group` and `role`, `memberof_permission` declared, four attributes no longer reported as
required, and three new object classes. **Refresh the resource schema as part of the upgrade:**

1. Deploy the jar and restart midPoint.
2. Repoint the resource's `connectorRef` at the new version.
3. Force a schema refetch — Resources → *resource* → Refresh schema.

> [!important]
>
> Replacing the jar and restarting is **not** enough. The cached resource schema survives
> both, so the connector runs new code against an old schema. An attribute the connector
> returns that is missing from the cached schema makes midPoint recurse until it throws
> `StackOverflowError` rather than reporting a clear error, so this step is not optional.
>
> Note also that a version bump only proves *a* build of that version is loaded, not *which*
> build. When iterating on a fixed version, confirm the running code is the one you just
> built before drawing conclusions from a test.

## Configuring resource

* create user in FreeIPA
* set membership to user groups: ipausers, trust admins, admins
* inspire by [sample](https://github.com/inalogy/midpoint-connector-freeipa/tree/master/sample) to configure your own resource

## Debugging

* Verify if service account has set password never expire, has "User authentication types" "Password" and not needed to change password at first log on.
* Try to log in with created service account (user) to FreeIPA web GUI & verify if you have required permissions to create/update/delete user, create/update/delete groups & roles and his memberships. If you use the `hostgroup`, `hbacrule` or `host` object classes, verify the same for those — including `hbacrule_add_user` / `hbacrule_add_host` and `hostgroup_add_member`, whose refusals are now reported as errors rather than silently ignored.
* Set up Logger for package "com.inalogy.midpoint.connectors.freeipa" to TRACE in midpoint over System/Logging/Loggers & verify midpoint.log for error details.
* In some cases FreeIPA misconfiguration cause to return HTML error page instead of JSON and this is shown as error message in Test Connection "org.json.JSONException(A JSONObject text must begin with '{' at 1 [character 2 line 1])"
* Set up Logger for package "org.apache.http" to TRACE in midpoint over System/Logging/Loggers & verify midpoint.log for other error details.

## License

Licensed under the [Apache License 2.0](/LICENSE).

## Status

FreeIPA Connector is intended for production use. Tested with MidPoint version `4.10.2`
against FreeIPA `4.12.2` (API 2.254). The connector was introduced as a contribution to midPoint project by [Inalogy](https://www.inalogy.com) and is not officially supported by Evolveum.
If you need support, please contact info@inalogy.com.

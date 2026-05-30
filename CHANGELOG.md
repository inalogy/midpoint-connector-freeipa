# Changelog

All notable changes to this project will be documented in this file.

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

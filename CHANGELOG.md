# Changelog

## Unreleased

### Added

## [0.6.0]

 - Added the ability to clone with source and target instances in different VPCs, by automatically falling back to `RestoreDBInstanceFromDBSnapshot`, instead of `CreateDBInstanceReadReplica`
   - The restore will be done based on the latest snapshot available, which is usually at most 24h stale.
 - Added `--restore-snapshot` to force even same-VPC clones to be done with `RestoreDBInstanceFromDBSnapshot` (it's currently faster)
 - Added a terrafrom environment for testing

## [0.5.0]

 - Added `--iam-policy` option for generating a IAM policy for a user or role to clone a replica with a minimal set of permissions.
 - Updated dependencies

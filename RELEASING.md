# Release Process

This document describes how to release SoftCard2 to Maven Central and
GitHub.

SoftCard2 builds against ReplicApple2Plus (its `SlotCard` interface), so a
SoftCard2 release follows a ReplicApple2Plus release and builds against
it. ReplicApple2Plus's own RELEASING.md covers that release.

## Prerequisites

- GPG key configured for signing
- Sonatype Central Portal credentials configured in `~/.m2/settings.xml`
  under server ID `ossrh`
- The `gh` CLI, signed in to GitHub
- Gradle wrapper present in the project
- The SoftCard II CP/M 2.28B master disk image, for the CP/M tests

## Version Numbering

The version is derived from Git tags by axion-release, with the tag prefix
`v`: tag `v1.0.0` builds version `1.0.0`. No manual version editing is
needed. An untagged commit builds the next SNAPSHOT version.

The README carries no version numbers to update; its Maven Central badge
follows the latest release by itself.

## Pre-Release Checklist

Before tagging, verify:

1. **ReplicApple2Plus is released**, and `replicApple2PlusVersion` in
   `gradle.properties` names that release, not a SNAPSHOT, so the release
   is built against a fixed `SlotCard` contract. (ReplicApple2Plus is a
   compile-only dependency, so the published POM doesn't name it.) Commit
   the change.
2. All changes are committed and pushed.
3. The project builds cleanly **with the master disk**, so the CP/M tests
   run instead of being skipped:
   ```bash
   ./gradlew clean build -PbootDisk=/path/to/image.dsk
   ```
   Check that nothing was skipped:
   ```bash
   grep -l 'skipped="[1-9]' build/test-results/test/*.xml
   ```
4. README.md and HARDWARE-REFERENCE.md are up to date.

> **IMPORTANT**: Do a full build verification BEFORE tagging. If a bug is
> found after tagging, the tag must be deleted locally and remotely, the
> bug fixed, and the tag recreated on the new commit. Moving tags after
> publishing to Maven Central is not possible.

## Release Steps

### 1. Tag the release

```bash
git tag v1.0.0
git push origin v1.0.0
```

Verify it produces the release version (no `-SNAPSHOT` suffix):

```bash
./gradlew properties | grep "^version:"
```

### 2. Publish and install

`publish` and `closeAndReleaseStagingRepositories` MUST run in the same
Gradle invocation -- the staging repository ID is not persisted between
invocations.

```bash
./gradlew publish closeAndReleaseStagingRepositories
./gradlew install
```

### 3. Verify Maven Central publication

Check that the artifacts appear at:
**https://central.sonatype.com/publishing/deployments**

Propagation to Maven Central typically takes 10-30 minutes after the
staging repository is released.

### 4. Create the GitHub release

The plug-in jar is what users install, so attach it:

```bash
gh release create v1.0.0 build/libs/SoftCard2-1.0.0.jar \
    --title "SoftCard2 1.0.0" --notes "..."
```

## Moving a Tag After a Bug Fix

If a bug is found after tagging but before publishing to Maven Central:

```bash
# Fix the bug and commit
git add <files>
git commit -m "Fix <description>"

# Delete tag locally and remotely
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# Recreate tag on new HEAD
git tag v1.0.0
git push origin v1.0.0
```

If the bug is found after publishing to Maven Central, the release cannot
be modified. A new patch release (e.g. `1.0.1`) must be made.

## Local Development Builds

For local development, use `install` instead of `publish`:

```bash
./gradlew install
```

## Dropping Failed Staging Repositories

If a publish fails after the staging repository is created, drop it via:

```bash
curl -X POST \
  -u "OSSRH_USERNAME:OSSRH_PASSWORD" \
  "https://ossrh-staging-api.central.sonatype.com/service/local/staging/bulk/drop" \
  -H "Content-Type: application/json" \
  -d '{"data":{"stagedRepositoryIds":["REPO_ID"]}}'
```

The staging repository ID is printed in the build output, e.g.:
```
Created staging repository 'com.nordstrom--abc123...' at https://...
```

A 204 response (no body) indicates success.

## Troubleshooting

**"No staging repository with name ossrh created"** -- `publish` and
`closeAndReleaseStagingRepositories` were run in separate Gradle
invocations. Always run them together in a single command.

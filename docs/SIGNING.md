# Signing: one keystore, every update

## The rule

**One keystore signs every release of this app, forever.**

Android decides whether an APK is an *update* by comparing two things: the
`applicationId` and the signing certificate. Same id + same certificate →
the new APK installs straight over the old one and the app's data (chats,
downloaded models, settings, API keys) is untouched. Different certificate →
Android refuses with "App not installed", and the only way forward is to
uninstall first, which deletes local data.

So the release key is created **once**, backed up carefully, and never
rotated. Everything below exists to make that boring and automatic.

## One-time setup (5 minutes)

On your own machine (JDK 17 installed):

```bash
./scripts/setup-keystore.sh
```

The script:

1. Generates `release.jks` (4096-bit RSA, ~27-year validity) in the repo
   root — git-ignored, never committed.
2. Writes `keystore.properties` so local `./gradlew assembleRelease` builds
   are signed with the same key.
3. Prints the four values to paste into
   **GitHub → Settings → Secrets and variables → Actions**:

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_B64` | the whole keystore, base64-encoded |
   | `KEYSTORE_PASSWORD` | the store password you chose |
   | `KEY_ALIAS` | `offlinegguf` |
   | `KEY_PASSWORD` | the key password |

4. Prints the certificate SHA-256 fingerprint for your records.

Then **back up `release.jks` + the password together** — a password
manager, an encrypted drive, a second machine. Not in the repo, not in a
chat, not in an issue. Whoever holds both can sign "updates" as you.

Re-running the script refuses to overwrite an existing keystore.
`./scripts/setup-keystore.sh --print-secrets` re-prints the GitHub values.

## How CI uses it

`.github/workflows/android-build.yml`, in the APK job:

1. *Decode the release keystore* — `KEYSTORE_B64` → `$RUNNER_TEMP/release.jks`,
   exported as `KEYSTORE_FILE`. The file exists only on the ephemeral runner.
2. *Build release APK* — Gradle picks the keystore up from the environment
   (see `signingConfigs` in `app/build.gradle.kts`).
3. *Show the release signing certificate* — `apksigner verify --print-certs`
   prints the signer SHA-256 into the build log, so every build proves which
   key signed it. Same fingerprint across releases = updates work.

Without the secrets, the release build falls back to the Android debug key
so CI artifacts stay installable for testing — but debug-signed builds do
**not** update release-signed installs (or vice versa).

## Migrating from a debug-signed install

If a user's installed copy came from the debug key (early CI builds before
the secrets were configured), the first properly-signed release cannot
update it. One-time fix: export any chats you care about (Chat → ⋯ →
Export), uninstall, install the new release. From then on, every update is
in place.

## If the key is ever lost

There is no recovery. A new keystore means every existing user must
uninstall + reinstall once. This is why the backup matters more than the
setup. (Play Store's app signing solves this by holding the key for you;
this app is distributed as direct APKs, so the key is ours to keep.)

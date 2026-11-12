# CI/CD Signing Guide for Play Store Releases

`app/build.gradle.kts` reads release signing credentials from four environment variables:

| Environment Variable | Purpose |
|---|---|
| `KITE_KEYSTORE_PATH` | Absolute path to the `.jks` or `.keystore` file on the build runner |
| `KITE_KEYSTORE_PASSWORD` | Keystore password (`storePassword`) |
| `KITE_KEY_ALIAS` | Signing key alias inside the keystore (`keyAlias`) |
| `KITE_KEY_PASSWORD` | Private key password (`keyPassword`) |

If `KITE_KEYSTORE_PATH` is unset or blank, `:app:bundleRelease` and `:app:assembleRelease` produce **unsigned** outputs, which Google Play rejects with:

> `403 Forbidden: All uploaded bundles must be signed. Please sign the bundle using jarsigner.`

---

## Option 1: Automated Setup via Script (Recommended)

Run `scripts/setup-ci-signing.sh` locally (it validates your keystore with `keytool` and uploads encrypted secrets via `gh secret set` without printing secrets to logs):

```bash
chmod +x scripts/setup-ci-signing.sh

# Generate a new Play Store upload keystore and upload GitHub Actions secrets:
./scripts/setup-ci-signing.sh --create --keystore "$HOME/.android/roku-upload.jks" --alias upload

# Or use an existing keystore and also verify a local signed .aab build:
./scripts/setup-ci-signing.sh --keystore "$HOME/.android/roku-upload.jks" --alias upload --build-aab
```

Keep a secure offline backup of your `.jks` file and passwords outside the repository. Never commit `.jks` or `.keystore` files to git.

---

## Option 2: Manual GitHub Actions Configuration

In your GitHub repository go to **Settings → Secrets and variables → Actions → New repository secret** and add:

1. **`RELEASE_KEYSTORE_BASE64`**: Single-line Base64 encoding of your `.jks` / `.keystore`:
   ```bash
   # Linux
   base64 -w 0 /path/to/roku-upload.jks
   # macOS
   base64 -i /path/to/roku-upload.jks | tr -d '\n'
   ```
2. **`RELEASE_KEYSTORE_PASSWORD`**: Your keystore password.
3. **`RELEASE_KEY_ALIAS`**: Your key alias (for example `upload`).
4. **`RELEASE_KEY_PASSWORD`**: Your private key password.

In `.github/workflows/release.yml`, decode the keystore into `$RUNNER_TEMP` (ephemeral runner storage, never checked into workspace) and pass all four `KITE_*` environment variables only to the Gradle build step:

```yaml
- name: Decode release keystore
  env:
    KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
  run: |
    test -n "$KEYSTORE_BASE64" || { echo "Missing RELEASE_KEYSTORE_BASE64 secret" >&2; exit 1; }
    printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"

- name: Build signed Play Store bundle (.aab)
  env:
    KITE_KEYSTORE_PATH: ${{ runner.temp }}/release.jks
    KITE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
    KITE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
    KITE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
  run: bash gradlew :app:bundleRelease

- name: Verify AAB signature
  run: jarsigner -verify -strict app/build/outputs/bundle/release/app-release.aab

- name: Clean up temporary keystore
  if: always()
  run: rm -f "$RUNNER_TEMP/release.jks"
```

---

## Option 3: Generic CI/CD or AI Studio Secrets

For GitLab CI, CircleCI, Jenkins, Bitrise, or local release builds:

1. Store the keystore file as a masked/protected file variable (or Base64 secret decoded at runtime to `$TMPDIR/release.jks` with `chmod 600`).
2. Mark `KITE_KEYSTORE_PASSWORD`, `KITE_KEY_ALIAS`, and `KITE_KEY_PASSWORD` as **masked/secret** environment variables so CI logs redact them.
3. Run:
   ```bash
   gradle :app:bundleRelease
   jarsigner -verify -strict app/build/outputs/bundle/release/app-release.aab
   ```
4. Upload `app/build/outputs/bundle/release/app-release.aab` to Google Play Console (ensure **Play App Signing** is enabled and the matching upload certificate is registered for `com.dhatech.roku.cast`).

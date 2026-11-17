# Install a CI build on a Fire TV

How a tester downloads an APK that CI built and installs it on a Fire TV over ADB. You need a computer with
[ADB](https://developer.android.com/tools/adb) (Android SDK Platform-Tools) on the same network as the TV, and a
GitHub account (GitHub only lets signed-in users download Actions artifacts).

## Pick the right APK

CI uploads these device-facing APKs ([CONTRIBUTING.md](../../CONTRIBUTING.md#ci-checks), `publish` job):

| Artifact | Built from | Kept | Package |
| --- | --- | --- | --- |
| `stationbreak-github-debug-pr<N>-<shortsha>.apk` | Pull request `<N>`, at commit `<shortsha>` | 7 days | `tv.stationbreak.app.debug` |
| `stationbreak-github-debug-main-<shortsha>.apk` | A push to `main` | 30 days | `tv.stationbreak.app.debug` |
| `stationbreak-github-benchmark-main-<shortsha>.apk` | A push to `main`. Release code, not debuggable, for timing runs | 30 days | `tv.stationbreak.app.benchmark` |

- Install only from a run whose conclusion is **success**. A red run has no APK, but a green attempt followed by a red
  re-run keeps the first attempt's APK.
- Check that `<shortsha>` in the file name is the commit you mean to test (for a PR, its head commit).
- Never install an `internal-*`, `debug-apk-runner-key` or `benchmark-apk-runner-key` artifact. Those are hand-offs
  between CI jobs, not signed with the CI debug key.
- A pull request from a fork has no APK: forks never receive the signing key
  ([ADR-0011](../adr/0011-debug-build-signing.md)).

The debug and benchmark builds have different package names, so both can be installed side by side.

## Download it

The artifacts are uploaded unarchived, so each one is the `.apk` itself, not a zip.

> **Don't use `gh run download`.** It treats the APK as a zip and unpacks it into a folder of `.dex` files and
> resources, which can't be installed.

### In a browser

1. Sign in to GitHub.
2. Open the repository's **Actions** tab, then the **CI** run for the commit you want (filter by branch, or follow the
   **Details** link of a check on the pull request and go to the run's **Summary**).
3. Under **Artifacts** at the bottom of the summary, select the APK's name. The browser saves the `.apk`.

### With the GitHub CLI

Find the run, list its artifacts, then download one by ID with `gh api`:

```sh
# Newest green CI run on main (for a pull request: --branch <its branch>)
gh run list -R StationBreak/stationbreaktv --workflow ci.yml --branch main --status success --limit 1

# The run's APKs and their artifact IDs
gh api repos/StationBreak/stationbreaktv/actions/runs/<run-id>/artifacts \
  -q '.artifacts[] | select(.name | endswith(".apk")) | "\(.id) \(.name)"'

# Download one. The endpoint is called zip, but for these artifacts it returns the APK itself.
gh api repos/StationBreak/stationbreaktv/actions/artifacts/<artifact-id>/zip > <artifact-name>
```

`file <artifact-name>` should report `Android package (APK)`.

### Check the signature (optional)

Every CI debug and benchmark APK is signed with the CI debug key. With a JDK installed:

```sh
keytool -printcert -jarfile <artifact-name> | grep SHA256
```

The fingerprint must match the one under [Debug signing key](../../CONTRIBUTING.md#debug-signing-key) in
CONTRIBUTING.md. Don't install an APK that doesn't match.

## Turn on ADB debugging on the TV

1. On the TV, open **Settings → My Fire TV** (on some models **Device** or **Device & Software**).
2. If there is no **Developer options** entry, open **About** and select the device name seven times, then go back.
3. In **Developer options**, turn **ADB debugging** on.
4. Find the TV's IP address under **My Fire TV → About → Network**.

## Install

```sh
adb connect <tv-ip>:5555        # the first time, the TV asks you to allow this computer
adb install -r <artifact-name>  # -r installs over the build already on the TV, keeping its data
adb shell am start -n tv.stationbreak.app.debug/tv.stationbreak.app.MainActivity
```

For the benchmark build, use `tv.stationbreak.app.benchmark` in the last line. The app also appears under
**Your Apps & Channels** on the TV.

If the install fails:

| Error | Meaning | What to do |
| --- | --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy has a different signature, usually a debug build from someone's own machine | `adb uninstall tv.stationbreak.app.debug`, then install again. This removes the sign-in and settings |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK is from an older run than the installed one (CI uses the run number as the version code) | Debug build: `adb install -r -d <artifact-name>`. Benchmark build (not debuggable): uninstall it first |
| `failed to connect` / `unauthorized` | ADB debugging is off, the IP is wrong, or the prompt on the TV wasn't accepted | Check the steps above, then `adb kill-server` and connect again |

## When you're done: turn ADB debugging off

ADB debugging lets a computer on your network install and run anything on the TV, so leave it off between test
sessions ([ADR-0011](../adr/0011-debug-build-signing.md), device hygiene):

```sh
adb disconnect <tv-ip>:5555
```

Then on the TV, **Settings → My Fire TV → Developer options → ADB debugging → Off**.

## Test reports from a fork

When instrumented tests fail, the run also has an `instrumented-test-reports` artifact (HTML report, JUnit XML,
logcat). It is uploaded for pull requests from forks too, and a fork writes its own HTML report, so opening that
report runs the fork's JavaScript in your browser. For a fork's pull request, read the JUnit XML or the job log and
don't open the HTML report.

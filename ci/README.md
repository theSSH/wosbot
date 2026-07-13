# Continuous Integration

This directory contains a ready-to-use GitHub Actions workflow for building and
testing Frostguard.

## Activating the workflow

Because the automation account that opened the improvement PR does not hold the
`workflows` GitHub-App permission, the pipeline is shipped here instead of under
`.github/workflows/`. A maintainer can enable it with a single move:

```sh
mkdir -p .github/workflows
git mv ci/github-actions-ci.yml .github/workflows/ci.yml
git commit -m "ci: enable build & test workflow"
git push
```

## What the pipeline does

- Checks out the repository **with Git LFS** (required for the bundled native
  `.dll` / `.exe` / `.traineddata` binaries).
- Sets up **Temurin JDK 21** with a Maven dependency cache.
- Runs `mvn clean install`, which compiles every module and executes the
  JUnit 5 regression tests.
- Uploads the packaged desktop bundle and the Surefire test reports as build
  artifacts.

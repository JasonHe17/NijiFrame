# Contributor guidance

Read `README.md`, `CONTRIBUTING.md`, and the applicable design documents before implementation. The repository contains an Android foundation app, plain JVM editor-core and native image-core. Use docs/development.md for portable build commands and docs/validation.md for verification scope.

- Use repository-relative configuration and documented environment variables. Keep workstation setup, private data, credentials, signing material, and research-only assets outside tracked product files.
- Preserve original images. Keep editable state independent from Android UI types and platform file handles.
- New project-owned code uses GPL-3.0-only. Check code, model, asset licenses, and relevant patent claims separately before adopting a dependency or algorithm.
- Validate the changed behavior through the real import/edit/save/export path when applicable. Distinguish host, emulator, physical-device, and desktop results.
- Design documents are proposals until implemented and verified; do not present planned functionality as available.

## Branch routing

Use `develop` as the persistent integration branch and `main` for accepted milestones. Start short-lived feature/fix/documentation branches from `develop`, merge completed work into `develop`, and integrate accepted milestones into `main`. Synchronize the resulting mainline merge back to `develop`; both may share a commit at a milestone boundary, while development work may otherwise lead main.

Resolve source and destination refs before any merge or deletion. “Development branch” means `develop`; do not substitute `main`. Preserve unmerged work and archive diagnostic commits before removing their branch refs. Follow the detailed workflow in `CONTRIBUTING.md`.

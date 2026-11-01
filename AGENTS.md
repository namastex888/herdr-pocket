# Agent rules

Short rules for coding agents working in this repo. Humans: the same rules apply.

## Layout

- `android/`: the app (Gradle). Run Gradle from there: `cd android && ./gradlew …`.
- `desktop/pocket-link/`: the Herdr desktop mod.
- Root: `version.properties`, `scripts/version.sh`, `docs/`, `.github/`.

## Working

1. Work in your own git worktree and branch. Don't switch branches, stash or reset in a shared
   checkout.
2. Open pull requests into `dev`. Never push to `dev` or `main` directly.
3. Commit subjects are conventional, `type(scope): description`; CI rejects others. `feat`, `fix`,
   `refactor` and similar cut a release on merge; `docs`, `ci` and `test` don't
   ([releasing](docs/guides/releasing.md)).
4. Before a pull request: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
5. Never commit or print the signing key or its password. Never change the app ID, the signing key
   or the `latest.json` format without an RFC.

## Docs

- Start at [docs/_index.md](docs/_index.md). Keep it an Obsidian vault: frontmatter on every note,
  relative links.
- A contract, privacy or permission change needs an RFC first
  ([RFC 0000](docs/rfcs/0000-rfc-process.md)). One choice with one reason: a decision note.
- Cite code as `path:line`. Never invent a decision or a reason; put unknowns under Open
  questions.
- A change to what the app collects, sends or asks permission for updates
  [docs/privacy.md](docs/privacy.md) in the same pull request.
- Write action first, short, skimmable. No hostnames, IPs, personal data or private repo links.

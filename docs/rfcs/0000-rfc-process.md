---
rfc: "0000"
title: "How this repo proposes and records changes"
status: Draft
created: 2026-11-01
pr: "#21"
---
# RFC 0000 — How this repo proposes and records changes

## Summary

Big changes to Automagik Pocket start as a short Markdown proposal in `docs/rfcs/`. Smaller,
already-made choices are kept as notes in `docs/decisions/`. Both live in the `docs/` vault,
so people, coding agents and Brain read the same files. This RFC says when to write one, what
goes in it, how its status moves, and how decisions are written down.

## Motivation

- Until now the reasons behind a change lived only in pull request bodies. They are hard to find
  later and easy to lose.
- Agents do a lot of the work here. They need one place to check what was decided before they
  propose something new, and a rule that stops them from making up a reason that nobody gave.

## Current state

- Before this change the repo had no `docs/` folder. Its README carried both user and maintainer
  material.
- Pull requests are the only record of decisions so far (#1 to #19 on
  `automagik-dev/automagik-pocket`).
- Every pull request commit must have a conventional subject: `.github/workflows/commitlint.yml`
  (the `Check` step).

## Design

### 1. When to write an RFC

Write one before the work when the change:

1. adds or changes a contract that something outside the app relies on: the `latest.json` update
   feed, the pairing link and its protocol, the herdr-core wire protocol, the signing key, the
   app ID;
2. touches privacy, security, permissions or what leaves the phone;
3. adds a new component to the repo (for example the planned `meter/`);
4. reverses an earlier decision.

Skip the RFC for bug fixes, refactors that keep every contract, UI polish, tests and docs. Those
go straight to a pull request.

A **decision note** (`docs/decisions/`) is enough for one choice with one reason, such as a default
setting. It uses the same decision lines as an RFC.

### 2. Files and numbers

- One file per RFC: `docs/rfcs/NNNN-short-slug.md`. Numbers are four digits, start at 0001 in this
  repo, go up by one, and are never reused. 0000 is this process.
- Each repo numbers its own RFCs. To point at another repo's RFC, write its repo name in front:
  `automagik-herdr RFC 0002`. Link it only when that repo is public.
- Decision notes have no number: `docs/decisions/short-slug.md`.
- Start from the templates: [`_Templates/rfc.md`](../_Templates/rfc.md) and
  [`_Templates/decision.md`](../_Templates/decision.md).

### 3. Statuses

| Status | Means | To get there |
|---|---|---|
| Draft | Being discussed; anything can change | The file exists with every section |
| Accepted | This is the plan; work can start | A dated owner line under Decisions |
| Implemented | Merged on `dev` | The pull requests are listed in the frontmatter `pr` and in Related |
| Superseded | A newer RFC replaced it | The first line of Summary names the newer RFC |
| Withdrawn | Dropped | A dated line under Decisions says why |

A pull request that changes a status changes the frontmatter and the row in
[the index](README.md) together.

### 4. Decision lines

- Write each one as a full sentence: `Owner, YYYY-MM-DD: <what was decided>.` Add the reason only
  when someone wrote it down, and say where (a pull request, an issue).
- Only the owner's decisions start with "Owner". A reviewer, a bot or an agent proposes; write
  those as "Proposed by …" and keep them out of the Decisions list.
- **Do not invent decisions or reasons.** When nobody wrote down why, put the question under
  Open questions.
- For history written after the fact: a pull request that the owner merged into `dev` records
  the decision, dated by its merge day. Its reason comes only from the pull request text.
- Never delete an old decision. Mark it **superseded** and name what replaced it.

### 5. What goes in each section

The template has these sections, in this order, so a reader (or a search index) can jump to one:
Summary, Motivation, Current state, Design, Decisions, Alternatives considered, Open questions,
Related.

- **Current state** says what the code does today and cites `path:line`, for example
  `android/app/build.gradle.kts:25`. A claim that the code can't show says where it comes from.
- **Open questions** are numbered, and each says who answers it (usually the owner).

### 6. Writing rules

- Lead with the action or the answer. Background comes after.
- Short sentences, short paragraphs, lists and tables. A reader should get the point by skimming
  the headings and the first line of each section.
- An Implemented record of shipped work fits on about one screen.
- Concrete names over adjectives: file paths, setting names, field names.
- No personal data, hostnames, IP addresses, keys, tokens or private repo links. Refer to people
  by role ("the owner").
- Examples use made-up values (a machine called `laptop-1`, a repo called `example/app`).
- Relative Markdown links only, so the folder opens as an Obsidian vault and renders on GitHub.

### 7. Review and merge

- RFCs and decision notes go through a pull request into `dev`, like code, with a `docs(...)`
  commit subject. Docs-only merges don't cut a release.
- Anyone, including an agent, can draft or edit a Draft. Only the owner accepts.

## Decisions

- Owner, 2026-11-01: this repo keeps its documentation as a Brain-style vault in `docs/`, with
  RFCs, decision notes, guides and a privacy page; the README is rewritten for Android users first.
  (Recorded in PR #21, which the owner asked for as "Brain-style full".) The process text above
  is still a Draft until the owner accepts it; see open question 1.

## Alternatives considered

- **Pull request bodies only.** This is what the repo did up to #19. It loses the "why" once the
  PR scrolls away, and agents don't read old PRs before proposing.
- **GitHub issues for proposals.** Good for tracking work, weak for a design that needs to be
  read and cited later, and not part of the vault.

## Open questions

1. Is this process accepted as written? Merging this file is not acceptance; a dated owner line
   is. — the owner.
2. Should a CI check compare the index table with each file's frontmatter status? — the owner.
3. Can the owner hand acceptance of some areas (for example docs-only RFCs) to someone else? —
   the owner.

## Related

- [RFC index](README.md)
- [Decisions](../decisions/_index.md)
- [Docs home](../_index.md)

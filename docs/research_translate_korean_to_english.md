# Korean-to-English Translation Research

## Scope

- Translate every Korean passage in version-controlled UTF-8 text files.
- Include Java comments and strings, Markdown documentation, repository instructions, and tracked temporary research notes.
- Exclude generated output, dependency caches, the game runtime directory, binaries, and unrelated untracked files.
- Preserve source identifiers, configuration keys, paths, URLs, code fences, Markdown structure, and numeric references.
- Preserve the user's existing uncommitted change in `SmartMovingClient.java` while translating Korean text around it.

## Inventory

The initial Unicode scan found Korean text in 213 tracked files and approximately 42,221 lines:

| Area | Files | Korean lines |
|---|---:|---:|
| `src/` | 42 | 8,141 |
| `docs/` | 166 | 33,966 |
| `.tmp_research/` | 1 | 49 |
| Repository root | 4 | 65 |

## Translation approach

Because the corpus is several megabytes, use an offline Korean-to-English machine-translation model for the mechanical first pass. Processing stays local after the model is downloaded. Translate only text containing Hangul and retain one input line per output line so source line references and code layout remain stable.

The translation pass must protect technical tokens such as inline code, URLs, identifiers, file paths, version numbers, issue labels, and formatting delimiters. After translation, restore protected tokens byte-for-byte and fail rather than write a file if any placeholder is lost.

## Validation

1. Scan all tracked text files for the Hangul Unicode range; the expected result is zero matches.
2. Confirm no unrelated untracked files are added and preserve pre-existing user changes.
3. Run `git diff --check`.
4. Compile and build the mod with the configured Java toolchain.
5. Review representative Java and Markdown diffs for syntax, formatting, and terminology.

## Translation tooling

Argos Translate is an open-source offline translation library using installable language-model packages. Its official documentation provides a Python API for downloading and installing a language pair, then translating locally. The Korean-to-English model is suitable for the bulk first pass; repository-specific validation remains necessary afterward.


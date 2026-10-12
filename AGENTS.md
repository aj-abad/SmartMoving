# SmartMoving Project Workflow Rules

## Workflow
When starting the implementation of a new feature or a complex task, you must follow the sequence below.

```
1. Research
└─ Investigate necessary APIs, references, existing code, etc.

2. Create Research File (docs/research_<topic>.md)
└─ Organize the findings into a Markdown file in the `docs` folder.

3. Create Task Checklist (docs/checklist_<topic>.md)
└─ Create a checklist of specific task steps based on the research file.

4. Execute Tasks
└─ Complete items one by one while referring to the research file and checklist. 
Mark completed items as checked in the checklist.
```

## `docs` Folder Rules
- Root path: `docs/`
- Research file naming: `research_<topic>.md`
- Checklist file naming: `checklist_<topic>.md`
- Retain files even after tasks are completed; do not delete them.

## Git Commit Rules
- Commit changes **after every interaction/step**.
- Follow the commit message conventions below.

### Commit Types
| Type | Description |
|------|------|
| `feat` | Adding a new feature |
| `fix` | Bug fix |
| `docs` | Adding/modifying documentation |
| `refactor` | Code refactoring (no functional changes) |
| `chore` | Build settings, dependencies, or other miscellaneous changes |
| `style` | Code formatting, typo corrections, etc. |
| `test` | Adding/modifying tests | ### Commit Message Format
```
<type>: <subject (imperative mood, under 50 characters)>

[body - optional, explain the reason for the change]
```

### Examples
```
feat: add player movement speed adjustment feature
chore: upgrade Gradle wrapper to 9.4.0
docs: add SmartMoving workflow documentation
```
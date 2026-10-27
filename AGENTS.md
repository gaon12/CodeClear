# Repository instructions

These rules apply to all work in this repository.

## Language and code structure

- Write project documentation, commit messages, and code comments in English.
- Use clear, specific language. Explain the purpose and relevant behavior of a change so a new contributor can understand it.
- Keep code intuitive: use descriptive names, small focused components, explicit dependencies, and a straightforward flow of data and state.
- Add comments where they explain intent, constraints, or non-obvious behavior. Do not add comments that merely repeat the code.
- Keep localized application strings in the appropriate language resources; the English documentation rule does not restrict supported UI or translation languages.

## Required implementation workflow

Follow this order for each small, coherent implementation change:

1. Edit the code for one focused change.
2. Run the applicable formatter and linter. Apply fixes and review the resulting diff.
3. Run the relevant tests and required checks against the formatted, lint-clean code.
4. Review and stage only the intended files or hunks, then create a Git commit.

If checks fail or any code changes after testing, fix the change and repeat formatting/linting and the affected tests before committing. Do not bypass checks or report checks as passed when they were not run. If a required check cannot run, document the reason and obtain an explicit exception before committing implementation code.

## Commit scope and messages

- Commit incrementally by feature, code responsibility, or file when that forms a complete, reviewable change. Do not accumulate the entire application into one commit.
- Keep dependent files together when needed for a coherent, buildable change. Do not split solely to achieve one commit per file.
- Separate unrelated features, broad formatting changes, and refactors from behavior changes where practical.
- Write an informative English subject. For nontrivial changes, add a clear body explaining the problem, behavior, important decisions, and validation performed.
- Inspect the staged diff before committing. Do not include unrelated existing work, generated output, credentials, local configuration, or ignored internal documents without explicit authorization.

## Performance and security requirements

- Complete the mobile app with on-device translation first. Server translation, backend/DB deployment, service-provider/BYOK integration, and server-cache features are deferred; retain their design decisions for later. The current mobile build must not expose working-looking server controls, request provider keys, or send alert bodies to a server. Model downloads are a separate, disclosed local-engine preparation step. Keep the capture-first validation gate before full product features.
- The selected minimum is Android 11/API 30. Reliable emergency-alert body capture is the primary acceptance criterion; translation API availability and successful builds do not establish capture support. The user rejected Android 7-10 support without reliable extraction evidence. Validate every supported version and source/firmware profile; do not claim universal capture merely because the device runs Android 11+. Keep the historical investigation in `.dev/11-android-7-to-10-feasibility.md` and the current capture decision in `.dev/12-capture-first-android-11-baseline.md`.
- Target Android 11+ smartphones and tablets from all manufacturers, not Galaxy alone. Watches, TVs, and automotive Android are outside the current scope. Use platform capabilities and validated alert-source adapters rather than manufacturer-based eligibility gates. Explain that Wi-Fi-only devices cannot use cellular emergency-alert reception/automatic capture; never confuse a cellular device currently using Wi-Fi, missing a SIM, or having mobile data disabled with Wi-Fi-only hardware. Keep radio capability, access, source support, and current connectivity separate.
- Account for Android 13+ restricted settings on affected install paths with user-controlled guidance, not a permission bypass. Recheck actual access after returning from settings.
- Deliver code with no known unresolved security vulnerabilities or performance defects within the change's scope. Identify and fix issues before committing or shipping.
- Keep collection callbacks and the main thread responsive. Bound extraction, memory use, concurrency, network work, retries, and cache retention; release resources and cancel stale work.
- Avoid unnecessary recomposition, database reads, model downloads, duplicate translation requests, and provider charges. Measure relevant performance goals before claiming they are met.
- Minimize permissions and data collection. Validate input, use parameterized queries, protect credentials, enforce authorization and cache ownership, and exclude sensitive data from logs and backups.
- Test meaningful failure and concurrency cases, including deletion, consent withdrawal, key changes, stale responses, and duplicate requests. Keep supported dependencies current through reviewed updates.
- Report the checks actually performed and any remaining limitations. Do not claim that code is issue-free without evidence.

## Internal documentation

- Maintain English requirements, designs, decisions, and development records in `.dev/`.
- Keep `.dev/` ignored by Git under the existing repository configuration unless the user requests otherwise.
- Resolve open product decisions and record platform feasibility evidence before feature implementation. Maintain the compatibility review in `.dev/09-android-compatibility-and-open-decisions.md` and the confirmed provider/cache decisions in `.dev/10-server-models-byok-and-public-cache.md`.
- Follow the detailed workflow in `.dev/08-development-workflow.md`. Documentation-only changes need document validation; do not run unrelated application tests solely for prose edits.

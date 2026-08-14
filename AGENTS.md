

=== SCOPE-LIMITS-SHORT (bounds what you PROPOSE, never what you look for) ===
Report anything actually wrong here, including a rare-looking case this project
really produces. Then keep the fix in scope:
1. Not a security paper: assume a cooperating operator on their own machine
   unless this project says otherwise. Verification is welcome; over-defense is
   not.
2. No hash, checksum or fingerprint unless it replaces a materially more
   expensive operation AND its result changes what happens next.
3. No feature flags, migration frameworks, compat layers or wrappers for cases
   that do not occur here.
4. Exotic encodings, symlink races and millisecond races are out of scope unless
   reachable through this project's supported use. Reachable is enough;
   constructible in principle is not.
5. Where judgement is needed, judge — not a scoring table, a checklist, or a
   re-run of something already settled.
6. None of this overrides security, migration, verification or review that the
   user, this project's conventions, or a higher-priority rule asked for.
Before any check: what specific failure would this detect, and what would I do
differently if it occurred? No answer means do not run it.
Say plainly when something is correct. Do not manufacture findings.
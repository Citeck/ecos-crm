# ecos-crm

ECOS application with the CRM artifacts: types, journals, forms, boards, patches. Until the
`phoneDigits` task the project had no JVM code at all — it is still an artifacts project, the Java
under `src/test` exists only to run and guard the scripts embedded in those artifacts.

## Build and test

```bash
mvn clean package -DskipTests   # -> target/classes/apps/ecos-crm.zip
mvn test                        # requires JDK 17+, see the trap below
```

⚠️ **`mvn test` reports BUILD SUCCESS without running anything on a JDK below 17.** `pom.xml` sets
`maven.test.skip=true` in the top-level properties and only the `jdk17-tests` profile
(`<activation><jdk>[17,)</jdk>`) flips it back to `false`. The property is `maven.test.skip`, not
`skipTests`, so on an old JDK even test *compilation* is skipped — sources that do not compile
still produce a green build. Always read the `Tests run: …` line, never the exit code or the
`BUILD` result alone. Reproduce the old-JDK path with `mvn clean package -P '!jdk17-tests'`.

The test-only dependencies (JUnit 5, `org.graalvm.js:js`, snakeyaml, surefire) live inside that
profile, not in the main `<dependencies>`.

## Testing computed SCRIPT attributes

A computed attribute is a JS snippet inside a type YAML, so it deploys fine whatever it does.
`src/test/java/ru/citeck/ecos/crm/phonedigits/ComputedScriptRunner` runs such a snippet on a real
GraalJS engine with the same host-access settings the platform uses:

```java
ComputedScriptRunner.ofTypeAttribute(ComputedScriptRunner.typeFile("opportunity"), "phoneDigits")
    .executeToStringList(Map.of("contacts[].contactPhone", List.of("+79161177716")));
```

**The script is always read out of the YAML — never keep a reference copy of it in a test.** A copy
drifts silently and the test then guards the copy instead of the artifact. For the same reason
`value.load(att)` throws when the fixture does not declare `att`: an undeclared attribute means the
fixture and the script disagree, which must fail rather than return null.

`docs/phone-digits.md` is verified against `opportunity.yml` by `PhoneDigitsContractDocTest` — the
listing is compared line by line and every row of its example table is replayed against the real
script. Editing the script without regenerating the listing fails the build.

The same class also compares `collectPhoneKeys` with its copy in the sibling clone `ecos-datalist`.
That comparison needs the clone to be next to this one, which is never true on the Jenkins agent, so
by default a missing clone **skips** the check and a green CI run proves nothing about the symmetry
of the copies. Run `mvn test -Dphonedigits.requireSiblings=true` before a release or after touching
the normalization: the flag turns a missing or outdated sibling into a build failure.

## Verifying on a stand (Records API / Playwright)

Gotchas established while accepting `phoneDigits`, all of them silent:

- a `deal`/`lead` created through the Records API **without an explicit `manager`** is invisible
  even to an admin: `records_mutate` returns `ok: true`, reads come back empty and a later mutation
  gives HTTP 500 `Access denied` — the computed role in `opportunity.yml` resolves to
  `GROUP_crm-manager`;
- journals must be opened in `ws=crm-workspace`; the personal workspace of the admin has no records
  and every journal looks empty;
- `not-eq counterparty ""` also returns records with an empty `counterparty` — use `not-empty`;
- `empty` on a JSON multiple attribute treats `[]` as non-empty, so fill rates can only be measured
  by sampling, never by a counter;
- `lead` goes through `DEPLOYED → DEPS_WAITING → DRAFT → DEPLOYED` while it waits for its
  `opportunity` parent — budget a second observer pass when checking a deploy;
- a predicate over a path **inside a JSON attribute** (`contacts.contactPhone`) is cut to
  `alwaysFalse` and returns zero records without an error; a path **through an association**
  (`counterparty.phoneDigits`) is a different mechanism and does work.

## Deploy

See the global instructions: `mvn clean package -DskipTests`, then copy
`target/classes/apps/ecos-crm.zip` into the `ecos-apps` deploy directory of the launcher namespace.

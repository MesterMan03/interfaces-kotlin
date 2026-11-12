# Publishing the MESTER fork

Publishing follows [MESTER's setup](https://github.com/MesterMan03/MESTER/blob/main/.github/workflows/publish-packages.yml):
Vanniktech Maven Publish 0.37.0, Maven Central, in-memory signing, and a version-change workflow.
Noxcrew's repository and credentials are no longer used for publication. Original package names and MIT attribution are retained.

## Coordinates and local development

The version is `interfaces_version` in `gradle.properties`; the initial development version is `2.2.0-mester.1-SNAPSHOT`.

| Module | Coordinates |
| --- | --- |
| Bukkit library | `info.mester.interfaces:interfaces:2.2.0-mester.1-SNAPSHOT` |
| Platform-independent API | `info.mester.interfaces:interfaces-api:2.2.0-mester.1-SNAPSHOT` |

The Bukkit module transitively depends on the API module. Publish both together. Builds use Java 25 and the pinned
`paper_version` from `gradle.properties`; update that property deliberately when changing the supported Paper build.

```powershell
.\gradlew.bat spotlessApply build publishInterfacesToMavenLocal
```

Local publication requires no signing credentials and never contacts a remote publishing service.

## GitHub Actions setup

In **MesterMan03/interfaces-kotlin → Settings → Secrets and variables → Actions**, add the same four secrets used in MESTER:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_KEY` | Entire ASCII-armored private signing key, including its header/footer; not base64 |
| `SIGNING_PASSWORD` | Signing key passphrase |

These are repository secrets, so MESTER's existing secrets do not automatically become available to this fork. An organization
secret already shared with both repositories can be used instead. Do not place credentials in this repository.
Use the existing Central account authorized for `info.mester` and its subnamespaces, and the same registered signing key.

Push the feature branch and merge it into `main` after review. The **Build** workflow only builds/tests; it never publishes.
The **Publish to Maven Central** workflow publishes when `interfaces_version` changes on `main`, or when run manually through
**Actions → Publish to Maven Central → Run workflow**. A `paper_version`-only change does not publish automatically.
For immutable releases, change the version to a unique value without `-SNAPSHOT`, such as `2.2.0-mester.1`.
Release publication uses automatic release after Central validation. Do not reuse a released version.

For a local remote publication, supply these Gradle properties through environment variables or your user Gradle properties file:
`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, and `signingInMemoryKeyPassword`.
Then run `./gradlew spotlessCheck build publishInterfacesPackages`.

## Consuming in ChronoCore

Published releases resolve from `mavenCentral()`. Published snapshots additionally need
`https://central.sonatype.com/repository/maven-snapshots/` in both Gradle repositories and the Paper loader's Maven resolver.
When adopting a published fork version, update **both** `build.gradle.kts` and
`src/main/kotlin/de/obsium/mmocore/Loader.java` to the same `info.mester.interfaces:interfaces` coordinates.
Keep the transitive API module available to the runtime resolver. Remove the Noxcrew dependency so both implementations
cannot be loaded into the same plugin classloader.

The current ChronoCore runtime dependency remains upstream pending live inventory validation and the first fork publication.
Use `-PuseVendoredInterfaces=true` for a compile/check against this checkout without changing production dependency coordinates.

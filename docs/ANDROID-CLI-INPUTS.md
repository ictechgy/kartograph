# Android CLI inputs and local plugin installation

Use compiled inputs from one named variant and record the build command, source revision, class roots,
dependency classpath and Android SDK. A smaller graph from incomplete inputs is not evidence of fewer
dependencies. The plugin supplies variant inputs; the CLI requires you to select them explicitly.

## Install a retained plugin JAR

The release asset `kartograph-gradle-plugin-<version>.jar` embeds Kartograph's runtime dependencies.
Use the matching CLI, plugin and compiler collectors. Verify the asset against the release's
`SHA256SUMS` before using it. A locally built candidate can be produced with
`./gradlew :gradle-plugin:jar`; it is not a published release.

For a local file, add the JAR to the buildscript classpath and apply its plugin ID. For example, in a
Groovy `build.gradle` for an Android module whose AGP is already configured:

```groovy
buildscript {
    dependencies {
        classpath files(rootProject.file('tools/kartograph-gradle-plugin-<version>.jar'))
    }
}

apply plugin: 'io.github.ictechgy.kartograph'

kartograph {
    reportFormat.set('json')
    includeSourcePaths.set(true)
}
```

Replace `<version>` with the artifact you actually have. Do not also resolve a different Kartograph
version through `plugins {}`. The local JAR path does not use the Plugin Portal's marker artifact.
The repository's [AGP 8 consumer fixture](../fixtures/agp-8-smoke/build.gradle) uses the same application
mechanism and accepts `-Pkartograph.pluginJar=/absolute/path/to/the/retained.jar` for package verification.
This fixture evidence does not establish compatibility with every AGP or corporate repository.

If your organization already mirrors the publication in Nexus or another Maven repository, the
implementation coordinate produced by this build is
`io.github.ictechgy.kartograph:gradle-plugin:<version>`. It can replace `files(...)` in the
`buildscript.dependencies.classpath` declaration; configure that existing repository in
`buildscript.repositories`. The `plugins { id 'io.github.ictechgy.kartograph' version '...' }` form
instead needs the plugin marker POM as well as the implementation publication in a repository visible
to `pluginManagement.repositories`. Mirroring only the GitHub JAR does not create either Maven
publication. Use the generated POM and retained JAR from the same build, preserve their identity, and
follow your existing repository/authentication procedure. This guide does not publish or configure a
repository. See Gradle's [plugin application documentation](https://docs.gradle.org/current/userguide/plugins_intermediate.html).

## Resolve the variant's dependency classes

An Android `CompileClasspath` can contain AARs and transformed artifacts. Do not assume that listing
the raw configuration files yields the class JARs the analyzer needs. In an Android module's Groovy
`build.gradle`, this task requests AGP's class-JAR view of a specific compile classpath:

```groovy
def kartographDependencyJars = configurations.named('debugCompileClasspath').get()
    .incoming.artifactView {
        attributes {
            attribute(org.gradle.api.attributes.Attribute.of('artifactType', String),
                'android-classes-jar')
        }
    }.files

tasks.register('writeKartographClasspath') {
    inputs.files(kartographDependencyJars)
    def destination = layout.buildDirectory.file('kartograph/debug-classpath.txt')
    outputs.file(destination)
    doLast {
        def output = destination.get().asFile
        output.parentFile.mkdirs()
        output.text = kartographDependencyJars.files.collect { it.absolutePath }.sort().join('\n')
    }
}
```

Choose the real variant name, such as `demoDebugCompileClasspath`, and build its project dependencies
before analysis. AGP provides the `android-classes-jar` transform; this is not a generic JVM artifact
type. Keep resolution failures visible instead of making the view lenient and silently dropping a
dependency. Gradle explains [artifact views and transforms](https://docs.gradle.org/current/userguide/artifact_views.html).
The existing [public-sample script](../Scripts/public-sample-classpath.gradle) uses the same artifact
view with `demoDebugRuntimeClasspath` for that sample's explicitly documented runtime analysis.
Compile and runtime classpaths have different scopes; record which one you selected.

Pass each listed class JAR with `--classpath`, and also supply the selected Android SDK's
`platforms/android-<api>/android.jar`. For example:

```bash
kartograph graph --project /path/to/project \
  --classes /path/to/variant/project-classes \
  --classpath /path/to/resolved/dependency-classes.jar \
  --classpath /path/to/android-sdk/platforms/android-35/android.jar \
  --include-paths --format json
```

The generated text file is an inventory, not an implicit CLI argument file. Read it in your build
integration and pass each path as a separate argument. The paths are local build inputs; do not
commit them. `android.jar` supplies framework hierarchy headers, not the Android runtime or proof that
all framework callbacks are modeled. Include the additional dependency classes actually used by the
variant. Missing hierarchy or unsupported dynamic paths remain analysis limitations.

## Select post-transformation project outputs

Hilt and other AGP transforms can change superclasses after `tmp/kotlin-classes` or javac output is
written. Feeding only those earlier directories can omit `Hilt_*` relationships. Use the chosen
variant's post-transformation class directories/JARs, or the plugin's variant integration, and keep
dependency headers separate from project roots. AGP intermediate directory names are not a stable
cross-version API; do not copy a `transformDebugClassesWithAsm` path from another project and assume
it is the final artifact. [Public validation](PUBLIC-VALIDATION.md) records the concrete transformed
inputs used in the published sample. A class name or `AndroidEntryPoint` annotation alone does not
prove that a supplied output has undergone the transform.

Merged manifests and resource XML must describe the same variant as the class inputs. Other variants,
device-specific overlay winners, binary-only resource data and runtime registration can remain
unobserved. Preserve the report's input hints and limitations when interpreting findings.

## Match plugin graph export to the CLI

The 0.26.0 candidate adds graph-specific settings to the extension; availability in a public package
must be verified separately. They affect `kartographGraph<Variant>`, not snapshot or dead analysis:

```groovy
kartograph {
    graphFormat.set('ndjson')             // json remains the default
    excludeOrigins.set(['dispatchModel']) // default: no explicit origin exclusion
    dispatchCandidateLimit.set(256)       // same default as CLI; supported range 1..1,000,000
    includeExternalStubs.set(false)       // opt in when external relation endpoints are needed
    includeSourcePaths.set(true)
}
```

`excludeOrigins` accepts `bytecode`, `kotlinMetadata`, `dispatchModel`, `runtimeModel` and
`compilerReference`, exactly as repeated CLI `--exclude-origin` values do. Each explicit exclusion
and each measured candidate-limit omission remains in the exported limitations. A dispatch group
above the limit is omitted as a whole; the tool does not choose an arbitrary subset. JSON and NDJSON
use the same graph selection and observed facts. The default output filename remains
`build/reports/kartograph/<variant>-graph.json`; choosing NDJSON changes its suffix to `.ndjson`.

Earlier plugin graph tasks used the full analysis index path, while the CLI used the bounded graph
export path. The candidate uses the CLI path and defaults in both, including disabling callback fact
harvesting for graph export. Node or edge counts can therefore change after upgrading the plugin.
For a meaningful comparison, provide identical project roots, post-transform class bytes, dependency
headers, Android SDK and explicit selection settings. Snapshot/impact callback evidence is unchanged.
The native regression compiles a separate interface library and several implementations, then
compares actual task output with a separate CLI process for both formats, exclusions, candidate caps,
source declarations and external stubs. It does not reproduce the reported private app's size or cost.

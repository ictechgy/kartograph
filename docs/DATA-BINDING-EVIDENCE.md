# DataBinding candidate evidence

This follow-up extends the 0.26 candidate. It does not infer that every annotated
adapter runs. The existing graph still needs a reached binding owner, a captured
framework callback header, and an actual compiled CALL to reach an adapter.
An unrelated overload or an unreached owner receives no new retention root.

`dead` and `why` now attach `data-binding-xml-candidate` diagnostics to exact adapter
IDs and XML locations. When XML makes an adapter eligible but no reached compiled
caller is observed, its finding needs runtime review. The graph can still say
`unreachable`: that is the graph result, while the diagnostic records an unresolved
runtime selection. All six existing dead report formats retain this distinction.
An unreachable class owning an unresolved adapter also needs review. Explicit
class runtime observations keep their existing `runtime-observed` confidence;
the XML uncertainty remains in diagnostics. An unobserved resource scope is
distinguished from supplied layouts with no matching expression; neither proves
that an adapter cannot run.
Snapshots retain these diagnostics, so their query/MCP consumers see them too.
These facts do not authorize deletion or skipping tests.

Unresolved adapters also carry uncertainty forward along observed `BYTECODE CALL`
edges, including calls into another source file. Those callees and their owning
classes need review in CLI and Gradle dead reports. Ownership does not traverse
the owner's other members. `why` shows the adapter and representative bytecode
call steps (including observed call lines); `bindings.callUncertainty` exposes
the same predecessor evidence. This changes confidence only: an `unreachable`
callee stays unreachable, and no retention root is added. Explicit runtime class
observations still take precedence over review confidence. Modeled dispatch,
field dependencies and unobserved runtime paths are outside this bounded proof;
absence from the displayed chain is not a deletion guarantee.

Call uncertainty has separate limits of 20,000 recorded declarations/owners and
200,000 examined graph edges. Exhausting either emits
`data-binding-call-uncertainty-truncated` and lowers all otherwise unreachable
findings to review because remaining paths are unknown. This fallback does not
claim that every declaration is a callback candidate: declarations without an
observed path receive no invented provenance. Detailed JSON steps share the
`--limit` row bound and explicitly report `rowsTruncated`; `why` limits its path
to 64 calls and labels a truncated sink-side suffix. Saved snapshots retain the
aggregate limitation; they do not add these candidate paths as graph edges.

For a detailed JSON report, use the same compiled inputs as `why`:

```sh
kartograph bindings --project . --classes build/classes --classpath android.jar \
  --manifest src/main/AndroidManifest.xml --resources src/main/res \
  --namespace example.app --variant debug
```

Other `why` input options, including repeated classpaths, keep rules and
`--include-private-members`, are accepted. Private-member mode retains its existing
conservative public-member entry policy; this report does not remove those roots.
`--variant` is an **input assertion**, not proof that classes, resources and processor
outputs came from that build. Input SHA-256 values are observed before and after
analysis. Equality means unchanged observed bytes; `buildFreshness` remains
`unverified` without a build witness. The report is always `incomplete`, or `stale`
when inputs change. Exit 0 means the report was produced, not that the code is safe.

The model reads `androidx.databinding.BindingAdapter` and its legacy Android
equivalent from the selected classfile. A similarly named annotation in another
package is not accepted. Attribute array order, `requireAll` (default true), JVM
value descriptors and old/new parameter pairs are preserved. For each XML View:

- `requireAll=true` needs every associated binding expression on that same element.
  Two attributes on different Views are never combined.
- `requireAll=false` permits some expressions to be absent. An ordinary XML literal
  is not counted as a binding expression.
- Custom attribute namespaces are ignored; the Android namespace stays distinct.
- Declared variable types are observations, not a compiler type solver. Conversions,
  imports/aliases, expression evaluation and runtime overload selection remain unknown.
- Exact View types and captured inheritance can distinguish compatible,
  incompatible and unknown receiver types. Unsupported signatures remain unknown.

The report preserves all matching overload declarations. `compiledCallers` and
`reachedCompiledCallers` are separate bytecode/graph observations; their coexistence
with an XML candidate does not prove that this XML element generated that call.
`xmlToCompiledLink` stays `unverified`. DataBinding component injection, custom
conversions, included-layout variable propagation and device configuration selection
are not inferred. Empty candidates do not establish absence of runtime use.

Resource roots normally form an order-independent, duplicate-independent union.
The detailed report can apply an explicit priority assertion:

```sh
kartograph bindings --project . --classes build/classes --manifest manifest.xml \
  --resources src/main/res --resources src/debug/res --namespace example.app \
  --resource-overlay src/main/res --resource-overlay src/debug/res --variant debug
```

List overlay roots from lowest to highest priority, and also supply each through
`--resources`. Only equal resource paths (including `layout-land`, other qualifiers,
and filename) are overlaid. A higher-priority XML without binding expressions still
shadows the lower definition. Different qualifiers remain alternatives. Unordered
roots remain a union. This is not an AGP merge receipt: ordinary dead/why, snapshot
and plugin inputs continue using the union and do not claim an overlay winner.

Limits are explicit: 2 MiB per XML, 16 MiB per adapter class, 64 MiB aggregate retained
class/XML reads, 100,000 resource entries, depth 128, 50,000 adapter/XML observations,
200,000 matching pairs and 1,000 result rows by default (`--limit 1..10000`). Budget
or unsupported-input failures retain unknown diagnostics. A result-row limit sets
`truncated`; omitted adapters remain unknown. Parsing rejects external XML entities.
These are size/work limits, not a wall-clock deadline. Preparation includes reading
the compiled graph, annotation values, XML and input hashes.

Semantic references: [binding adapters](https://developer.android.com/topic/libraries/data-binding/binding-adapters)
and [requireAll API contract](https://developer.android.com/reference/android/databinding/BindingAdapter#requireAll()).
Synthetic javac/XML regressions cover the boundaries above. The separate Android
integration fixture exercises the actual AGP DataBinding processor; its CI result
must be checked before claiming it ran. Neither fixture remeasures the private app's
reported 83%, 589 or 88 counts, or proves runtime execution on a device.

Android snapshot capture includes file inputs declared by compiler argument providers
through Gradle's public file-input annotations. Processor outputs and compiler output
directories are excluded. Inaccessible provider inputs are an explicit rejection in
the compiler task's input identity: ordinary compilation can succeed, but the witness
and snapshot remain unavailable. Unknown compiler inputs are never treated as fresh.
The separate Hilt processor fixture distinguishes a matched snapshot from a precise
capture refusal; refusal verifies the guard, not usable transformed snapshot support.

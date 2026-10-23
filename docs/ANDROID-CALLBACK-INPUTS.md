# Android callback and generated input boundaries

The 0.26 candidate connects reached Fragment, ViewDataBinding and DataBinderMapper
owners to a bounded set of callback implementations. It also includes Activity
`onCreate`, RecyclerView.Adapter `onBindViewHolder`, and View.OnClickListener
`onClick` under the same owner and exact-header conditions. The selected project or
dependency classfile must contain the matching framework method header with the
same JVM name and descriptor and an overridable visibility. Non-callback methods,
unrelated overloads and unreached owners receive no new root. These edges have
`runtimeModel` origin: they describe possible framework invocation, not an observed
execution or a guarantee that a callback executes.
Reaching an adapter or listener owner does not prove it was registered with a view;
these edges conservatively preserve possible invocation for review. Other callback
families remain unresolved unless separate compiled or retention evidence exists.

Supply the dependency hierarchy, including the selected Android SDK and AndroidX
classes. A class named `ScreenFragment` or a method named `onViewCreated` does not
suffice. Missing headers leave the callback unresolved. Existing keep/manifest/XML
retention policies remain separate from this owner-conditioned graph model.

For supported `androidx.databinding.DataBindingUtil.bind`, `inflate` and
`setContentView` static descriptors, the graph can enter the captured exact
`androidx.databinding.DataBinderMapperImpl` constructor if its hierarchy reaches
MergedDataBinderMapper and DataBinderMapper. The constructor's actual bytecode
connects registered application mappers; their proven callback implementations lead
to the concrete binding constructors and `executeBindings`. Compiled adapter CALL
edges preserve the selected overload. If the generated root mapper is absent, this
path remains unresolved. The model does not infer the winning layout ID branch,
device overlay, attribute type, `requireAll` combination or missing variant.
`data-binding-selection-partial` is retained in query/snapshot limitations, and the
dead report also exposes an input hint. All reached branches are review candidates.

Resource scanning records explicit qualified classes in binding-layout `import`
and `variable` types, including generic type arguments. Imported aliases are not
resolved as independent classes. XML attribute names are not used to declare every
similarly named BindingAdapter reachable. This avoids turning overloaded or unused
adapters into asserted runtime selections when the emitted binding code is missing.

The Hilt Gradle plugin rewrites the superclass of `@AndroidEntryPoint` and
`@HiltAndroidApp` declarations. If the selected classfile does not extend the
expected exact `Hilt_...` superclass, `hilt-transform-not-observed` asks for the
variant's post-instrumentation classes and generated outputs. An annotated source
declaration plus its exact generated sibling name provides generated provenance;
an unrelated `Hilt_...` name does not. See the
[official Hilt setup](https://dagger.dev/hilt/gradle-setup.html).

DataBinding/Navigation inheritance, `Args`, `Directions` and `BindingImpl` suffixes
do not establish that source was generated. In particular, the
[Safe Args Kotlin writer](https://github.com/androidx/androidx/blob/androidx-main/navigation/navigation-safe-args-generator/src/main/kotlin/androidx/navigation/safe/args/generator/kotlin/KotlinNavWriter.kt)
and [Java writer](https://github.com/androidx/androidx/blob/androidx-main/navigation/navigation-safe-args-generator/src/main/kotlin/androidx/navigation/safe/args/generator/java/JavaNavWriter.kt)
do not provide a universal classfile generation marker. Such declarations stay in
the graph and remain unsynthesized unless a supported marker or an explicitly
supplied generated-only class root establishes provenance. The
`android-generated-provenance-unverified` hint explains this boundary. Never mark
a mixed handwritten/generated output directory as generated-only.

Repeat `--resources` for each relevant XML resource root. `dead` and `why` accumulate
the normalized roots, so their order and repeated identical paths do not alter the
result. Compiled AGP `.flat` files are not XML: their presence now fails with an
actionable diagnostic rather than producing an empty successful scan. Supply
unpacked merged or source XML instead. An unreadable resource subtree also fails
the scan; partial retention evidence is not returned as a complete result. Source
directory indexing has its own independent diagnostic and isolation policy.

The regression fixtures compile synthetic Java dependency headers and application
classes. They reproduce the public 0.25 callback, generic mapper and Hilt gaps and
check unreachable owners, overloads, missing headers/mappers and handwritten name
lookalikes. They do not execute the Android runtime, the DataBinding processor, Hilt
instrumentation or Safe Args generation. They do not remeasure the reported private
application's candidate counts or establish accuracy across its build variants.

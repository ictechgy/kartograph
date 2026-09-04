# False-positive corpus

실제 Android compiler 산출물에서 보존 규칙의 양방향 동작을 검증하는 작은 앱이다.

| class | 기대 | 보존 근거 |
|---|---|---|
| `ManifestActivity` | 보고 금지 | Android manifest component |
| `XmlOnlyView` | 보고 금지 | XML layout custom view |
| `KeptByAnnotation` | 보고 금지 | `androidx.annotation.Keep` |
| `KeptByRule` | 보고 금지 | exact ProGuard/R8 `-keep class` rule |
| `KeptByWildcardRule` | 보고 금지 | class-name wildcard keep rule |
| `KeptByExtendsRule` | 보고 금지 | `public` + `extends` keep rule |
| `KeptByClasspathRule` | 보고 금지 | included rule + external library hierarchy |
| `KeptByConsumerRule` | 보고 금지 | library consumer rules |
| `KeptByRuleAnnotation` | 보고 금지 | annotated class keep specification |
| `KeptByMemberAnnotation` | 보고 금지 | member-level AndroidX `@Keep` |
| `ConditionalKept` | 보고 금지 | annotated `-keepclasseswithmembers` condition |
| `KeptByFinalRule` | 보고 금지 | required JVM `final` flag |
| `KeptWithAllMembers` | 보고 금지 | plain keep catch-all member block |
| `KeptByInject` | 보고 금지 | `javax.inject.Inject` constructor |
| `KeptBySerializable` | 보고 금지 | `kotlinx.serialization.Serializable` |
| `KeptClassWithMembers` | 보고 금지 | class-level AndroidX `@Keep` + members |
| `CorpusEntity` / `CorpusDao` / `CorpusDatabase` | 보고 금지 | Room annotations |
| `CorpusDatabase_Impl` | 보고 금지 | Room KSP generated sibling naming |
| `JavascriptBridge` | 보고 금지 | `@JavascriptInterface` runtime callback |
| `NativeBridge` | 보고 금지 | JNI native method |
| `CorpusWorker` | 보고 금지 | WorkManager worker inheritance |
| `CorpusService` | 보고 금지 | Retrofit HTTP method annotation |
| `OverrideOnlyDependency` | 보고 금지 | interface call에서 구현 method로 이어지는 override dispatch |
| `SyntheticCallbackDependency` | 보고 금지 | runtime이 호출하는 compiler synthetic callback body |
| `ManifestActivity$Companion` | 보고 금지 | reachable owner의 source companion object |
| `RuntimeImplementation$Companion` / `ClassInitializerDependency` | 보고 금지 | reached class의 JVM class initializer |
| `WebViewCallbackDependency` / `ViewModelCallbackDependency` | 보고 금지 | reached runtime callback class의 framework-invoked member |
| `InlinedConstantOwner` | 보고 금지 | bytecode reference가 사라지는 Kotlin `const val` owner |
| `CameraCallbackDependency` | 보고 금지 | reached Camera2 callback의 framework-invoked member |
| `PlainSignatureKept` | 보고 금지 | inline plain keep constructor specification |
| `ActuallyUnused` | 반드시 보고 | 보존 근거 없음 |
| `OpenFinalRule` | 반드시 보고 | final name pattern, but no final flag |

`OnlyCalledFromKeptMember`는 직접 보존 근거는 없지만 `KeptWithAllMembers`의 keep된 method가 참조하므로
보고되면 안 된다. 전체 finding exact 비교가 이 전이 도달성을 검증한다.
`OnlyCalledFromKeptClassMember`도 class-level `@Keep`이 보존한 method를 통해서만 도달한다.
`ManifestOwnedDependency`는 manifest가 소유한 component의 runtime member body를 통해서만 도달한다.
Room 표본은 KSP가 실제 생성하고 Kotlin compiler가 compile한 `CorpusDatabase_Impl.class`를 검사한다.

기계 판독 계약은 `expectations.tsv`에 있다. source line을 옮기면 evidence line도 함께 고쳐야 하며,
새 보존 계열은 “retain” 표본만 추가하지 말고 같은 채널이 아닌 실제 report 표본도 유지한다.

```bash
Scripts/verify-fixture-corpus.sh
```

verifier는 app과 consumer rules가 있는 library를 빌드하고 app class만 graph에 넣되 library classpath의
hierarchy와 AGP 9.3.2가 생성한 기본 optimize 규칙을 함께 읽는다.
같은 expectations 파일로
`dead`의 전체 finding 목록 추가·누락, `--strict` 종료 코드, 각 retain 표본의 `--explain` 근거를 검증한다.

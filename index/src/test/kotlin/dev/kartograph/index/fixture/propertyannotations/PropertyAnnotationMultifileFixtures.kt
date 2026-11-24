@file:JvmMultifileClass
@file:JvmName("PropertyAnnotationConstants")

package dev.kartograph.index.fixture.propertyannotations

/** multi-file `const val`에 붙는다. 합성 method는 part에, field는 facade `PropertyAnnotationConstants`에 있다. */
annotation class OnMultifileConst

/** 어노테이션을 단 multi-file 상수다. */
@OnMultifileConst
const val MULTIFILE_CONST: Int = 9

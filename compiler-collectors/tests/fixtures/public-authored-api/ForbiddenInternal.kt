package external.consumer

import dev.kartograph.core.CompilerSourceFacts

// 공개 소비자가 내부 helper를 볼 수 있다면 잘못된 friend/source classpath다.
fun forbiddenInternalAccess(): Any = CompilerSourceFacts

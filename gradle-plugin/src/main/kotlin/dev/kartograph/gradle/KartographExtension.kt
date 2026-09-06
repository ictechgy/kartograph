package dev.kartograph.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** variant task들이 공유할 사용자 지정 보존 입력과 strict 정책이다. */
public abstract class KartographExtension {
    /** 기존 class 보고를 유지하면서 private member 진단을 선택적으로 추가한다. */
    public abstract val includePrivateMembers: Property<Boolean>

    /**
     * AGP Variant API가 merged input으로 노출하지 않는
     * consumer keep 파일도 명시적으로 추가한다.
     */
    public abstract val keepRules: ConfigurableFileCollection

    /** finding이 있으면 task를 실패시킬지 선택한다. */
    public abstract val strict: Property<Boolean>

    /** CI와 IDE가 소비할 report 형식이다. */
    public abstract val reportFormat: Property<String>

    /** 기존 finding을 억제해 점진적으로 도입할 때 쓰는 baseline이다. */
    public abstract val baseline: RegularFileProperty

    /**
     * 그래프 JSON에 project 기준 source 경로를 해석해 실을지 선택한다.
     * 요청하지 않은 경로 노출을 만들지 않도록 CLI의 `--include-paths`와 같이 opt-in이다.
     */
    public abstract val includeSourcePaths: Property<Boolean>
}

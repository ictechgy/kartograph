package dev.kartograph.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** variant task들이 공유할 사용자 지정 보존 입력과 strict 정책이다. */
public abstract class KartographExtension {
    /** 의존성 분석에서 test 컴파일 결과도 명시적으로 수집하며 테스트 실행은 연결하지 않는다. */
    public abstract val dependencyIncludeTests: Property<Boolean>
    /** 컴파일 입력과 main/test snapshot의 자동 수집을 명시적으로 활성화한다. */
    public abstract val snapshotsEnabled: Property<Boolean>

    /** snapshot의 class 파싱 결과만 재사용하며 전체 분석·신선도 검사는 매번 수행한다. */
    public abstract val snapshotIndexCacheEnabled: Property<Boolean>

    /** 저장 snapshot의 reader/writer 자원 상한(MiB)이며 그래프 provenance 의미에는 포함하지 않는다. */
    public abstract val snapshotMaxMiB: Property<Int>

    /** 공개 snapshot과 분리한 로컬 파싱 캐시 위치이며 variant가 같은 class 바이트를 공유할 수 있다. */
    public abstract val snapshotIndexCacheDirectory: DirectoryProperty

    /** CI가 전달한 commit 라벨이다. 컴파일 신선도는 별도의 내용·producer 근거로 검증한다. */
    public abstract val snapshotRevision: Property<String>

    /** Kotlin snapshot compiler에 명시적으로 적용할 toolchain이다. 공개 KGP 인자에는 설치 경로가 노출되지 않는다. */
    public abstract val snapshotKotlinToolchain: Property<org.gradle.jvm.toolchain.JavaLauncher>

    /** 관례 경로 밖의 applied script·convention source·설정 파일을 명시적으로 fingerprint에 포함한다. */
    public abstract val snapshotBuildInputs: ConfigurableFileCollection

    /** 기존 class 보고를 유지하면서 private member 진단을 선택적으로 추가한다. */
    public abstract val includePrivateMembers: Property<Boolean>

    /**
     * AGP Variant API가 merged input으로 노출하지 않는
     * consumer keep 파일도 명시적으로 추가한다.
     */
    public abstract val keepRules: ConfigurableFileCollection

    /** 생성 전용 class directory/JAR를 지정한다. 해당 경로는 variant의 project class 입력에도 포함돼야 한다. */
    public abstract val generatedClassRoots: ConfigurableFileCollection

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

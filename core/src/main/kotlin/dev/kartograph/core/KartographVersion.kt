package dev.kartograph.core

import java.util.Properties

/** CLI와 모든 machine document가 공유하는 release artifact version이다. */
public object KartographVersion {
    /** root VERSION에서 build 시 생성된 resource의 semantic version이다. */
    public val current: String by lazy {
        val properties = Properties()
        val resource = requireNotNull(KartographVersion::class.java.getResourceAsStream("/dev/kartograph/version.properties")) {
            "release version resource is missing"
        }
        resource.use(properties::load)
        requireNotNull(properties.getProperty("version")) { "release version is missing" }
    }
}

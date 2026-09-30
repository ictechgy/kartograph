// 플러그인 버전만 고정한다. 서비스마다 자기 build 파일을 둔다 — kartograph가 서비스 루트에서 Boot 버전을 읽는다.
plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.spring") version "2.4.20" apply false
}

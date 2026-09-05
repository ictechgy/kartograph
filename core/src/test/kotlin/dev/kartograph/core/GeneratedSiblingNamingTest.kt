package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedSiblingNamingTest {
    @Test
    fun `Hilt application roots identify exact generated component siblings`() {
        assertEquals(setOf(
            "dev/fixture/Hilt_App", "dev/fixture/App_HiltComponents", "dev/fixture/DaggerApp_HiltComponents_SingletonC",
        ), GeneratedSiblingNaming.candidatesFor("dev/fixture/App", setOf("dagger/hilt/android/HiltAndroidApp")))
        assertEquals(emptySet(), GeneratedSiblingNaming.candidatesFor("dev/fixture/App", emptySet()))
        assertEquals(setOf("Hilt_App", "App_HiltComponents", "DaggerApp_HiltComponents_SingletonC"),
            GeneratedSiblingNaming.candidatesFor("App", setOf("dagger/hilt/android/HiltAndroidApp")))
        assertEquals(emptySet(), GeneratedSiblingNaming.candidatesFor(
            "dev/fixture/Outer${'$'}App", setOf("dagger/hilt/android/HiltAndroidApp"),
        ))
    }
}

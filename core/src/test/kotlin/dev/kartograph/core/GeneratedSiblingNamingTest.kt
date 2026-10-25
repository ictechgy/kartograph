package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedSiblingNamingTest {
    @Test
    fun `Android entry points identify their exact Hilt base without broadening nested application roots`() {
        assertEquals(setOf("dev/fixture/Hilt_Screen"), GeneratedSiblingNaming.candidatesFor(
            "dev/fixture/Screen", setOf("dagger/hilt/android/AndroidEntryPoint"),
        ))
        assertEquals(setOf("dev/fixture/Hilt_Outer_Screen"), GeneratedSiblingNaming.candidatesFor(
            "dev/fixture/Outer${'$'}Screen", setOf("dagger/hilt/android/AndroidEntryPoint"),
        ))
        assertEquals(emptySet(), GeneratedSiblingNaming.candidatesFor(
            "dev/fixture/Outer${'$'}App", setOf("dagger/hilt/android/HiltAndroidApp"),
        ))
    }

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

package dev.kartograph.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** 원시 예외·경로를 저장하지 않고 자동 compiler 관측을 거부한 사유만 전달한다. */
internal enum class WitnessRejection(val description: String) {
    UNSUPPORTED_JAVAC("unsupported javac option for compiler witness"),
    UNSUPPORTED_KOTLIN("Kotlin compiler witness requires declared toolchain and file input APIs"),
    UNSUPPORTED_LAUNCHER("compiler witness requires the declared Java toolchain without a custom launcher or JVM arguments"),
    FAIL_ON_ERROR_REQUIRED("compiler witness requires failOnError=true"),
    MISSING_INPUT("fingerprint input is missing"),
    CHANGED_INPUTS("compiler inputs changed during compilation; rebuild before capturing a snapshot"),
    UNAVAILABLE("compiler evidence could not be captured; inspect the selected compiler input configuration");

    companion object {
        const val FILE_NAME: String = "rejection.txt"

        fun from(error: Exception): WitnessRejection = generateSequence<Throwable>(error) { it.cause }
            .take(8).mapNotNull { cause -> entries.find { it != UNAVAILABLE && it.description == cause.message } }
            .firstOrNull() ?: UNAVAILABLE

        fun describe(files: List<File>): String {
            val file = files.singleOrNull()?.toPath() ?: return UNAVAILABLE.description
            return try {
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 128) UNAVAILABLE.description
                else entries.find { it.name == Files.readString(file) }?.description ?: UNAVAILABLE.description
            } catch (_: java.io.IOException) { UNAVAILABLE.description }
        }
    }
}

package dev.kartograph.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * 표준 출력·오류를 UTF-8로 고정한 뒤 명령을 실행한다.
 *
 * `System.out`은 플랫폼 로케일을 따르므로 `LC_ALL=C` 같은 환경에서는 비ASCII 식별자가 `?`로 뭉개진다.
 * 그러면 서로 다른 선언이 같은 `usr`로 붕괴해 교환 문서의 join key가 조용히 충돌하고, Gradle task가
 * 항상 UTF-8로 쓰는 문서와도 바이트가 달라진다. 출력 자체를 고정해 환경과 무관하게 결정적으로 만든다.
 */
fun main(arguments: Array<String>) {
    val output = utf8Stream(FileDescriptor.out)
    val error = utf8Stream(FileDescriptor.err)
    val status = KartographCli.run(arguments, output, error)
    output.flush()
    // 잘린 문서를 성공으로 보고하지 않도록, 쓰기 실패는 도구 실패로 올린다.
    if (output.checkError()) {
        error.println("error: unable to write the complete output; check the destination for available space")
        error.flush()
        exitProcess(ExitStatus.FAILURE.code)
    }
    error.flush()
    exitProcess(status)
}

private fun utf8Stream(descriptor: FileDescriptor): PrintStream =
    PrintStream(FileOutputStream(descriptor), false, StandardCharsets.UTF_8)

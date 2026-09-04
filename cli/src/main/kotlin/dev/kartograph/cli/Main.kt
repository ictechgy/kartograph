package dev.kartograph.cli

import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    exitProcess(KartographCli.run(arguments, System.out, System.err))
}

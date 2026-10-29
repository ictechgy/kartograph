package dev.kartograph.cli.fixture.defaultbridge

class BridgeFixture {
    fun label(value: Int = 7): Int = value
    fun caller(): Int = label()
}

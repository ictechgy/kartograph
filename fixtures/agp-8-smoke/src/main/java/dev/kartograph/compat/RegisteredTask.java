package dev.kartograph.compat;

public final class RegisteredTask implements Runnable {
    @Override public void run() { new ServiceDependency(); }
}

final class ServiceDependency {}

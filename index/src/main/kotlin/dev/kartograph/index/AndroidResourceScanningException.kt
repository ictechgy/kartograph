package dev.kartograph.index

/**
 * Android XML을 안전하고 완전하게 읽지 못해
 * 부분 보존 근거를 반환하지 않을 때 사용한다.
 */
public class AndroidResourceScanningException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

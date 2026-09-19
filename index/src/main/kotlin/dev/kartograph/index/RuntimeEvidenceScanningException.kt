package dev.kartograph.index

/** 사용자가 제공한 런타임 근거 입력을 완전하게 읽지 못했을 때 사용한다. */
public class RuntimeEvidenceScanningException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

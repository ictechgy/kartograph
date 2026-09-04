package dev.kartograph.index

/**
 * class root 또는 class bytes를 신뢰할 수 없어 부분 그래프 대신
 * 분석을 중단할 때 사용한다.
 */
public class ClassIndexingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

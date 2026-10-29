package dev.kartograph.core

/**
 * 지역·익명 class가 소스에서 어휘적으로 속한 선언이다.
 *
 * Kotlin 람다 class·익명 객체·suspend 람다·SAM 변환 class·callable reference class는 classfile의
 * `EnclosingMethod` 속성으로 자신을 감싼 메서드(또는 초기화 문맥이면 class)를 기록한다. 이 사실은
 * dispatch 모델의 `FunctionN.invoke` 후보 간선과 달리 컴파일러가 확정한 관계이므로, 순회는 람다 본문을
 * 감싼 선언의 일부로 다룰 수 있다. 기존 간선 집합과 `impact` 기본 출력을 바꾸지 않도록 간선이 아닌 별도
 * 사실로 보관한다.
 *
 * @property localClass `EnclosingMethod` 속성을 가진 지역·익명 class 정점이다
 * @property enclosing 감싼 메서드 정점이다. 초기화 문맥이거나 메서드 정점이 그래프에 없으면 소유 class 정점이다
 */
public data class LexicalEnclosure(val localClass: NodeId, val enclosing: NodeId) : Comparable<LexicalEnclosure> {
    override fun compareTo(other: LexicalEnclosure): Int =
        compareValuesBy(this, other, LexicalEnclosure::localClass, LexicalEnclosure::enclosing)
}

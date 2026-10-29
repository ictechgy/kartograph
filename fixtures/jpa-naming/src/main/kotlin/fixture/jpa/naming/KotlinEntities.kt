package fixture.jpa.naming

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Transient

/** Kotlin 생성자 프로퍼티와 use-site 타깃 어노테이션을 고정한다. */
@Entity
@Table(name = "kotlin_tasks")
class KotlinTask(
    @Id
    var id: Long = 0,
    @Column(name = "taskTitle")
    var title: String = "",
    @field:Column(name = "due_on")
    var dueDate: String? = null,
    var createdAt: Long = 0,
    @ManyToOne
    @JoinColumn(name = "boardRef")
    var board: KotlinBoard? = null,
) {
    var priorityLevel: Int = 0

    @Transient
    var scratch: String = ""

    /** backing field가 없는 계산 프로퍼티는 컬럼이 아니다. */
    val summary: String
        get() = "$title/$priorityLevel"
}

/** 몸체 프로퍼티만 가진 Kotlin 엔티티다. */
@Entity
class KotlinBoard {
    @Id
    var boardId: Long = 0
    var boardName: String = ""
    @ManyToOne
    var ownerTask: KotlinTask? = null
}

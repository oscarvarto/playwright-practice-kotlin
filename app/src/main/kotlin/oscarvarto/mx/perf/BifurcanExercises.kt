package oscarvarto.mx.perf

import arrow.core.Either
import io.lacuna.bifurcan.IntMap
import oscarvarto.mx.domain.Name
import oscarvarto.mx.domain.Person
import oscarvarto.mx.domain.ValidatedPerson
import kotlin.collections.sorted
import io.lacuna.bifurcan.List as PerfList

fun groupByAge(maybePeople: List<ValidatedPerson>): IntMap<PerfList<Name>> =
    IntMap.from(
        maybePeople
            .filterIsInstance<Either.Right<Person>>()
            .map { it.value }
            .groupBy { it.age.toLong() }
            .mapValues { (_, people) -> PerfList.from(people.map(Person::name).sorted()) },
    )

fun main() {
    val initialData =
        listOf(
            Person("Bety", 34),
            Person("Alex", 15),
            Person("Joshua", 11),
            Person("Oscar", 34),
        )

    groupByAge(initialData).forEach { println(it) }
}

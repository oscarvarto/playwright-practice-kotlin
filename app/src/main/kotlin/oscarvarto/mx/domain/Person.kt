package oscarvarto.mx.domain

import arrow.core.Either
import arrow.core.Nel
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.zipOrAccumulate

sealed interface PersonValidationError

data object BlankName : PersonValidationError

data object NegativeAge : PersonValidationError

data object MaxAge : PersonValidationError

typealias ValidatedPerson = Either<NonEmptyList<PersonValidationError>, Person>
typealias Name = String
typealias Age = Int

@ConsistentCopyVisibility
data class Person private constructor(
    val name: Name,
    val age: Age,
) {
    companion object {
        const val MAX_AGE = 130

        operator fun invoke(
            name: Name,
            age: Age,
        ): Either<NonEmptyList<PersonValidationError>, Person> =
            either {
                zipOrAccumulate(
                    { ensure(name.isNotBlank()) { BlankName } },
                    { ensure(age >= 0) { NegativeAge } },
                    { ensure(age <= MAX_AGE) { MaxAge } },
                ) { _, _, _ -> Person(name, age) }
            }
    }
}

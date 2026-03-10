package oscarvarto.mx.domain

import arrow.core.nonEmptyListOf
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec

class PersonTest :
    FunSpec({
        test("name cannot be blank") {
            Person("", 24).shouldBeLeft(nonEmptyListOf(BlankName))
        }

        test("age cannot be negative") {
            Person("Alice", -1).shouldBeLeft(nonEmptyListOf(NegativeAge))
        }

        test("age cannot exceed MAX_AGE") {
            Person("Alice", 131).shouldBeLeft(nonEmptyListOf(MaxAge))
        }

        test("accumulates multiple errors") {
            Person("", -1).shouldBeLeft(nonEmptyListOf(BlankName, NegativeAge))
        }

        test("valid person") {
            Person("Alice", 30).shouldBeRight()
        }
    })

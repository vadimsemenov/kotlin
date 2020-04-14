// ATTACH_LIBRARY: coroutines

package continuation

import forTests.builder

fun main() {
    val a = "a"
    fun fibonacci() = sequence {
        var terms = Pair(0, 1)

        while (true) {
            yield(terms.first)
            terms = Pair(terms.second, terms.first + terms.second)
            //Breakpoint!
        }
    }
    fibonacci().take(10)
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}
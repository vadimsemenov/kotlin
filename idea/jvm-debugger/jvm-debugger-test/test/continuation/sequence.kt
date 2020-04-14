// ATTACH_LIBRARY: coroutines

package continuation

fun main() {
    val a = "a"
    val result = fibonacci().take(10).toList()
}

fun fibonacci() = sequence {
    var terms = Pair(0, 1)

    while (true) {
        yield(terms.first)
        terms = pair(terms)
    }
}

fun pair(terms: Pair<Int, Int>): Pair<Int, Int> {
    var terms1 = terms
    terms1 = Pair(terms1.second, terms1.first + terms1.second)
    if (terms.first > 7)
        println("stop") //Breakpoint!
    return terms1
}


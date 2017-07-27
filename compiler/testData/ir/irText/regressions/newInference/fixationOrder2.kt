fun <X, Y, Z> foo(f: (Y) -> Z, g: (X) -> Y, x: X): Z = f(g(x))

fun test() = foo({ it: Int -> it + 1 }, { it.length }, "")
// "Create class 'XImpl'" "true"
interface X
interface A {
    fun f(): X
}

class B : A {
    override fun f() = <caret>XImpl()
}
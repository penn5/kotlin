/*
 * UNEXPECTED BEHAVIOUR
 * ISSUES: KT-37488
 */

fun Case1(arg: SealedClass?) {
    val case1_1 = when (arg) {
        null, SealedClass.Object2 -> null

        SealedClass.Object1 -> {
            xNull
        }
    }?.let {
        <!INAPPLICABLE_CANDIDATE!>markPsi<!>(it)   //it is Unit instead of CustomClass
    }

    val case1_2 = when (arg) {
        null, SealedClass.Object2 -> null

        SealedClass.Object1 -> {
            ""
        }
    }?.let {
        <!INAPPLICABLE_CANDIDATE!>markString<!>(it)  //it is Unit instead of String
    }

}

fun Case2(arg: SealedClass) {
    val case2_1 = when (arg) {
        SealedClass.Object2 -> xNotNull

        SealedClass.Object1 -> {
            xNotNull
        }
    }.let {
        <!INAPPLICABLE_CANDIDATE!>markPsi<!>(it)   //it is Unit instead of CustomClass
    }

    val case2_2 = when (arg) {
        SealedClass.Object2 -> "wef"

        SealedClass.Object1 -> {
            ""
        }
    }.let {
        <!INAPPLICABLE_CANDIDATE!>markString<!>(it)  //it is Unit instead of String
    }

}

fun Case4(arg: SealedClass?) {
    val case1_1 = when (arg) {
        null, SealedClass.Object2 -> null

        SealedClass.Object1 -> {
            xNull
        }
    }?.also {
        <!INAPPLICABLE_CANDIDATE!>markPsi<!>(it)  //it is Unit instead of CustomClass
    }

    val case1_2 = when (arg) {
        null, SealedClass.Object2 -> null

        SealedClass.Object1 -> {
            ""
        }
    }?.also {
        <!INAPPLICABLE_CANDIDATE!>markString<!>(it) //it is Unit instead of String
    }

}

fun Case3(arg: SealedClass) {
    val case2_1 = when (arg) {
        SealedClass.Object2 -> xNotNull

        SealedClass.Object1 -> {
            xNotNull
        }
    }.also {
        <!INAPPLICABLE_CANDIDATE!>markPsi<!>(it)  //it is Unit instead of CustomClass
    }

    val case2_2 = when (arg) {
        SealedClass.Object2 -> "wef"

        SealedClass.Object1 -> {
            ""
        }
    }.also {
        <!INAPPLICABLE_CANDIDATE!>markString<!>(it) //it is Unit instead of String
    }

}


fun markPsi(element: CustomClass) {}
fun markString(element: String) {}

sealed class SealedClass {
    object Object1 : SealedClass()

    object Object2 : SealedClass()
}

interface CustomClass

val xNull: CustomClass? = TODO()
val xNotNull: CustomClass = TODO()

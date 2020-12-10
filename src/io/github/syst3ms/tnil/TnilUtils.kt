package io.github.syst3ms.tnil

var affixData: List<AffixData> = emptyList()
var rootData: List<RootData> = emptyList()

fun error(s: String) = "\u0000" + s

fun errorList(s: String) = listOf("\u0000", s)

val VOWELS = setOf("a", "ä", "e", "ë", "i", "ö", "o", "ü", "u")

fun String.stripPunctuation(): String = this.replace("[.,?!:;]+$".toRegex(), "")

fun String.isVowel() = with(substituteAll(UNSTRESSED_FORMS)) {
    when (length) {
        1, 2 -> all { it.toString() in VOWELS }
        3 -> this[1] == '\'' && this[0].toString() in VOWELS && this[2].toString() in VOWELS
        else -> false
    }
}

fun String.isConsonant() = this.all { it.toString().defaultForm() in CONSONANTS }

val STRESSED_VOWELS = setOf('á','â','é', 'ê', 'í', 'ô', 'ó', 'û', 'ú')

fun String.hasStress() = this[0] in STRESSED_VOWELS

fun String.withZeroWidthSpaces() = this.replace("([/—-])".toRegex(), "\u200b$1")


//Deals with series three vowels and non-default consonant forms
infix fun String.eq(s: String): Boolean = if ("/" in this) {
    this.split("/").any { it eq s }
} else {
    this.defaultForm() == s.defaultForm()
}

fun String.substituteAll(substitutions : List<Pair<String, String>>) = substitutions.fold(this) {
        current, (allo, sub) -> current.replace(allo.toRegex(), sub)
}

fun String.defaultForm() = toLowerCase().substituteAll(ALLOGRAPHS).substituteAll(UNSTRESSED_FORMS)


fun Array<String>.findStress(): Int {
    val vowels = this.filter(String::isVowel)
        .flatMap {
            val (series, form) = seriesAndForm(it.defaultForm())
            if (series == 1 || series == 2 || (series == 3 && form == 5)) {
                listOf(it)
            } else {
                it.toCharArray().map(Char::toString).filter { ch -> ch != "'" }
            }
        }
    val stressIndex = vowels
            .reversed()
            .indexOfFirst { it.hasStress() }
    return when {
        vowels.size == 1 -> -1
        stressIndex == -1 -> 1
        else -> stressIndex
    }
}

fun String.splitGroups(): Array<String> {
    val groups = arrayListOf<String>()
    var chars = toCharArray()
            .map(Char::toString)
            .toList()
    while (chars.isNotEmpty()) {
        val group = when {
            chars[0].defaultForm().isVowel() -> {
                if (chars.size >= 3 && (chars[0] + chars[1] + chars[2]).isVowel()) {
                    chars[0] + chars[1] + chars[2]
                } else if (chars.size >= 2 && (chars[0] + chars[1]).isVowel()) {
                    chars[0] + chars[1]
                } else {
                    chars[0]
                }
            }
            chars[0].isConsonant() -> {
                chars.takeWhile(String::isConsonant).joinToString("")
            }

            chars[0] == "-" -> chars[0]

            else -> {
                throw IllegalArgumentException("Non-Ithkuil character: ${chars[0]}")
            }
        }
        chars = chars.subList(group.length, chars.size)
        groups += group
    }
    return groups.toTypedArray()
}

val BICONSONANTAL_PRS = setOf("th", "ph", "kh", "ll", "rr", "řř")

fun parseFullReferent(s: String, precision: Int, ignoreDefault: Boolean): String? {
    val refList = mutableListOf<Slot>()
    var index = 0

    while (index < s.length) {
        refList.add(
                if (index + 1 < s.length && s.substring(index, index + 2) in BICONSONANTAL_PRS) {
                    parsePersonalReference(s.substring(index, index + 2)).also { index += 2 }
                            ?: return null
                } else parsePersonalReference(s.substring(index, index + 1)).also { index++ }
                        ?: return null
        )
    }
    return when (refList.size) {
        0 -> null
        1 -> refList[0].toString(precision, ignoreDefault)
        else -> refList
                .joinToString(REFERENT_SEPARATOR, REFERENT_START, REFERENT_END)
                { it.toString(precision, ignoreDefault) }
    }
}


fun parseAffixes(data: String): List<AffixData> = data
        .lines()
        .asSequence()
        .drop(1)
        .map    { it.split("\t") }
        .filter { it.size >= 11 }
        .map    { AffixData(it[0], it[1], it.subList(2, 11).toTypedArray()) }
        .toList()

fun parseRoots(data: String): List<RootData> = data
        .lines()
        .asSequence()
        .drop(1)
        .map    { it.split("\t") }
        .filter { it.size >= 5 }
        .map    { RootData(it[0], it.subList(1, 5)) }
        .toList()

data class AffixData(val cs: String, val abbr: String, val desc: Array<String>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AffixData
        if (cs != other.cs) return false
        if (abbr != other.abbr) return false
        if (!desc.contentEquals(other.desc)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = cs.hashCode()
        result = 31 * result + abbr.hashCode()
        result = 31 * result + desc.contentHashCode()
        return result
    }
}

data class RootData(val cr: String, val dsc: List<String>)

class SentenceParsingState(var carrier: Boolean = false,
                           var register: MutableList<Register> = mutableListOf(),
                           var quotativeAdjunct: Boolean = false,
                           var concatenative: Boolean = false,
                           stress: Int? = null,
                           var isLastFormativeVerbal : Boolean? = null,
                           var rtiAffixScope: String? = null) {
    var forcedStress : Int? = stress
        get() {
            val old = field
            forcedStress = null
            return old
        }
}

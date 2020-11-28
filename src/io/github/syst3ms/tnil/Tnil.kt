@file:Suppress("IMPLICIT_CAST_TO_ANY")

package io.github.syst3ms.tnil

import java.io.PrintWriter
import java.io.StringWriter

fun main() {
    println(parseWord("lada'lad", 1, true))
}

fun parseSentence(s: String, precision: Int, ignoreDefault: Boolean): List<String> {
    if (s.isBlank()) {
        return errorList("Nothing to parse.")
    }
    val words = s.toLowerCase().split("\\s+".toRegex())
    val state = SentenceParsingState()
    var currentlyCarrier = false
    var modularIndex : Int? = null
    var modularForcedStress : Int? = null
    val result = arrayListOf<String>()
    for ((i, word) in words.withIndex()) {
        var toParse = word
        if (!currentlyCarrier || !state.carrier) {
            if (toParse.startsWith(LOW_TONE_MARKER)) { // Register end
                toParse = toParse.drop(1)
            } else if (toParse matches "'[aeoui]'".toRegex()) {
                state.forcedStress = when (toParse[1]) {
                    'a' -> -1
                    'e' -> 0
                    'o' -> 1
                    'u' -> 2
                    'i' -> 3
                    else -> throw IllegalStateException()
                }
                continue
            }
        }
        if (toParse.any { !it.toString().isConsonant() && !it.toString().isVowel() }) {
            return errorList("**Parsing error**: '$word' contains non-Ithkuil characters")
        }
        try {
            val res = parseWord(toParse, precision, ignoreDefault)
            if (currentlyCarrier && state.carrier) {
                result += if (word.startsWith(LOW_TONE_MARKER)) {
                    currentlyCarrier = false
                    state.carrier = false
                    "${word.drop(1)} $CARRIER_END "
                } else {
                    "$word "
                }
                continue
            } else if (res == MODULAR_PLACEHOLDER) { // Modular adjunct
                modularIndex = i
                modularForcedStress = state.forcedStress
                continue
            } else if (res.startsWith("\u0000")) {
                return errorList("**Parsing error**: ${res.drop(1)}")
            } else if (word.startsWith(LOW_TONE_MARKER)) {
                if (state.register.isEmpty())
                    return errorList("*Syntax error*: low tone can't mark the end of non-default register, since no such register is active.")
                val reg = state.register.removeAt(state.register.lastIndex)
                result += if (reg == Register.DISCURSIVE) {
                    "$res $DISCURSIVE_END "
                } else {
                    "$res $REGISTER_END "
                }
                continue
            } else if ((state.isLastFormativeVerbal != null || state.quotativeAdjunct) && modularIndex != null) {
                // Now we can know the stress of the formative and finally parse the adjunct properly
                val mod = parseModular(
                        words[modularIndex].splitGroups(),
                        precision,
                        // If quotativeAdjunct is true, case-scope needs default values like CTX or MNO to be shown, and we want to ignore them
                        state.quotativeAdjunct || ignoreDefault,
                        // This is fine because if quotativeAdjunct is false that means isLastFormativeVerbal is non-null
                        /* !state.quotativeAdjunct && state.isLastFormativeVerbal!!,
                        modularForcedStress,
                        sentenceParsingState = state */
                )
                if (mod.startsWith("\u0000")) {
                    return errorList("**Parsing error**: ${mod.drop(1)}")
                }
                result.add(modularIndex, "$mod ")
                state.quotativeAdjunct = false
                modularIndex = null
                modularForcedStress = null
            }
            currentlyCarrier = state.carrier
            result += res + if (state.carrier && !res.endsWith(CARRIER_START)) {
                " $CARRIER_START"
            } else {
                " "
            }
        } catch (e: Exception) {
            logger.error("{}", e)
            return if (precision < 3) {
                errorList("A severe exception occurred during sentence parsing. We are unable to give more information. " +
                        "For a more thorough (but technical) description of the error, please use debug mode.")
            } else {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stacktrace = sw.toString()
                        .split("\n")
                        .take(10)
                        .joinToString("\n")
                errorList(stacktrace)
            }
        }
    }
    if (modularIndex != null && modularForcedStress != null) {
        return errorList("A modular adjunct needs an adjacent formative. If you want to parse the adjunct by itself, use ??gloss, ??short or ??full.")
    }
    if (currentlyCarrier && state.carrier) {
        result += "$CARRIER_END "
    }
    for (reg in state.register.asReversed()) {
        result += if (reg == Register.DISCURSIVE) {
            "$DISCURSIVE_END "
        } else {
            "$REGISTER_END "
        }
    }
    if (state.concatenative) {
        result += "$CONCATENATIVE_END "
    }
    return result
}

fun parseWord(s: String, precision: Int, ignoreDefault: Boolean) : String {

    val initialGroups = s.splitGroups().toList()
    if (initialGroups.isEmpty()) {
        return error("Empty word")
    }

    var sentencePrefix = true

    val groups = when {
        initialGroups.size in 5..6 && initialGroups.take(4) == listOf("ç", "ë", "h","ë") -> initialGroups.drop(3) // Single-affix adjunct, degree 4
        initialGroups.size >= 4 && initialGroups[0] == "ç" && initialGroups[1] == "ë" -> initialGroups.drop(2)
        initialGroups[0] == "ç" && initialGroups[1].isVowel() -> initialGroups.drop(1)
        initialGroups[0] == "çw" -> listOf("w") + initialGroups.drop(1)
        initialGroups[0] == "çç" -> listOf("y") + initialGroups.drop(1)
        else -> initialGroups.also { sentencePrefix = false }
    }.toTypedArray()

    val ssgloss = when (precision) {
        0 -> "[.]-"
        1 -> "[sentence:]-"
        2, 3, 4 -> "[sentence start]-"
        else -> ""
    }

    return (if (sentencePrefix) ssgloss else "") +  when {
        groups.size == 1 && groups[0].isConsonant() ->  {
            Bias.byGroup(groups[0])?.toString(precision) ?: error("Unknown bias: ${groups[0]}")
        }
        groups[0] in setOf("hl", "hm", "hn", "hr") && (groups.size == 2) -> {
            val v = groups[1]
            parseSuppletiveAdjuncts(groups[0], v, precision, ignoreDefault) ?: error("Unknown carrier adjunct: $s")
        }
        groups[0] == "h" && groups.size == 2 -> {
            val (register, initial) = Register.byVowel(groups.last()) ?: return error("Unknown register adjunct: $s")
            return "<" + (if (initial) "" else "/") + register.toString(precision, ignoreDefault) + ">"
        }
        groups.size == 2 && groups[0].isConsonant() && !groups[0].isModular()
                || groups.size >= 4 && !groups[0].isModular() && (groups[1] == "ë" || groups[2] matches "[wy]".toRegex()) -> {
            parsePRA(groups, precision, ignoreDefault)
        }
        groups.size >= 4 && groups[0].isVowel() && groups[3] in COMBINATION_PRA_SPECIFICATION
                || groups.size >= 3 && groups[0] !in CC_CONSONANTS && groups[2] in COMBINATION_PRA_SPECIFICATION -> {
            parseCombinationPRA(groups, precision, ignoreDefault)
        }
        groups.size in 2..3 && groups[1].isConsonant() && !groups[1].isModular()
                || groups.size in 4..5 && groups[1] == "y" && !groups[3].isModular() -> {
            parseAffixual(groups, precision, ignoreDefault)
        }
        groups.size >= 5 && groups[0].isConsonant() && groups[2].removePrefix("'") in CC_CONSONANTS
                || groups.size >= 6 && (groups[0] == "ë") && (groups[3].removePrefix("'") in CC_CONSONANTS) -> {
            parseAffixualScoping(groups, precision, ignoreDefault)
        }

        groups.all { it.isVowel() || it in CN_CONSONANTS } -> {
            parseModular(groups, precision, ignoreDefault)
        }
        else -> parseFormative(groups, precision, ignoreDefault)
    }
}

@Suppress("UNCHECKED_CAST")
fun parseFormative(groups: Array<String>, precision: Int, ignoreDefault: Boolean) : String {

    val stress = groups.takeWhile { it != "-" }.toTypedArray().findStress().coerceAtLeast(0)
    var index = 0

    val (concatenation, shortcut) = if (groups[0] in CC_CONSONANTS) {
        index++
        parseCc(groups[0])
    } else Pair(null, null)

    val relation = if (concatenation == null) {
        when (stress){
            2 -> Relation.FRAMED
            else -> Relation.UNFRAMED
        }
    } else null

    val vv = if (index == 0 && groups[0].isConsonant()) "a" else {
        groups[index].also { index++ }
    }

    val slotII = parseVv(vv, shortcut) ?: return error("Unknown Vv value: $vv")

    val stem = (slotII[0] as Stem).ordinal
    val (root, stemUsed) = parseRoot(groups[index], precision, stem)
    index++

    val vr = if (shortcut != null) "a" else {
        groups.getOrNull(index).also { index++ } ?: return error("Formative ended unexpectedly: ${groups.joinToString("")}")
    }

    val slotIV = parseVr(vr) ?: return error("Unknown Vr value: $vr")

    val csVxAffixes : MutableList<Affix> = mutableListOf()

    if (shortcut == null) {
        var indexV = index
        while (true) {
            if (indexV+1 >= groups.size || groups[indexV] in CN_CONSONANTS || groups[indexV] == "-") {
                csVxAffixes.clear()
                indexV = index
                break
            } else if (groups[indexV].isGlottalCa()) {
                break
            }

            val (vx, glottal) = unGlottalVowel(groups[indexV+1]) ?: return error("Unknown vowelform: ${groups[indexV+1]} (slot V)")

            csVxAffixes.add(Affix(vx, groups[indexV]))
            indexV += 2

            if (glottal) break
        }
        index = indexV

    }

    if (csVxAffixes.size == 1) csVxAffixes[0].canBePraShortcut = true


    var cnInVI = false

    val slotVI = if (shortcut == null) {
        val ca = if (groups.getOrNull(index)?.isGlottalCa()
                        ?: return error("Formative ended unexpectedly")) {
            if (csVxAffixes.isNotEmpty()) {
                groups[index].unGlottalCa()
            } else return error("Unexpected glottal Ca: ${groups[index]}")
        } else groups[index]

        if (ca !in setOf("hl", "hr", "hm", "hn", "hň")) {
            parseCa(ca).also { index++ } ?: return error("Unknown Ca value: $ca")
        } else {
            parseCa("l")!!.also{ cnInVI = true }
        }
    } else null

    val vxCsAffixes : MutableList<Precision> = mutableListOf()

    if (!cnInVI) {
        while (true) {
            if (index+1 >= groups.size || groups[index+1] in CN_CONSONANTS || groups[index+1] == "-") {
                break
            }

            val (vx, glottal) = unGlottalVowel(groups[index]) ?: return error("Unknown vowelform: ${groups[index]} (slot VII)")

            vxCsAffixes.add(Affix(vx, groups[index+1]))
            index += 2

            if (glottal) {
                vxCsAffixes.add(PrecisionString("{end of slot V}", "{Ca}"))
            }
        }
    }

    if (vxCsAffixes.size == 1) (vxCsAffixes[0] as? Affix)?.canBePraShortcut = true

    val marksMood = (stress == 0)

    val slotVIII: List<Precision>? = when {
        cnInVI -> {
            parseVnCn("a", groups[index], marksMood).also { index++ } ?: return error("Unknown Cn value in Ca: ${groups[index]}")
        }
        groups.getOrNull(index+1) in CN_CONSONANTS -> {
            parseVnCn(groups[index], groups[index+1], marksMood).also { index += 2 } ?: return error("Unknown VnCn value: ${groups[index] + groups[index+1]}")
        }
        else -> null
    }


    val vcVk = groups.getOrNull(index) ?: "a"

    val slotIX = if (concatenation == null) {
        when (stress) {
            0 -> parseVk(vcVk) ?: return error("Unknown Vk form $vcVk")
            1, 2 -> listOf(Case.byVowel(vcVk) ?: return error("Unknown Vc form $vcVk"))
            else -> return error("Unknown stress: $stress from ultimate")
        }
    } else {
        when (stress) {
            1 -> listOf(Case.byVowel(vcVk) ?: return error("Unknown Vf form $vcVk (penultimate stress)"))
            0 -> {
                val glottalified = when (vcVk.length) {
                    1 -> "$vcVk'$vcVk"
                    2 -> "${vcVk[0]}'${vcVk[1]}"
                    else -> return error("Vf form is too long: $vcVk")
                }
                listOf(Case.byVowel(glottalified) ?: return error("Unknown Vf form $vcVk (ultimate stress)"))
            }
            else -> return error("Unknown stress for concatenated formative: $stress from ultimate")
        }
    }
    index++

    val cyMarksMood = (stress == 0) || (stress == 2 && slotVIII != null)

    val slotX = if (concatenation == null && index < groups.size) {
        parseCbCy(groups[index], cyMarksMood)
    } else null

    val parentFormative = if (groups.getOrNull(index) == "-") {
        if (concatenation != null) {
            parseFormative(groups.drop(index+1).toTypedArray(), precision, ignoreDefault)
        } else return error("Non-concatenated formative hyphenated")

    } else null

    val slotList: List<Any> = listOfNotNull(relation, concatenation, slotII, PrecisionString(root), slotIV) +
            csVxAffixes + listOfNotNull(slotVI) + vxCsAffixes + listOfNotNull(slotVIII, slotIX, slotX)

    val parsedFormative : String = slotList.map {
        if (it is List<*>) {
            (it as List<Precision>).toString(precision, ignoreDefault, stemUsed = stemUsed) // Wacky casting, beware.
        } else (it as Precision).toString(precision, ignoreDefault)
    }.filter { it.isNotEmpty() }.joinToString(SLOT_SEPARATOR)

    return if (parentFormative != null) {
        "$parsedFormative $parentFormative"
    } else parsedFormative

}

fun parseVh(vh: String) : PrecisionString? = when (vh.defaultForm()) {
    "a" -> PrecisionString("{concatenated formative only}", "{concat.}")
    "e" -> PrecisionString("{scope over formative}", "{formative}")
    "i", "u" -> PrecisionString("{scope over concatenated formative only}", "{concat. formative}")
    "o" -> PrecisionString("{scope over adjacent adjuncts}", "{adjacent}")
    "ö" -> PrecisionString("{scope over adjacent adjuncts of the concatenated formative}", "{concat. adjacent}")
    else -> null
}

@Suppress("UNCHECKED_CAST")
fun parseModular(groups: Array<String>, precision: Int, ignoreDefault: Boolean) : String {
    val stress =  groups.findStress().let { if (it != -1) it else 1 }
    var index = 0

    val slot1 = if (groups[0] == "w") {
        PrecisionString("{parent formative only}", "{parent}")
                .also { index++ }
    } else null

    val midSlotList : MutableList<List<Precision>> = mutableListOf()

    while (groups.size > index + 2) {
        midSlotList.add(parseVnCn(groups[index], groups[index+1], false) ?: return error("Unknown VnCn: ${groups[index]}${groups[index+1]}"))
        index += 2
    }

    if (midSlotList.size > 3) return error("Too many (>3) middle slots in modular adjunct: ${midSlotList.size}")

    val slot5 = when {
        midSlotList.isEmpty() -> Aspect.byVowel(groups[index]) ?: return error("Unknown aspect: ${groups[index]}")
        stress == 1 -> parseVnCn(groups[index], "h", marksMood = false) ?: return error("Unknown non-aspect Vn: ${groups[index]}")
        stress == 0 -> parseVh(groups[index]) ?: return error("Unknown Vh: ${groups[index]}")
        else -> return error("Unknown stress on modular adjunct: $stress from ultimate")
    }

    return listOfNotNull(slot1, *midSlotList.toTypedArray(), slot5).map {
        if (it is List<*>) {
            (it as List<Precision>).toString(precision, ignoreDefault) // More wacky casting, beware.
        } else (it as Precision).toString(precision, ignoreDefault)
    }.filter { it.isNotEmpty() }.joinToString(SLOT_SEPARATOR)

}

fun parsePRA(groups: Array<String>, precision: Int, ignoreDefault: Boolean, sentenceParsingState: SentenceParsingState? = null) : String {
    val stress =  groups.findStress().let { if (it != -1) it else 1 }
    val essence = (if (stress == 0) Essence.REPRESENTATIVE else Essence.NORMAL).toString(precision, ignoreDefault)
    var index = 0
    val c1 = groups[0] + if (groups[1] == "ë") groups[2] else ""
    val refA = parseFullReferent(c1, precision, ignoreDefault) ?: return error("Unknown personal reference cluster: $c1")
    if (groups[1] == "ë") index += 3 else index++

    val caseA = Case.byVowel(groups[index])?.toString(precision, ignoreDefault) ?: return error("Unknown case: ${groups[index]}")
    index++

    when {
        groups.getOrNull(index) in setOf("w", "y") -> {
            index++
            val vc2 = groups.getOrNull(index) ?: return "PRA ended unexpectedly"
            val caseB = Case.byVowel(vc2)?.toString(precision, ignoreDefault) ?: return error("Unknown case: ${groups[index]}")
            index++

            val c2 = groups.getOrNull(index)
            val refB = if (c2 != null) {
                parseFullReferent(c2, precision, ignoreDefault) ?: return error("Unknown personal reference cluster: $c2")
            } else null

            index++
            if (groups.getOrNull(index) == "ë") index++

            if (groups.size > index) return error("PRA is too long")

            return listOfNotNull(refA, caseA, caseB, refB, essence).filter { it.isNotEmpty() }.joinToString(SLOT_SEPARATOR)

        }
        groups.size > index+1 -> return error("PRA is too long")
        
        else -> return listOfNotNull(refA, caseA, essence).filter { it.isNotEmpty() }.joinToString(SLOT_SEPARATOR)
    }
}

@Suppress("UNCHECKED_CAST")
fun parseCombinationPRA(groups: Array<String>,
                        precision: Int,
                        ignoreDefault: Boolean,
                        sentenceParsingState: SentenceParsingState? = null): String {
    val stress =  groups.findStress().let { if (it != -1) it else 1 }
    val essence = if (stress == 0) Essence.REPRESENTATIVE else Essence.NORMAL
    var index = 0

    val shortcut = when(groups[0]) {
        "w" -> Shortcut.W_SHORTCUT
        "y" -> Shortcut.Y_SHORTCUT
        else -> null
    }
    if (shortcut != null) index++

    val slot1 = when {
        !groups[index].isVowel() -> null
        groups[index] == "ë" -> null.also { index++ }
        else -> (parseVv(groups[index], shortcut) ?: return error("Unknown Vv: ${groups[index]}"))
            .filter { it !is Stem }.also { index++ }
    }

    val ref = PrecisionString(parseFullReferent(groups[index], precision, ignoreDefault) ?: return error("Unknown referent: ${groups[index]}"))
    index++

    val caseA = Case.byVowel(groups[index]) ?: "Unknown case: ${groups[index]}"
    index++

    val specification = when(groups[index]) {
        "x" -> Specification.BASIC
        "xx" -> Specification.CONTENTIAL
        "lx" -> Specification.CONSTITUTIVE
        "rx" -> Specification.OBJECTIVE
        else -> return error("Unknown combination PRA specification: ${groups[index]}")
    }
    index++

    val vxCsAffixes : MutableList<Precision> = mutableListOf()
    while (true) {
        if (index+1 >= groups.size || groups[index+1] in CN_CONSONANTS || groups[index+1] == "-") {
            break
        }

        val (vx, glottal) = unGlottalVowel(groups[index]) ?: return error("Unknown vowelform: ${groups[index]} (slot VII)")

        if (glottal) return "Unexpected glottal stop"

        vxCsAffixes.add(Affix(vx, groups[index+1]))
        index += 2

    }

    val caseB = when (groups.getOrNull(index)?.defaultForm()) {
        "a", null -> null
        "üa" -> Case.THEMATIC
        else -> Case.byVowel(groups[index]) ?: return error("Unknown case: ${groups[index]}")
    }

    val slotList = listOfNotNull(slot1, ref, caseA, specification, *vxCsAffixes.toTypedArray(), caseB, essence)

    return slotList.map {
        if (it is List<*>) {
            (it as List<Precision>).toString(precision, ignoreDefault) // Wacky casting, beware.
        } else (it as Precision).toString(precision, ignoreDefault) }
            .filter { it.isNotEmpty() }
            .joinToString(SLOT_SEPARATOR)

}

fun parseAffixualScoping(groups: Array<String>,
                         precision: Int,
                         ignoreDefault: Boolean,
                         sentenceParsingState: SentenceParsingState? = null): String {
    var rtiScope: String? = sentenceParsingState?.rtiAffixScope
    var result = ""
    var i = 0
    if (groups[0] == "ë")
        i++
    var c = groups[i]
    var v: String
    if (groups[i+2] == "y") {
        v = groups[i+1] + "y" + groups[i+3]
        i += 4
    } else {
        v = groups[i+1]
        i += 2
    }
    if (c.isInvalidLexical() && v != CA_STACKING_VOWEL)
        return error("'$c' can't be a valid affix consonant")
    var aff = parseAffix(c, v, precision, ignoreDefault)
    when {
        aff.startsWith(AFFIX_UNKNOWN_VOWEL_MARKER) -> return error("Unknown affix vowel: ${aff.drop(AFFIX_UNKNOWN_VOWEL_MARKER.length)}")
        aff.startsWith(AFFIX_UNKNOWN_CASE_MARKER) -> return error("Unknown case vowel: ${aff.drop(AFFIX_UNKNOWN_CASE_MARKER.length)}")
        aff.startsWith(AFFIX_UNKNOWN_CA_MARKER) -> return error("Unknown Ca cluster: ${aff.drop(AFFIX_UNKNOWN_CA_MARKER.length)}")
    }
    result += aff.plusSeparator()
    val scope = affixAdjunctScope(groups[i], ignoreDefault) ?: return error("Invalid scope: ${groups[i]}")
    if (c == RTI_AFFIX_CONSONANT)
        rtiScope = rtiScope ?: scope
    result += scope.plusSeparator()
    i++
    while (i + 2 <= groups.size) {
        if (groups[i+1] == "y") {
            if (i + 3 >= groups.size) {
                return error("Second affix group ended unexpectedly")
            }
            v = groups[i] + "y" + groups[i+2]
            c = groups[i+3]
            i += 4
        } else {
            v = groups[i]
            c = groups[i+1]
            i += 2
        }
        if (c.isInvalidLexical() && v != CA_STACKING_VOWEL)
            return error("'$c' can't be a valid affix consonant")
        aff = parseAffix(c, v, precision, ignoreDefault)
        when {
            aff.startsWith(AFFIX_UNKNOWN_VOWEL_MARKER) -> return error("Unknown affix vowel: ${aff.drop(AFFIX_UNKNOWN_VOWEL_MARKER.length)}")
            aff.startsWith(AFFIX_UNKNOWN_CASE_MARKER) -> return error("Unknown case vowel: ${aff.drop(AFFIX_UNKNOWN_CASE_MARKER.length)}")
            aff.startsWith(AFFIX_UNKNOWN_CA_MARKER) -> return error("Unknown Ca cluster: ${aff.drop(AFFIX_UNKNOWN_CA_MARKER.length)}")
        }
        if (c == RTI_AFFIX_CONSONANT)
            rtiScope = rtiScope ?: ""
        result += aff
        i += 2
    }
    val sc = affixAdjunctScope(groups.getOrNull(i), ignoreDefault, scopingAdjunctVowel = true)
    if (sc != "" && rtiScope == "")
        rtiScope = sc
    result += (sc ?: return error("Invalid scope: ${groups[i]}")).plusSeparator(start = true)
    val stress = sentenceParsingState?.forcedStress ?: groups.findStress()
    result += when (stress) {
        0 -> "{Incp}".plusSeparator(start = true)
        1 -> ""
        else -> return error("Couldn't parse stress: stress was on syllable $stress from the end")
    }
    if (rtiScope != null)
        sentenceParsingState?.rtiAffixScope = rtiScope
    return result
}

fun parseAffixual(groups: Array<String>,
                  precision: Int,
                  ignoreDefault: Boolean,
                  sentenceParsingState: SentenceParsingState? = null): String {
    var rtiScope = sentenceParsingState?.rtiAffixScope
    var stress = sentenceParsingState?.forcedStress ?: groups.findStress()
    if (stress == -1) // Monosyllabic
        stress = 1 // I'll be consistent with 2011 Ithkuil, this precise behaviour is actually not documented
    var i = 0
    val v = if (groups[1] == "y") {
        i += 2
        groups[0] + "y" + groups[2]
    } else {
        groups[0]
    }
    val c = groups[i+1]
    if (c.isInvalidLexical() && v != CA_STACKING_VOWEL)
        return error("'$c' can't be a valid affix consonant")
    val aff = parseAffix(c, v, precision, ignoreDefault)
    val scope = affixAdjunctScope((groups.getOrNull(i+2)?.defaultForm()), ignoreDefault)
    return when {
        aff.startsWith(AFFIX_UNKNOWN_VOWEL_MARKER) -> error("Unknown affix vowel: ${aff.drop(AFFIX_UNKNOWN_VOWEL_MARKER.length)}")
        aff.startsWith(AFFIX_UNKNOWN_CASE_MARKER) -> error("Unknown case vowel: ${aff.drop(AFFIX_UNKNOWN_CASE_MARKER.length)}")
        aff.startsWith(AFFIX_UNKNOWN_CA_MARKER) -> error("Unknown Ca cluster: ${aff.drop(AFFIX_UNKNOWN_CA_MARKER.length)}")
        scope == null -> error("Invalid scope: ${groups[i+2]}")
        else -> {
            if (c == RTI_AFFIX_CONSONANT)
                rtiScope = rtiScope ?: scope
            if (rtiScope != null)
                sentenceParsingState?.rtiAffixScope = rtiScope
            aff + scope.plusSeparator(start = true) + if (stress != 1) {
                "{Incp}".plusSeparator(start = true)
            } else {
                ""
            }
        }
    }
}

fun error(s: String) = "\u0000" + s

fun errorList(s: String) = listOf("\u0000", s)

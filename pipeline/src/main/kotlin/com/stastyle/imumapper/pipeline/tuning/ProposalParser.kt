package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One field a proposal changes, with the reason the model gave when it gave one. */
class ConfigChange(val key: String, val from: String, val to: String, val reason: String?)

/** A model's answer turned into a config: the base config with the accepted keys applied. */
class Proposal(
    val config: PipelineConfig,
    val changes: List<ConfigChange>,
    val reasoning: String?,
    /** What the model expects to improve, in its own words. */
    val expected: String?,
    /** Keys that were ignored (unknown) and other things worth showing but not refusing. */
    val warnings: List<String>,
)

sealed interface ProposalOutcome {
    class Accepted(val proposal: Proposal) : ProposalOutcome

    /** Nothing was applied: every problem is listed so the user can fix the text or ask again. */
    class Rejected(val errors: List<String>) : ProposalOutcome
}

/**
 * Reads the JSON a model pastes back and applies it on top of the current config. Accepts the
 * answer format the prompt asks for (`{"config": {...}, "changes": [...], "reasoning": ...}`), a
 * bare config object, and either of those inside a fenced code block or surrounded by prose.
 * Every value is range-checked against [ConfigSchema]; one bad value rejects the whole answer,
 * because a half-applied proposal is not what the model reasoned about.
 */
object ProposalParser {

    fun parse(text: String, base: PipelineConfig): ProposalOutcome {
        val root = extractObject(text) ?: return ProposalOutcome.Rejected(listOf("No JSON object found in the pasted text"))
        val configObject = root["config"] as? JsonObject ?: root
        val reasons = changeReasons(root["changes"])
        val reasoning = root["reasoning"].asText()
        val expected = root["expected"].asText()

        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var c = base
        for ((key, value) in configObject) {
            if (value is JsonNull) continue
            when (key) {
                "strideLengthM" -> number(key, value, errors)?.let { c = c.copy(strideLengthM = it) }
                "weinbergK" -> number(key, value, errors)?.let { c = c.copy(weinbergK = it) }
                "headingOffsetRad" -> number(key, value, errors)?.let { c = c.copy(headingOffsetRad = Angles.wrap(it)) }
                "headingOffsetDeg" -> {
                    val deg = value.asDouble()
                    if (deg == null || deg < -360.0 || deg > 360.0) {
                        errors.add("headingOffsetDeg must be a number between -360 and 360")
                    } else {
                        c = c.copy(headingOffsetRad = Angles.wrap(Math.toRadians(deg)))
                    }
                }
                "headingAxis" -> {
                    val name = value.asText()?.trim()?.uppercase()
                    val axis = HeadingAxisMode.entries.firstOrNull { it.name == name }
                    if (axis == null) errors.add("headingAxis must be one of " + HeadingAxisMode.entries.joinToString()) else c = c.copy(headingAxis = axis)
                }
                "useMagnetometer" -> bool(key, value, errors)?.let { c = c.copy(useMagnetometer = it) }
                "magGateTolerance" -> number(key, value, errors)?.let { c = c.copy(magGateTolerance = it) }
                "gyroBias" -> vector(value, errors)?.let { c = c.copy(gyroBias = it) }
                "stepMinIntervalS" -> number(key, value, errors)?.let { c = c.copy(stepMinIntervalS = it) }
                "stepMinSwing" -> number(key, value, errors)?.let { c = c.copy(stepMinSwing = it) }
                "stepBandLowHz" -> number(key, value, errors)?.let { c = c.copy(stepBandLowHz = it) }
                "stepBandHighHz" -> number(key, value, errors)?.let { c = c.copy(stepBandHighHz = it) }
                "preferHardwareSteps" -> bool(key, value, errors)?.let { c = c.copy(preferHardwareSteps = it) }
                "baroSmoothingS" -> number(key, value, errors)?.let { c = c.copy(baroSmoothingS = it) }
                "baroHoldWhenStill" -> bool(key, value, errors)?.let { c = c.copy(baroHoldWhenStill = it) }
                "baroStillGapS" -> number(key, value, errors)?.let { c = c.copy(baroStillGapS = it) }
                "loopClosure" -> bool(key, value, errors)?.let { c = c.copy(loopClosure = it) }
                "smoothingWindow" -> number(key, value, errors)?.let { c = c.copy(smoothingWindow = it.toInt()) }
                "pdrFallbackWhenTrackingLost" -> bool(key, value, errors)?.let { c = c.copy(pdrFallbackWhenTrackingLost = it) }
                "vioResamplePeriodS" -> number(key, value, errors)?.let { c = c.copy(vioResamplePeriodS = it) }
                else -> warnings.add("Ignored unknown key \"$key\"")
            }
        }
        if (c.stepBandHighHz <= c.stepBandLowHz) errors.add("stepBandHighHz must be above stepBandLowHz")
        if (errors.isNotEmpty()) return ProposalOutcome.Rejected(errors)

        val changes = ConfigSchema.changedKeys(base, c).map { key ->
            ConfigChange(key, ConfigSchema.valueText(base, key) ?: "", ConfigSchema.valueText(c, key) ?: "", reasons[key])
        }
        if (changes.isEmpty()) warnings.add("The answer changes nothing")
        return ProposalOutcome.Accepted(Proposal(c, changes, reasoning, expected, warnings))
    }

    /**
     * The first JSON object in [text]: the content of a fenced block when there is one, else the
     * outermost braces. A model that wraps its answer in prose is still understood.
     */
    fun extractObject(text: String): JsonObject? {
        val candidates = ArrayList<String>()
        val fence = Regex("```(?:json|JSON)?\\s*\\n([\\s\\S]*?)```")
        for (m in fence.findAll(text)) candidates.add(m.groupValues[1])
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) candidates.add(text.substring(start, end + 1))
        for (candidate in candidates) {
            val trimmed = candidate.trim()
            val s = trimmed.indexOf('{')
            val e = trimmed.lastIndexOf('}')
            if (s < 0 || e <= s) continue
            val parsed = runCatching { json.parseToJsonElement(trimmed.substring(s, e + 1)) }.getOrNull()
            if (parsed is JsonObject) return parsed
        }
        return null
    }

    private fun changeReasons(element: JsonElement?): Map<String, String> {
        val array = element as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (item in array) {
            val o = item as? JsonObject ?: continue
            val key = o["key"].asText() ?: o["field"].asText() ?: continue
            val reason = o["reason"].asText() ?: o["why"].asText() ?: continue
            out[key] = reason
        }
        return out
    }

    private fun number(key: String, value: JsonElement, errors: MutableList<String>): Double? {
        val spec = ConfigSchema.find(key)
        val v = value.asDouble()
        if (v == null || v.isNaN() || v.isInfinite()) {
            errors.add("$key must be a number")
            return null
        }
        if (spec != null && (v < spec.min || v > spec.max)) {
            errors.add("$key = " + ConfigSchema.num(v) + " is outside " + ConfigSchema.num(spec.min) + " to " + ConfigSchema.num(spec.max))
            return null
        }
        if (spec?.kind == FieldKind.INTEGER && v != Math.floor(v)) {
            errors.add("$key must be a whole number")
            return null
        }
        return v
    }

    private fun bool(key: String, value: JsonElement, errors: MutableList<String>): Boolean? {
        val b = value.asBoolean()
        if (b == null) errors.add("$key must be true or false")
        return b
    }

    private fun vector(value: JsonElement, errors: MutableList<String>): Vec3? {
        val parts: List<Double?> = when (value) {
            is JsonObject -> listOf(value["x"].asDouble(), value["y"].asDouble(), value["z"].asDouble())
            is JsonArray -> if (value.size == 3) value.map { it.asDouble() } else listOf(null, null, null)
            else -> listOf(null, null, null)
        }
        val spec = ConfigSchema.find("gyroBias")!!
        if (parts.any { it == null || it < spec.min || it > spec.max }) {
            errors.add("gyroBias must be {x, y, z} with each component between " + ConfigSchema.num(spec.min) + " and " + ConfigSchema.num(spec.max))
            return null
        }
        return Vec3(parts[0]!!, parts[1]!!, parts[2]!!)
    }

    private fun JsonElement?.asDouble(): Double? {
        val p = this as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()
    }

    private fun JsonElement?.asBoolean(): Boolean? {
        val p = this as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> null
        }
    }

    private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Kept for callers that only want to know whether the text holds a JSON object at all. */
    fun looksLikeJson(text: String): Boolean = runCatching { extractObject(text)?.jsonObject != null }.getOrDefault(false)
}

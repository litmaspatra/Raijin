package io.raijin.brain

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.net.URLEncoder
import java.util.Locale

/**
 * AP-3: deep-link URI builders for known apps. Pure string/phone logic,
 * unit-tested in brain-core; the actuator owns Intent construction and
 * installation checks.
 */
object DeepLinks {

    /** Maps search: geo URI with the query; the Maps app (or any handler) opens the results screen. */
    fun mapsSearchUri(query: String): String =
        "geo:0,0?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * Result of normalizing a stored phone number to E.164 digits.
     *
     * [regionInferred] is true when the number was national and a region was
     * used to complete it: that region (device SIM/locale, or a user's spoken
     * answer) is an assumption, never proof, and must be shown in the
     * confirmation before anything opens.
     */
    data class NormalizedNumber(
        val e164Digits: String,
        val regionInferred: Boolean,
        val regionIso: String?,
    )

    /**
     * Normalize a phone number to E.164 digits for wa.me, or null when the
     * number is ambiguous/invalid and the caller must clarify.
     *
     * - A leading "+" is the only unambiguous international form; it is
     *   parsed region-free and validated as-is.
     * - Everything else is parsed by libphonenumber UNDER THE SUPPLIED
     *   [regionIso], and the parse provenance decides what is safe:
     *     - FROM_NUMBER_WITH_IDD: the region's own international dialing
     *       prefix (00 in India/the UK, 011 in the US, ...) was used. The
     *       region shaped the interpretation, so the result is flagged
     *       [NormalizedNumber.regionInferred] and the confirmation shows it.
     *     - FROM_DEFAULT_COUNTRY: a national number for that region, with
     *       trunk prefixes and significant leading zeros resolved by the
     *       library's national rules (never hand-stripped). Region-inferred.
     *     - FROM_NUMBER_WITHOUT_PLUS_SIGN: bare digits that the library
     *       resolved by pulling a country code out of the string itself
     *       (e.g. "919876543210"). Ambiguous with a national number carrying
     *       a trunk/prefix, so rejected: clarify, never guess.
     * - Digits-only strings are never assumed to be international: without a
     *   region they are ambiguous and return null.
     */
    fun normalizeWhatsAppNumber(rawPhone: String, regionIso: String?): NormalizedNumber? {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) return null
        val compact = trimmed.replace(Regex("[\\s().-]"), "")
        return try {
            if (compact.startsWith("+")) {
                parseExplicitInternational(compact)
            } else {
                val region = regionIso?.trim()?.uppercase()
                    ?.takeIf { phoneUtil.supportedRegions.contains(it) }
                    ?: return null
                val parsed = phoneUtil.parseAndKeepRawInput(trimmed, region)
                when (parsed.countryCodeSource) {
                    PhoneNumber.CountryCodeSource.FROM_NUMBER_WITHOUT_PLUS_SIGN -> null
                    PhoneNumber.CountryCodeSource.FROM_NUMBER_WITH_IDD,
                    PhoneNumber.CountryCodeSource.FROM_DEFAULT_COUNTRY,
                    PhoneNumber.CountryCodeSource.UNSPECIFIED -> {
                        if (!phoneUtil.isValidNumber(parsed)) return null
                        NormalizedNumber(
                            e164Digits = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).removePrefix("+"),
                            regionInferred = true,
                            regionIso = region,
                        )
                    }
                    else -> null
                }
            }
        } catch (_: NumberParseException) {
            null
        }
    }

    private fun parseExplicitInternational(e164: String): NormalizedNumber? {
        val parsed = phoneUtil.parse(e164, null)
        if (!phoneUtil.isValidNumber(parsed)) return null
        return NormalizedNumber(
            e164Digits = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).removePrefix("+"),
            regionInferred = false,
            regionIso = null,
        )
    }

    /** wa.me chat link, or null when the number can't be normalized. */
    fun whatsAppChatUri(rawPhone: String, regionIso: String?): String? =
        normalizeWhatsAppNumber(rawPhone, regionIso)?.let { "https://wa.me/${it.e164Digits}" }

    /** Human-readable country name for a region ISO code, for confirmation prompts. */
    fun regionDisplayName(regionIso: String): String =
        Locale.Builder().setRegion(regionIso).build().getDisplayCountry(Locale.ENGLISH)

    /**
     * Spoken-country aliases for the ambiguity clarification flow ("which
     * country is it?"). Only explicit user answers land here; an unknown
     * country returns null and the caller keeps clarifying, never guesses.
     */
    fun regionForCountryName(spoken: String): String? =
        COUNTRY_ALIASES[spoken.trim().lowercase()]

    private val COUNTRY_ALIASES: Map<String, String> = mapOf(
        "india" to "IN",
        "united states" to "US", "usa" to "US", "us" to "US",
        "america" to "US", "united states of america" to "US",
        "canada" to "CA",
        "uk" to "GB", "united kingdom" to "GB", "britain" to "GB",
        "great britain" to "GB", "england" to "GB",
        "australia" to "AU",
        "uae" to "AE", "united arab emirates" to "AE", "dubai" to "AE",
        "singapore" to "SG",
        "italy" to "IT", "germany" to "DE", "france" to "FR",
        "spain" to "ES", "portugal" to "PT", "netherlands" to "NL",
        "holland" to "NL", "ireland" to "IE", "switzerland" to "CH",
        "sweden" to "SE", "norway" to "NO", "denmark" to "DK",
        "poland" to "PL", "turkey" to "TR", "turkiye" to "TR",
        "pakistan" to "PK", "bangladesh" to "BD", "sri lanka" to "LK",
        "nepal" to "NP", "china" to "CN", "japan" to "JP",
        "south korea" to "KR", "korea" to "KR", "indonesia" to "ID",
        "malaysia" to "MY", "philippines" to "PH", "thailand" to "TH",
        "vietnam" to "VN", "brazil" to "BR", "mexico" to "MX",
        "saudi arabia" to "SA", "qatar" to "QA", "egypt" to "EG",
        "israel" to "IL", "russia" to "RU", "south africa" to "ZA",
        "nigeria" to "NG", "kenya" to "KE", "new zealand" to "NZ",
    )
}

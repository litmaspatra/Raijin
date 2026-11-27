package io.raijin.brain

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TM-1 golden cases. `now` is fixed: Wednesday 2026-11-11, 10:00 IST.
 * Deterministic: same text + same now = same result, always.
 */
class HinglishTimeParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, zone)

    private fun at(d: Int, h: Int, m: Int, s: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone)

    private fun expect(text: String, expected: ZonedDateTime) {
        val parsed = HinglishTimeParser.parse(text, now)
        assertNotNull(parsed, "expected a parse for: $text")
        assertEquals(expected, parsed.triggerAt, "wrong trigger for: $text")
    }

    private fun expectRecurrence(text: String, recurrence: HinglishTimeParser.Recurrence) {
        val parsed = HinglishTimeParser.parse(text, now)
        assertNotNull(parsed, "expected a parse for: $text")
        assertEquals(recurrence, parsed.recurrence, "wrong recurrence for: $text")
    }

    // ---------------- Relative: English ----------------

    @Test fun rel_in_5_minutes() = expect("remind me in 5 minutes", at(11, 10, 5))
    @Test fun rel_in_1_minute() = expect("in 1 minute", at(11, 10, 1))
    @Test fun rel_in_15_minutes() = expect("in 15 minutes", at(11, 10, 15))
    @Test fun rel_in_30_min() = expect("in 30 min", at(11, 10, 30))
    @Test fun rel_in_45_mins() = expect("in 45 mins", at(11, 10, 45))
    @Test fun rel_in_1_hour() = expect("in 1 hour", at(11, 11, 0))
    @Test fun rel_in_2_hours() = expect("call maa in 2 hours", at(11, 12, 0))
    @Test fun rel_in_3_hrs() = expect("in 3 hrs", at(11, 13, 0))
    @Test fun rel_in_1_day() = expect("in 1 day", at(12, 10, 0))
    @Test fun rel_in_2_days() = expect("in 2 days", at(13, 10, 0))
    @Test fun rel_in_30_seconds() = expect("in 30 seconds", at(11, 10, 0, 30))
    @Test fun rel_in_90_seconds() = expect("in 90 seconds", at(11, 10, 1, 30))
    @Test fun rel_in_ten_minutes_word() = expect("in ten minutes", at(11, 10, 10))
    @Test fun rel_in_two_hours_word() = expect("in two hours", at(11, 12, 0))
    @Test fun rel_in_five_minutes_word() = expect("in five minutes", at(11, 10, 5))

    // ---------------- Relative: Hinglish ----------------

    @Test fun rel_5_minute_baad() = expect("5 minute baad", at(11, 10, 5))
    @Test fun rel_10_min_baad() = expect("10 min baad", at(11, 10, 10))
    @Test fun rel_20_minute_baad() = expect("mujhe 20 minute baad yaad dilana", at(11, 10, 20))
    @Test fun rel_1_ghanta_baad() = expect("1 ghanta baad", at(11, 11, 0))
    @Test fun rel_2_ghante_baad() = expect("2 ghante baad", at(11, 12, 0))
    @Test fun rel_3_ghante_baad() = expect("3 ghante baad", at(11, 13, 0))
    @Test fun rel_1_din_baad() = expect("1 din baad", at(12, 10, 0))
    @Test fun rel_2_din_baad() = expect("2 din baad", at(13, 10, 0))
    @Test fun rel_paanch_minute_baad() = expect("paanch minute baad", at(11, 10, 5))
    @Test fun rel_do_ghante_baad() = expect("do ghante baad", at(11, 12, 0))
    @Test fun rel_teen_din_baad() = expect("teen din baad", at(14, 10, 0))
    @Test fun rel_das_minute_baad() = expect("das minute baad", at(11, 10, 10))
    @Test fun rel_aadha_ghanta_baad() = expect("aadha ghanta baad", at(11, 10, 30))
    @Test fun rel_dedh_ghante_baad() = expect("dedh ghante baad", at(11, 11, 30))
    @Test fun rel_dhai_ghanta_baad() = expect("dhai ghanta baad", at(11, 12, 30))
    @Test fun rel_5_minute_mein() = expect("5 minute mein", at(11, 10, 5))

    // ---------------- Dayparts alone ----------------

    @Test fun dp_subah_rolls_to_tomorrow() = expect("subah", at(12, 7, 0)) // 7am already past
    @Test fun dp_dopahar_today() = expect("dopahar", at(11, 13, 0))
    @Test fun dp_shaam_today() = expect("shaam", at(11, 18, 0))
    @Test fun dp_raat_today() = expect("raat", at(11, 21, 0))
    @Test fun dp_morning_rolls() = expect("morning", at(12, 7, 0))
    @Test fun dp_evening_today() = expect("evening", at(11, 18, 0))
    @Test fun dp_night_today() = expect("night", at(11, 21, 0))
    @Test fun dp_afternoon_today() = expect("afternoon", at(11, 13, 0))

    // ---------------- Day words + dayparts ----------------

    @Test fun day_aaj_shaam() = expect("aaj shaam", at(11, 18, 0))
    @Test fun day_aaj_shaam_ko() = expect("aaj shaam ko", at(11, 18, 0))
    @Test fun day_aaj_raat() = expect("aaj raat", at(11, 21, 0))
    @Test fun day_kal() = expect("kal", at(12, 7, 0))
    @Test fun day_kal_subah() = expect("kal subah", at(12, 7, 0))
    @Test fun day_kal_dopahar() = expect("kal dopahar", at(12, 13, 0))
    @Test fun day_kal_shaam() = expect("kal shaam", at(12, 18, 0))
    @Test fun day_kal_raat() = expect("kal raat", at(12, 21, 0))
    @Test fun day_parso() = expect("parso", at(13, 7, 0))
    @Test fun day_parso_subah() = expect("parso subah", at(13, 7, 0))
    @Test fun day_parso_shaam() = expect("parso shaam", at(13, 18, 0))
    @Test fun day_tomorrow_morning() = expect("tomorrow morning", at(12, 7, 0))
    @Test fun day_today_evening() = expect("today evening", at(11, 18, 0))
    @Test fun day_day_after_tomorrow() = expect("day after tomorrow", at(13, 7, 0))

    // ---------------- Clock forms ----------------

    @Test fun clk_kal_subah_6_baje() = expect("kal subah 6 baje", at(12, 6, 0))
    @Test fun clk_kal_subah_5_30_baje() = expect("kal subah 5:30 baje", at(12, 5, 30))
    @Test fun clk_raat_9_baje() = expect("raat 9 baje", at(11, 21, 0))
    @Test fun clk_raat_10_baje() = expect("raat 10 baje", at(11, 22, 0))
    @Test fun clk_raat_11_baje() = expect("aaj raat 11 baje", at(11, 23, 0))
    @Test fun clk_shaam_6_baje() = expect("shaam 6 baje", at(11, 18, 0))
    @Test fun clk_shaam_5_baje() = expect("aaj shaam 5 baje", at(11, 17, 0))
    @Test fun clk_subah_6_baje_tomorrow() = expect("subah 6 baje", at(12, 6, 0)) // 6am past
    @Test fun clk_dopahar_2_baje() = expect("dopahar 2 baje", at(11, 14, 0))
    @Test fun clk_3pm() = expect("3pm", at(11, 15, 0))
    @Test fun clk_3_45_pm() = expect("3:45pm", at(11, 15, 45))
    @Test fun clk_11am() = expect("11am", at(11, 11, 0))
    @Test fun clk_12pm_noon() = expect("12pm", at(11, 12, 0))
    @Test fun clk_12am_midnight_tonight() = expect("12am", at(12, 0, 0))
    @Test fun clk_8_baje_evening() = expect("8 baje", at(11, 20, 0)) // 8am past, pick 8pm
    @Test fun clk_9_baje_evening() = expect("9 baje", at(11, 21, 0))
    @Test fun clk_kal_8_baje_is_morning() = expect("kal 8 baje", at(12, 8, 0))
    @Test fun clk_sawa_6_baje() = expect("sawa 6 baje", at(11, 18, 15))
    @Test fun clk_sadhe_6_baje() = expect("sadhe 6 baje", at(11, 18, 30))
    @Test fun clk_paune_7_baje() = expect("paune 7 baje", at(11, 18, 45))
    @Test fun clk_raat_sawa_8_baje() = expect("raat sawa 8 baje", at(11, 20, 15))
    @Test fun clk_raat_sadhe_10_baje() = expect("raat sadhe 10 baje", at(11, 22, 30))
    @Test fun clk_kal_7_15_am() = expect("kal 7:15 am", at(12, 7, 15))
    @Test fun clk_6_30_pm() = expect("6:30 pm", at(11, 18, 30))

    // ---------------- Weekdays + recurrence ----------------

    @Test fun wd_har_somvar_8_baje() = expect("har somvar 8 baje", at(16, 8, 0)) // next Monday
    @Test fun wd_har_somvar_weekly() = expectRecurrence("har somvar 8 baje", HinglishTimeParser.Recurrence.WEEKLY)
    @Test fun wd_roz_subah_7_baje() = expect("roz subah 7 baje", at(12, 7, 0))
    @Test fun wd_roz_daily() = expectRecurrence("roz subah 7 baje", HinglishTimeParser.Recurrence.DAILY)
    @Test fun wd_har_roz_6_baje() = expect("har roz 6 baje", at(11, 18, 0)) // 6am past -> 6pm today
    @Test fun wd_har_roz_daily() = expectRecurrence("har roz 6 baje", HinglishTimeParser.Recurrence.DAILY)
    @Test fun wd_every_monday_8am() = expect("every monday 8am", at(16, 8, 0))
    @Test fun wd_every_friday_5pm() = expect("every friday 5pm", at(13, 17, 0))
    @Test fun wd_every_friday_weekly() = expectRecurrence("every friday 5pm", HinglishTimeParser.Recurrence.WEEKLY)
    @Test fun wd_next_friday_3pm() = expect("next friday 3pm", at(13, 15, 0))
    @Test fun wd_next_friday_one_shot() = expectRecurrence("next friday 3pm", HinglishTimeParser.Recurrence.NONE)
    @Test fun wd_shanivar_raat_10_baje() = expect("shanivar raat 10 baje", at(14, 22, 0))
    @Test fun wd_ravivar_subah() = expect("ravivar subah", at(15, 7, 0))
    @Test fun wd_mangalvar_shaam_7_baje() = expect("mangalvar shaam 7 baje", at(17, 19, 0))
    @Test fun wd_budhvar_dopahar_1_baje() = expect("budhvar dopahar 1 baje", at(11, 13, 0)) // today IS Wednesday
    @Test fun wd_guruvar_subah_9_baje() = expect("guruvar subah 9 baje", at(12, 9, 0))
    @Test fun wd_shukravar_raat_8_baje() = expect("shukravar raat 8 baje", at(13, 20, 0))
    @Test fun wd_itvar_5_pm() = expect("itvar 5 pm", at(15, 17, 0))
    @Test fun wd_daily_english() = expectRecurrence("daily raat 9 baje", HinglishTimeParser.Recurrence.DAILY)
    @Test fun wd_daily_raat_9_baje() = expect("daily raat 9 baje", at(11, 21, 0))

    // ---------------- Full phrases ----------------

    @Test fun phrase_remind_kal_subah() =
        expect("mujhe kal subah 6 baje uthna hai", at(12, 6, 0))
    @Test fun phrase_call_maa_shaam() =
        expect("remind me to call maa aaj shaam 7 baje", at(11, 19, 0))
    @Test fun phrase_dawai_raat() =
        expect("dawai leni hai raat 9 baje", at(11, 21, 0))
    @Test fun phrase_meeting_tomorrow_11am() =
        expect("meeting tomorrow at 11am", at(12, 11, 0))

    // ---------------- Determinism + nulls ----------------

    @Test fun deterministic_same_input_same_output() {
        val a = HinglishTimeParser.parse("kal subah 6 baje", now)
        val b = HinglishTimeParser.parse("kal subah 6 baje", now)
        assertEquals(a, b)
    }

    @Test fun null_plain_greeting() = assertNull(HinglishTimeParser.parse("hi kaise ho", now))
    @Test fun null_no_time_words() = assertNull(HinglishTimeParser.parse("call maa", now))
    @Test fun null_random_text() = assertNull(HinglishTimeParser.parse("the quick brown fox", now))
    @Test fun null_empty() = assertNull(HinglishTimeParser.parse("", now))
    @Test fun null_garbage_number() = assertNull(HinglishTimeParser.parse("42 is the answer", now))

    @Test fun does_not_confuse_kal_with_yesterday() {
        // "kal" is always resolved forwards for reminders.
        val p = HinglishTimeParser.parse("kal 9 baje", now)
        assertNotNull(p)
        assertEquals(at(12, 9, 0), p.triggerAt)
    }

    // ---------------- Voice-transcript meridiem forms (a.m. / p.m.) ----------------
    // Regression: "9:00 a.m." was not read, so "morning" fell back to its 7:00 default.

    @Test fun dotted_am_beats_morning_default() = expect("tomorrow morning at 9:00 a.m.", at(12, 9, 0))
    @Test fun dotted_pm_beats_evening_default() = expect("tomorrow evening at 6:30 p.m.", at(12, 18, 30))
    @Test fun dotted_evening_at_8_pm() = expect("tomorrow evening at 8:00 p.m.", at(12, 20, 0))
    @Test fun dotted_am_bare_hour() = expect("tomorrow at 8 a.m.", at(12, 8, 0))
    @Test fun spaced_am_letters() = expect("tomorrow morning 9 a m", at(12, 9, 0))
    @Test fun uppercase_dotted() = expect("tomorrow morning at 9:00 A.M.", at(12, 9, 0))
    @Test fun plain_am_still_works() = expect("tomorrow morning at 9 am", at(12, 9, 0))
    @Test fun number_word_am() = expect("tomorrow nine a.m.", at(12, 9, 0))
    @Test fun daypart_only_keeps_default() = expect("kal subah", at(12, 7, 0))
    @Test fun every_hour_dotted_am_and_pm() {
        for (h in 1..12) {
            expect("tomorrow at $h:00 a.m.", at(12, h % 12, 0))
            expect("tomorrow at $h:00 p.m.", at(12, h % 12 + 12, 0))
        }
    }
}

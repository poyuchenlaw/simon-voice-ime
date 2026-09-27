package com.simon.voiceime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class BopomofoDictionaryTest {
    @Test public void first_symbol_prioritizes_simon_personal_phrase() {
        List<String> suggestions = new BopomofoDictionary().suggest("ㄔ", 3);
        assertFalse(suggestions.isEmpty());
        assertEquals("陳柏諭", suggestions.get(0));
        assertTrue(suggestions.contains("陳報狀"));
    }

    @Test public void longer_prefix_narrows_without_network_or_semantic_model() {
        List<String> suggestions = new BopomofoDictionary().suggest("ㄕㄨ", 3);
        assertEquals("書記官", suggestions.get(0));
    }

    @Test public void omitted_tone_marks_do_not_hide_a_personal_candidate() {
        List<String> suggestions = new BopomofoDictionary().suggest("ㄔㄣ", 3);
        assertEquals("陳柏諭", suggestions.get(0));
    }

    @Test public void omitted_tones_between_syllables_still_match() {
        List<String> suggestions = new BopomofoDictionary().suggest("ㄔㄣㄅ", 3);
        assertEquals("陳報狀", suggestions.get(0));
    }

    @Test public void unknown_prefix_fails_open_to_no_candidate() {
        assertTrue(new BopomofoDictionary().suggest("ㄦ", 3).isEmpty());
    }

    @Test public void confirmed_manual_choice_is_ranked_ahead_of_seed_data() {
        BopomofoDictionary dictionary = new BopomofoDictionary();
        dictionary.recordConfirmed("ㄔ", "陳柏諭律師");
        assertEquals("陳柏諭律師", dictionary.suggest("ㄔ", 3).get(0));
    }
}

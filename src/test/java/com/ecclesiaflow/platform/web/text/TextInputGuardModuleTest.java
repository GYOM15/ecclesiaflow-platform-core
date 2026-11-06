package com.ecclesiaflow.platform.web.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextInputGuardModuleTest {

    record Item(String label) {
    }

    record Event(String title, List<Item> items, List<String> tags, Map<String, String> notes, int seats) {
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new TextInputGuardModule());

    @Test
    @DisplayName("every string of the body is guarded, in records, lists and maps alike")
    void guardsEveryString() throws Exception {
        Event event = mapper.readValue("""
                {"title":"Cul\\u200bte","items":[{"label":"Ch\\u2060ant"}],"tags":["Pri\\ufeffère"],
                 "notes":{"k":"No\\u200bte"},"seats":3}""", Event.class);

        assertThat(event.title()).isEqualTo("Culte");
        assertThat(event.items()).containsExactly(new Item("Chant"));
        assertThat(event.tags()).containsExactly("Prière");
        assertThat(event.notes()).containsEntry("k", "Note");
        assertThat(event.seats()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unsafe character is dropped wherever it sits, the body still read")
    void dropsUnsafeCharacters() throws Exception {
        Event event = mapper.readValue("{\"title\":\"a\\u0007b\",\"items\":[{\"label\":\"c\\u202ed\"}]}", Event.class);

        assertThat(event.title()).isEqualTo("ab");
        assertThat(event.items()).containsExactly(new Item("cd"));
        assertThat(mapper.readValue("\"e\\u2066f\"", String.class)).isEqualTo("ef");
    }

    @Test
    @DisplayName("nulls and the scalars Jackson turns into strings go through as before")
    void keepsJacksonCoercions() throws Exception {
        assertThat(mapper.readValue("{\"title\":null}", Event.class).title()).isNull();
        assertThat(mapper.readValue("{\"title\":42}", Event.class).title()).isEqualTo("42");
    }
}

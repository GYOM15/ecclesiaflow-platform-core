package com.ecclesiaflow.platform.web.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("an unsafe character names the field it sat in")
    void namesTheField() {
        assertThatThrownBy(() -> mapper.readValue("{\"items\":[{\"label\":\"ok\"},{\"label\":\"a\\u202eb\"}]}", Event.class))
                .isInstanceOfSatisfying(UnsafeTextInputException.class,
                        unsafe -> assertThat(unsafe.fieldPath()).isEqualTo("items[1].label"));
        assertThatThrownBy(() -> mapper.readValue("{\"title\":\"a\\u0007\"}", Event.class))
                .isInstanceOfSatisfying(UnsafeTextInputException.class,
                        unsafe -> assertThat(unsafe.fieldPath()).isEqualTo("title"));
    }

    @Test
    @DisplayName("a bare string body has no field to name")
    void bareString() {
        assertThatThrownBy(() -> mapper.readValue("\"a\\u2066b\"", String.class))
                .isInstanceOfSatisfying(UnsafeTextInputException.class, unsafe -> assertThat(unsafe.fieldPath()).isNull());
    }

    @Test
    @DisplayName("nulls and the scalars Jackson turns into strings go through as before")
    void keepsJacksonCoercions() throws Exception {
        assertThat(mapper.readValue("{\"title\":null}", Event.class).title()).isNull();
        assertThat(mapper.readValue("{\"title\":42}", Event.class).title()).isEqualTo("42");
    }
}

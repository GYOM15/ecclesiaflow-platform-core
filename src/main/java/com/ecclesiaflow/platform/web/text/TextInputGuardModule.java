package com.ecclesiaflow.platform.web.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;

/** Every string a request body carries goes through {@link TextInputGuard}, whatever object holds it. */
public final class TextInputGuardModule extends SimpleModule {

    public TextInputGuardModule() {
        super("ecclesiaflow-text-input-guard");
        addDeserializer(String.class, new GuardedStringDeserializer());
    }

    static final class GuardedStringDeserializer extends StdScalarDeserializer<String> {

        GuardedStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return TextInputGuard.clean(StringDeserializer.instance.deserialize(parser, context));
        }

        @Override
        public LogicalType logicalType() {
            return LogicalType.Textual;
        }

        @Override
        public boolean isCachable() {
            return true;
        }
    }
}

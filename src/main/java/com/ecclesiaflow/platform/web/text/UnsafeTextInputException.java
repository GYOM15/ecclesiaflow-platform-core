package com.ecclesiaflow.platform.web.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/** Jackson's form of {@link UnsafeTextException}, so the parser records the path of the field that held the text. */
public final class UnsafeTextInputException extends MismatchedInputException {

    UnsafeTextInputException(JsonParser parser) {
        super(parser, "A text holds a control, bidirectional or other unsafe character.", String.class);
    }

    /** The field's path in the body, « items[0].label », or null for a bare string body. */
    public String fieldPath() {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }
}

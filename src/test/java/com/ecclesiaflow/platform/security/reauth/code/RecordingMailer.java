package com.ecclesiaflow.platform.security.reauth.code;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records each mail, and refuses them while {@link #failure} is set. */
public final class RecordingMailer implements EmailCodeMailer {

    public final List<EmailCodeDelivery> sent = new CopyOnWriteArrayList<>();
    public volatile RuntimeException failure;

    @Override
    public void send(EmailCodeDelivery delivery) {
        if (failure != null) {
            throw failure;
        }
        sent.add(delivery);
    }

    public String lastCode() {
        return sent.get(sent.size() - 1).code();
    }
}

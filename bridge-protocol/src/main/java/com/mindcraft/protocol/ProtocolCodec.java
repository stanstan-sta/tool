package com.mindcraft.protocol;

import com.google.gson.Gson;

public final class ProtocolCodec {
    private static final Gson GSON = new Gson();

    public static String encode(Object message) {
        return GSON.toJson(message);
    }

    public static String peekType(String json) {
        try {
            Envelope e = GSON.fromJson(json, Envelope.class);
            return e == null ? null : e.type;
        } catch (Exception ex) {
            return null;
        }
    }

    public static <T> T decode(String json, Class<T> cls) {
        try {
            return GSON.fromJson(json, cls);
        } catch (Exception ex) {
            return null;
        }
    }

    private ProtocolCodec() {}
}

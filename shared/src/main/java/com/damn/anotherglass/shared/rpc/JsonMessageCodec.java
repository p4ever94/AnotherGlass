package com.damn.anotherglass.shared.rpc;

import android.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public final class JsonMessageCodec {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(RPCMessage.class, new RPCMessageDeserializer())
            .registerTypeAdapter(byte[].class, new ByteArrayBase64Adapter())
            .create();

    private JsonMessageCodec() {
    }

    public static String toJsonLine(RPCMessage message) {
        return GSON.toJson(message) + "\n";
    }

    public static byte[] toJsonLineBytes(RPCMessage message) {
        return toJsonLine(message).getBytes(StandardCharsets.UTF_8);
    }

    public static RPCMessage fromJsonLine(String line) {
        return GSON.fromJson(line, RPCMessage.class);
    }

    private static final class RPCMessageDeserializer implements JsonDeserializer<RPCMessage> {
        @Override
        public RPCMessage deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return new RPCMessage(null, null);
            }

            var jsonObject = json.getAsJsonObject();
            if (!jsonObject.has("service") || jsonObject.get("service").isJsonNull()) {
                return new RPCMessage(null, null);
            }

            String service = jsonObject.get("service").getAsString();
            String payloadType = null;
            if (jsonObject.has("type") && !jsonObject.get("type").isJsonNull()) {
                payloadType = jsonObject.get("type").getAsString();
            }

            Object payload = null;
            if (payloadType != null && jsonObject.has("payload") && !jsonObject.get("payload").isJsonNull()) {
                try {
                    Class<?> payloadClass = Class.forName(payloadType);
                    payload = context.deserialize(jsonObject.get("payload"), payloadClass);
                } catch (ClassNotFoundException e) {
                    throw new JsonParseException("Unable to find class: " + payloadType, e);
                }
            }
            return new RPCMessage(service, payloadType, payload);
        }
    }

    private static final class ByteArrayBase64Adapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {
        @Override
        public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null) {
                return JsonNull.INSTANCE;
            }
            return new JsonPrimitive(Base64.encodeToString(src, Base64.NO_WRAP));
        }

        @Override
        public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                return Base64.decode(json.getAsString(), Base64.DEFAULT);
            }
            throw new JsonParseException("Expected Base64 byte[] value");
        }
    }
}

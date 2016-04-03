/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.queue;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.wire.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by peter on 24/03/16.
 */
public class MethodReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodReader.class);
    private final ExcerptTailer tailer;
    private final WireParser<Void> wireParser;

    public MethodReader(ExcerptTailer tailer, Object... objects) {
        this.tailer = tailer;
        WireParselet defaultParselet = (s, v, $) ->
                LOGGER.warn("Unknown message " + s + ' ' + v.text());
        if (objects[0] instanceof WireParselet)
            defaultParselet = (WireParselet) objects[0];
        wireParser = WireParser.wireParser(defaultParselet);

        Set<String> methodsHandled = new HashSet<>();
        for (Object o : objects) {
            for (Method m : o.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers()))
                    continue;

                try {
                    Object.class.getMethod(m.getName(), m.getParameterTypes());
                    continue;
                } catch (NoSuchMethodException e) {
                    // not an Object method.
                }

                if (!methodsHandled.add(m.getName()))
                    continue;

                Class<?>[] parameterTypes = m.getParameterTypes();
                if (parameterTypes.length != 1)
                    continue;

                Class msgClass = parameterTypes[0];
                m.setAccessible(true); // turn of security check to make a little faster
                if (msgClass.isInterface() || !ReadMarshallable.class.isAssignableFrom(msgClass)) {
                    Object[] argArr = {null};
                    wireParser.register(m::getName, (s, v, $) -> {
                        try {
                            if (Jvm.isDebug())
                                logMessage(s, v);

                            argArr[0] = v.object(msgClass);
                            m.invoke(o, argArr);
                        } catch (Exception i) {
                            LOGGER.error("Failure to dispatch message: " + m.getName() + " " + argArr[0], i);
                        }
                    });

                } else {
                    ReadMarshallable arg;
                    try {
                        arg = (ReadMarshallable) msgClass.newInstance();
                    } catch (Exception e) {
                        arg = (ReadMarshallable) OS.memory().allocateInstance(msgClass);
                    }
                    ReadMarshallable[] argArr = {arg};
                    wireParser.register(m::getName, (s, v, $) -> {
                        try {
                            if (Jvm.isDebug())
                                logMessage(s, v);

                            v.marshallable(argArr[0]);
                            m.invoke(o, argArr);
                        } catch (Exception i) {
                            LOGGER.error("Failure to dispatch message: " + m.getName() + " " + argArr[0], i);
                        }
                    });
                }
            }
        }

        if (wireParser.lookup("history") == null) {
            wireParser.register(() -> "history", (s, v, $) -> {
                v.marshallable(ExcerptHistory.get());
            });
        }
    }

    static void logMessage(CharSequence s, ValueIn v) {
        String name = s.toString();
        String rest;

        if (v.wireIn() instanceof BinaryWire) {
            Bytes bytes = Bytes.elasticByteBuffer((int) (v.wireIn().bytes().readRemaining() * 3 / 2 + 64));
            long pos = v.wireIn().bytes().readPosition();
            v.wireIn().copyTo(new TextWire(bytes));
            v.wireIn().bytes().readPosition(pos);
            rest = bytes.toString();
        } else {
            rest = v.toString();
        }
        LOGGER.debug("read " + name + " - " + rest);
    }

    /**
     * reads one message
     *
     * @return true if there was a message, or false if not.
     */
    public boolean readOne() {
        ExcerptHistory.get().reset();
        try (DocumentContext context = tailer.readingDocument()) {
            if (!context.isData())
                return false;
            wireParser.accept(context.wire(), null);
        }
        return true;
    }
}
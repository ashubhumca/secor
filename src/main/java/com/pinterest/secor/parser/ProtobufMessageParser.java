/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.secor.parser;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.CodedInputStream;
import com.pinterest.secor.common.SecorConfig;
import com.pinterest.secor.message.Message;

/**
 * Protocol buffer message timestamp extractor
 * 
 * If <code>secor.protobuf.message.class</code> is not set assumes that the very
 * first <code>uint64</code> field in a message is the timestamp. Otherwise,
 * uses <code>message.timestamp.name</code> as a path to get to the timestamp
 * field within protobuf message.
 *
 * @author Liam Stewart (liam.stewart@gmail.com)
 */
public class ProtobufMessageParser extends TimestampedMessageParser {

    private static final Logger LOG = LoggerFactory.getLogger(ProtobufMessageParser.class);

    private Method messageParseMethod;
    private String[] timestampFieldPath;

    public ProtobufMessageParser(SecorConfig config) {
        super(config);

        String messageClassName = mConfig.getProtobufMessageClass();
        if (messageClassName != null) {
            try {
                Class<?> messageClass = (Class<?>) Class.forName(messageClassName);
                messageParseMethod = messageClass.getDeclaredMethod("parseFrom", new Class<?>[] { byte[].class });

                String timestampFieldName = mConfig.getMessageTimestampName();
                String timestampFieldSeparator = mConfig.getMessageTimestampNameSeparator();
                if (timestampFieldSeparator == null || timestampFieldSeparator.isEmpty()) {
                    timestampFieldSeparator = ".";
                }
                LOG.info("Using protobuf timestamp field path: {} with separator: {}", timestampFieldName,
                        timestampFieldSeparator);
                timestampFieldPath = timestampFieldName.split(Pattern.quote(timestampFieldSeparator));
            } catch (ClassNotFoundException e) {
                LOG.error("Unable to load protobuf message class", e);
            } catch (NoSuchMethodException e) {
                LOG.error("Unable to find parseFrom() method in protobuf message class", e);
            } catch (SecurityException e) {
                LOG.error("Unable to use parseFrom() method from protobuf message class", e);
            }
        }
    }

    @Override
    public long extractTimestampMillis(final Message message) throws IOException {
        if (messageParseMethod != null) {
            com.google.protobuf.Message decodedMessage;
            try {
                decodedMessage = (com.google.protobuf.Message) messageParseMethod.invoke(null, message.getPayload());
                int i = 0;
                for (; i < timestampFieldPath.length - 1; ++i) {
                    decodedMessage = (com.google.protobuf.Message) decodedMessage
                            .getField(decodedMessage.getDescriptorForType().findFieldByName(timestampFieldPath[i]));
                }
                return toMillis((Long) decodedMessage
                        .getField(decodedMessage.getDescriptorForType().findFieldByName(timestampFieldPath[i])));
            } catch (IllegalArgumentException e) {
                throw new IOException("Unable to extract timestamp from protobuf message", e);
            } catch (IllegalAccessException e) {
                throw new IOException("Unable to extract timestamp from protobuf message", e);
            } catch (InvocationTargetException e) {
                throw new IOException("Unable to extract timestamp from protobuf message", e);
            }
        } else {
            // Assume that the timestamp field is the first field, is required,
            // and is a uint64.

            CodedInputStream input = CodedInputStream.newInstance(message.getPayload());
            // Don't really care about the tag, but need to read it to get, to
            // the payload.
            input.readTag();
            return toMillis(input.readUInt64());
        }
    }
}

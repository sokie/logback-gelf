/*
 * Logback GELF - zero dependencies Logback GELF appender library.
 * Copyright (C) 2016 Oliver Siegmar
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package de.siegmar.logbackgelf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Marker;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LevelToSyslogSeverity;
import ch.qos.logback.core.LayoutBase;

/**
 * This class is responsible for transforming a Logback log event to a GELF message.
 */
public class GelfLayout extends LayoutBase<ILoggingEvent> {

    private static final Pattern VALID_ADDITIONAL_FIELD_PATTERN = Pattern.compile("^[\\w\\.\\-]*$");
    private static final double MSEC_DIVIDER = 1000D;

    private static final String DEFAULT_SHORT_PATTERN = "%m%nopex";
    private static final String DEFAULT_FULL_PATTERN = "%xEx";

    private String originHost;
    private boolean includeRawMessage;
    private boolean includeMdcData = true;
    private boolean includeCallerData;
    private boolean includeLevelName;
    private PatternLayout shortPatternLayout;
    private PatternLayout fullPatternLayout;
    private Map<String, Object> staticFields = new HashMap<>();

    public String getOriginHost() {
        return originHost;
    }

    public void setOriginHost(final String originHost) {
        this.originHost = originHost;
    }

    public boolean isIncludeRawMessage() {
        return includeRawMessage;
    }

    public void setIncludeRawMessage(final boolean includeRawMessage) {
        this.includeRawMessage = includeRawMessage;
    }

    public boolean isIncludeMdcData() {
        return includeMdcData;
    }

    public void setIncludeMdcData(final boolean includeMdcData) {
        this.includeMdcData = includeMdcData;
    }

    public boolean isIncludeCallerData() {
        return includeCallerData;
    }

    public void setIncludeCallerData(final boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }

    public boolean isIncludeLevelName() {
        return includeLevelName;
    }

    public void setIncludeLevelName(final boolean includeLevelName) {
        this.includeLevelName = includeLevelName;
    }

    public PatternLayout getShortPatternLayout() {
        return shortPatternLayout;
    }

    public void setShortPatternLayout(final PatternLayout shortPatternLayout) {
        this.shortPatternLayout = shortPatternLayout;
    }

    public PatternLayout getFullPatternLayout() {
        return fullPatternLayout;
    }

    public void setFullPatternLayout(final PatternLayout fullPatternLayout) {
        this.fullPatternLayout = fullPatternLayout;
    }

    public Map<String, Object> getStaticFields() {
        return staticFields;
    }

    public void setStaticFields(final Map<String, Object> staticFields) {
        this.staticFields = Objects.requireNonNull(staticFields);
    }

    public void addStaticField(final String staticField) {
        final String[] split = staticField.split(":", 2);
        if (split.length == 2) {
            addField(staticFields, split[0].trim(), split[1].trim());
        } else {
            addWarn("staticField must be in format key:value - rejecting '" + staticField + "'");
        }
    }

    private void addField(final Map<String, Object> dst, final String key, final String value) {
        if (key.isEmpty()) {
            addWarn("staticField key must not be empty");
        } else if ("id".equalsIgnoreCase(key)) {
            addWarn("staticField key name 'id' is prohibited");
        } else if (!VALID_ADDITIONAL_FIELD_PATTERN.matcher(key).matches()) {
            addWarn("staticField key '" + key + "' is illegal. "
                + "Keys must apply to regex ^[\\w\\.\\-]*$");
        } else {
            dst.put(key, value);
        }
    }

    @Override
    public void start() {
        if (originHost == null || originHost.trim().isEmpty()) {
            originHost = buildHostname();
        }
        if (shortPatternLayout == null) {
            shortPatternLayout = buildPattern(DEFAULT_SHORT_PATTERN);
        }
        if (fullPatternLayout == null) {
            fullPatternLayout = buildPattern(DEFAULT_FULL_PATTERN);
        }

        super.start();
    }

    private String buildHostname() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (final UnknownHostException e) {
            addWarn("Could not determine local hostname", e);
            return "unknown";
        }
    }

    private PatternLayout buildPattern(final String pattern) {
        final PatternLayout patternLayout = new PatternLayout();
        patternLayout.setContext(getContext());
        patternLayout.setPattern(pattern);
        patternLayout.start();
        return patternLayout;
    }

    @Override
    public String doLayout(final ILoggingEvent event) {
        final String shortMessage = shortPatternLayout.doLayout(event);
        final String fullMessage = includeCallerData && event.hasCallerData()
            ? fullPatternLayout.doLayout(event)
            : null;
        final double timestamp = event.getTimeStamp() / MSEC_DIVIDER;
        final Map<String, Object> additionalFields = mapAdditionalFields(event);

        final GelfMessage gelfMessage =
            new GelfMessage(originHost, shortMessage, fullMessage, timestamp,
                LevelToSyslogSeverity.convert(event), additionalFields);

        return gelfMessage.toJSON();
    }

    private Map<String, Object> mapAdditionalFields(final ILoggingEvent event) {
        final Map<String, Object> additionalFields = new HashMap<>(staticFields);

        additionalFields.put("logger_name", event.getLoggerName());
        additionalFields.put("thread_name", event.getThreadName());

        if (includeRawMessage) {
            additionalFields.put("raw_message", event.getMessage());
        }

        final Marker marker = event.getMarker();
        if (marker != null) {
            additionalFields.put("marker", marker.getName());
        }

        if (includeLevelName) {
            additionalFields.put("level_name", event.getLevel().levelStr);
        }

        if (includeCallerData) {
            final StackTraceElement[] callerData = event.getCallerData();

            if (callerData != null && callerData.length > 0) {
                final StackTraceElement first = callerData[0];

                additionalFields.put("source_file_name", first.getFileName());
                additionalFields.put("source_method_name", first.getMethodName());
                additionalFields.put("source_class_name", first.getClassName());
                additionalFields.put("source_line_number", first.getLineNumber());
            }
        }

        if (includeMdcData) {
            for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                addField(additionalFields, entry.getKey(), entry.getValue());
            }
        }

        return additionalFields;
    }

}
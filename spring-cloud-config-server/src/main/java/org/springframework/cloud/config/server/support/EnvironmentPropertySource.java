/*
 * Copyright 2018-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.support;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.io.JsonStringEncoder;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * @author Spencer Gibb
 */
public class EnvironmentPropertySource extends PropertySource<Environment> {

	// "\${" (from text) or "\\${" from JSON to signal escaped placeholder
	private static final Pattern ESCAPED_PLACEHOLDERS = Pattern.compile("[\\\\]{1,2}\\$\\{");

	/**
	 * Pattern to find placeholder expressions in text.
	 */
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

	/**
	 * Output format for placeholder resolution.
	 */
	public enum OutputFormat {

		/** Plain text output. */
		PLAIN,
		/** YAML output with block scalar support. */
		YAML,
		/** JSON output with escaped newlines. */
		JSON

	}

	public EnvironmentPropertySource(Environment sources) {
		super("cloudEnvironment", sources);
	}

	public static StandardEnvironment prepareEnvironment(Environment environment) {
		StandardEnvironment standardEnvironment = new StandardEnvironment();
		standardEnvironment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		standardEnvironment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		standardEnvironment.getPropertySources().addFirst(new EnvironmentPropertySource(environment));
		return standardEnvironment;
	}

	public static String resolvePlaceholders(StandardEnvironment preparedEnvironment, String text) {
		return resolvePlaceholders(preparedEnvironment, text, OutputFormat.PLAIN);
	}

	public static String resolvePlaceholders(StandardEnvironment preparedEnvironment, String text,
			OutputFormat format) {
		// Mask out escaped placeholders
		text = ESCAPED_PLACEHOLDERS.matcher(text).replaceAll("\\$_{");

		switch (format) {
			case YAML:
				text = resolveYamlPlaceholders(preparedEnvironment, text);
				break;
			case JSON:
				text = resolvePlaceholdersWithTransform(preparedEnvironment, text, v -> {
					StringBuilder sb = new StringBuilder();
					JsonStringEncoder.getInstance().quoteAsString(v, sb);
					return sb.toString();
				});
				break;
			default:
				text = preparedEnvironment.resolvePlaceholders(text);
				break;
		}

		return text.replace("$_{", "${");
	}

	/**
	 * Resolve placeholders in YAML text. When a resolved value contains newlines, format
	 * it as a YAML block scalar with proper indentation.
	 */
	private static String resolveYamlPlaceholders(StandardEnvironment env, String text) {
		StringBuilder result = new StringBuilder();
		String[] lines = text.split("\n", -1);

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher matcher = PLACEHOLDER_PATTERN.matcher(line);

			if (matcher.find() && isStandalonePlaceholder(line, matcher)) {
				String blockLine = resolveAsBlockScalar(line, matcher, env);
				if (blockLine != null) {
					result.append(blockLine);
					if (i < lines.length - 1) {
						result.append("\n");
					}
					continue;
				}
			}

			// Default: resolve inline (no multiline handling needed)
			result.append(env.resolvePlaceholders(line));
			if (i < lines.length - 1) {
				result.append("\n");
			}
		}

		return result.toString();
	}

	/**
	 * Resolve placeholders using Spring's {@link PropertyPlaceholderHelper} with a
	 * transform applied to each resolved value before insertion.
	 */
	private static String resolvePlaceholdersWithTransform(StandardEnvironment env, String text,
			UnaryOperator<String> valueTransform) {
		PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", null, true);
		return helper.replacePlaceholders(text, (placeholder) -> {
			String value = env.getProperty(placeholder);
			if (value == null) {
				return null;
			}
			return valueTransform.apply(value);
		});
	}

	/**
	 * Check if a placeholder is the sole content at its position in the YAML line.
	 * Supports:
	 * <ul>
	 * <li>mapping values: "key: ${placeholder}"</li>
	 * <li>sequence items: "- ${placeholder}"</li>
	 * <li>sequence item mappings: "- key: ${placeholder}"</li>
	 * </ul>
	 */
	private static boolean isStandalonePlaceholder(String line, Matcher matcher) {
		String trimmed = line.stripLeading();
		String expectedPlaceholder = "${" + matcher.group(1) + "}";

		// Sequence item: "- ${placeholder}"
		if (trimmed.startsWith("- ")) {
			String afterDash = trimmed.substring(2).trim();
			if (afterDash.equals(expectedPlaceholder)) {
				return true;
			}
			// Sequence item mapping: "- key: ${placeholder}"
			int colonIdx = afterDash.indexOf(':');
			if (colonIdx >= 0) {
				String valuePart = afterDash.substring(colonIdx + 1).trim();
				return valuePart.equals(expectedPlaceholder);
			}
			return false;
		}

		// Mapping value: "key: ${placeholder}"
		int colonIdx = line.indexOf(':');
		if (colonIdx < 0) {
			return false;
		}
		String valuePart = line.substring(colonIdx + 1).trim();
		return valuePart.equals(expectedPlaceholder);
	}

	/**
	 * Resolve a placeholder to a YAML block scalar if the value is multiline. Returns
	 * null if the resolved value is not multiline. Handles mapping lines, sequence items,
	 * and sequence item mappings.
	 */
	private static String resolveAsBlockScalar(String line, Matcher matcher, StandardEnvironment env) {
		String resolved = resolveValue(env, matcher.group(1));
		if (resolved == null || !resolved.contains("\n")) {
			return null;
		}

		// Compute leading whitespace
		int leadingSpaces = 0;
		while (leadingSpaces < line.length() && line.charAt(leadingSpaces) == ' ') {
			leadingSpaces++;
		}

		String trimmed = line.substring(leadingSpaces);
		String prefix;
		int contentIndent;

		if (trimmed.startsWith("- ")) {
			String afterDash = trimmed.substring(2).trim();
			int colonIdx = afterDash.indexOf(':');
			if (colonIdx >= 0 && !afterDash.startsWith("${")) {
				// Sequence item mapping: "- key: ${placeholder}"
				// Prefix is everything up to and including the colon
				String key = afterDash.substring(0, colonIdx);
				prefix = " ".repeat(leadingSpaces) + "- " + key + ":";
				// Content indented 2 past the key start (which is at leadingSpaces + 2)
				contentIndent = leadingSpaces + 2 + 2;
			}
			else {
				// Bare sequence item: "- ${placeholder}"
				prefix = " ".repeat(leadingSpaces) + "-";
				// Content indented relative to after the "- " marker
				contentIndent = leadingSpaces + 2;
			}
		}
		else {
			// Mapping value: "key: ${placeholder}"
			int colonIdx = line.indexOf(':');
			prefix = line.substring(0, colonIdx + 1);
			contentIndent = leadingSpaces + 2;
		}

		String indent = " ".repeat(contentIndent);
		StringBuilder sb = new StringBuilder();
		sb.append(prefix).append(" |");
		String[] valueLines = resolved.split("\n", -1);
		for (int j = 0; j < valueLines.length; j++) {
			if (j == valueLines.length - 1 && valueLines[j].isEmpty()) {
				continue;
			}
			sb.append("\n").append(indent).append(valueLines[j]);
		}
		return sb.toString();
	}

	/**
	 * Resolve a single placeholder key, handling default values (key:default).
	 */
	private static String resolveValue(StandardEnvironment env, String placeholder) {
		String key = placeholder.strip();
		String defaultValue = null;
		int colonIdx = key.indexOf(':');
		if (colonIdx >= 0) {
			defaultValue = key.substring(colonIdx + 1);
			key = key.substring(0, colonIdx);
		}
		String value = env.getProperty(key);
		if (value != null) {
			return value;
		}
		return defaultValue;
	}

	@Override
	public Object getProperty(String name) {
		for (org.springframework.cloud.config.environment.PropertySource source : getSource().getPropertySources()) {
			Map<?, ?> map = source.getSource();
			if (map.containsKey(name)) {
				return map.get(name);
			}
		}
		return null;
	}

}

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

import org.junit.jupiter.api.Test;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.support.EnvironmentPropertySource.OutputFormat;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.config.server.support.EnvironmentPropertySource.prepareEnvironment;
import static org.springframework.cloud.config.server.support.EnvironmentPropertySource.resolvePlaceholders;

public class EnvironmentPropertySourceTest {

	private final StandardEnvironment env = new StandardEnvironment();

	@Test
	public void testEscapedPlaceholdersRemoved() {
		assertThat(resolvePlaceholders(this.env, "\\${abc}")).isEqualTo("${abc}");
		// JSON generated from jackson will be double escaped
		assertThat(resolvePlaceholders(this.env, "\\\\${abc}")).isEqualTo("${abc}");
	}

	@Test
	public void multilinePlaceholderResolvedAsYamlBlockScalar() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("greeting", "hello\nworld\n");
		map.put("ref", "${greeting}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String yaml = "greeting: |\n  hello\n  world\nref: ${greeting}\n";
		String resolved = resolvePlaceholders(prepared, yaml, OutputFormat.YAML);

		assertThat(resolved).contains("ref: |\n  hello\n  world");
		assertThat(resolved).doesNotContain("ref: hello\nworld");
	}

	@Test
	public void multilinePlaceholderResolvedAsJsonEscaped() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("greeting", "hello\nworld\n");
		map.put("ref", "${greeting}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String json = "{\"greeting\":\"hello\\nworld\\n\",\"ref\":\"${greeting}\"}";
		String resolved = resolvePlaceholders(prepared, json, OutputFormat.JSON);

		assertThat(resolved).isEqualTo("{\"greeting\":\"hello\\nworld\\n\",\"ref\":\"hello\\nworld\\n\"}");
	}

	@Test
	public void singleLinePlaceholderUnchangedInYaml() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("foo", "bar");
		map.put("ref", "${foo}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String yaml = "foo: bar\nref: ${foo}\n";
		String resolved = resolvePlaceholders(prepared, yaml, OutputFormat.YAML);

		assertThat(resolved).isEqualTo("foo: bar\nref: bar\n");
	}

	@Test
	public void multilinePlaceholderInSequenceItem() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("greeting", "hello\nworld\n");
		map.put("items[0]", "${greeting}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String yaml = "items:\n- ${greeting}\n";
		String resolved = resolvePlaceholders(prepared, yaml, OutputFormat.YAML);

		assertThat(resolved).isEqualTo("items:\n- |\n  hello\n  world\n");
		// Verify it parses as valid YAML
		java.util.Map<String, Object> parsed = new org.yaml.snakeyaml.Yaml().load(resolved);
		@SuppressWarnings("unchecked")
		java.util.List<String> items = (java.util.List<String>) parsed.get("items");
		assertThat(items.get(0)).isEqualTo("hello\nworld\n");
	}

	@Test
	public void multilinePlaceholderInSequenceItemMapping() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("greeting", "hello\nworld\n");
		map.put("items[0].ref", "${greeting}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String yaml = "items:\n- ref: ${greeting}\n";
		String resolved = resolvePlaceholders(prepared, yaml, OutputFormat.YAML);

		assertThat(resolved).isEqualTo("items:\n- ref: |\n    hello\n    world\n");
		// Verify it parses as valid YAML
		java.util.Map<String, Object> parsed = new org.yaml.snakeyaml.Yaml().load(resolved);
		@SuppressWarnings("unchecked")
		java.util.List<java.util.Map<String, String>> items = (java.util.List<java.util.Map<String, String>>) parsed
			.get("items");
		assertThat(items.get(0).get("ref")).isEqualTo("hello\nworld\n");
	}

	@Test
	public void multilinePlaceholderInIndentedSequenceItem() {
		Environment environment = new Environment("test", "default");
		java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
		map.put("greeting", "hello\nworld\n");
		map.put("nested.items[0]", "${greeting}");
		environment.add(new org.springframework.cloud.config.environment.PropertySource("one", map));
		StandardEnvironment prepared = prepareEnvironment(environment);

		String yaml = "nested:\n  items:\n  - ${greeting}\n";
		String resolved = resolvePlaceholders(prepared, yaml, OutputFormat.YAML);

		assertThat(resolved).isEqualTo("nested:\n  items:\n  - |\n    hello\n    world\n");
		// Verify it parses as valid YAML
		java.util.Map<String, Object> parsed = new org.yaml.snakeyaml.Yaml().load(resolved);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> nested = (java.util.Map<String, Object>) parsed.get("nested");
		@SuppressWarnings("unchecked")
		java.util.List<String> items = (java.util.List<String>) nested.get("items");
		assertThat(items.get(0)).isEqualTo("hello\nworld\n");
	}

	@Test
	public void placeholderWithWhitespaceInKeyResolved() {
		// Simulate end-to-end: source YAML has placeholder spanning multiple lines.
		// SnakeYAML folds newlines to spaces when parsing plain scalars.
		String sourceYaml = "greeting: |\n  hello\n  world\nref: ${\n       greeting\n     }\n";
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = new org.yaml.snakeyaml.Yaml().load(sourceYaml);

		// Build Environment from parsed properties (as config server would)
		Environment environment = new Environment("test", "default");
		environment.add(new PropertySource("one", parsed));
		StandardEnvironment prepared = prepareEnvironment(environment);

		// Re-serialize (as EnvironmentController does before resolving)
		String serialized = new org.yaml.snakeyaml.Yaml().dumpAsMap(parsed);
		String resolved = resolvePlaceholders(prepared, serialized, OutputFormat.YAML);

		assertThat(resolved).contains("ref: |\n  hello\n  world");
	}

}

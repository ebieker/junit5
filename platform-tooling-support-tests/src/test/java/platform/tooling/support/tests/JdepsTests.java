/*
 * Copyright 2015-2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */

package platform.tooling.support.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import com.paypal.digraph.parser.GraphEdge;
import com.paypal.digraph.parser.GraphNode;
import com.paypal.digraph.parser.GraphParser;

import de.sormuras.bartholdy.jdk.Jdeps;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import platform.tooling.support.Helper;
import platform.tooling.support.Request;

/**
 * @since 1.3
 */
class JdepsTests {

	private static Set<String> KNOWN_BAD_EDGES = Set.of(
		"org.junit.jupiter.engine.descriptor.TestInstanceLifecycleUtils -> org.junit.jupiter.engine.Constants",
		"org.junit.jupiter.engine.discovery.JavaElementsResolver -> org.junit.jupiter.engine.JupiterTestEngine",
		"org.junit.jupiter.engine.execution.ConditionEvaluator -> org.junit.jupiter.engine.Constants",
		"org.junit.jupiter.engine.extension.ExtensionRegistry -> org.junit.jupiter.engine.Constants");

	private static Set<String> IGNORE_TARGET_STARTING_WITH = Set.of( //
		"java.", //
		"org.apiguardian.", //
		"org.junit.jupiter.params.shadow.com.univocity.parsers");

	@ParameterizedTest
	@MethodSource("platform.tooling.support.Helper#loadModuleDirectoryNames")
	void modules(String module, TestReporter reporter) throws Exception {
		var version = Helper.version(module);
		var archive = module + '-' + version + ".jar";
		var path = Paths.get("..", module, "build", "libs", archive);
		var destination = Paths.get("build", "test-workspace", "jdeps-tests-modules", module);
		var builder = Request.builder() //
				.setTool(new Jdeps()) //
				.setProject("jdeps-cycle-check") //
				.setWorkspace("jdeps-cycle-check/" + module);

		if (module.equals("junit-platform-commons")) {
			builder.addArguments("--multi-release", 9);
		}

		var result = builder.addArguments("--dot-output", destination) //
				.addArguments("-verbose") // -verbose:class -filter:none ... filter "$" types
				.addArguments(path) //
				.build() //
				.run();

		var dot = destination.resolve(archive + ".dot");
		var raw = Files.write(destination.resolve(archive + ".raw.dot"), //
			Files.readAllLines(dot) //
					.stream() //
					.map(line -> line.replaceAll(" \\(.+\\)", "")) //
					.collect(Collectors.toList()));

		assertEquals(0, result.getExitCode(), "result = " + result);
		assertEquals("", result.getOutput("err"), "error log isn't empty");

		var parser = new GraphParser(new FileInputStream(raw.toFile()));
		var graph = new DirectedAcyclicGraph<String, DefaultEdge>(DefaultEdge.class);

		var bads = new ArrayList<String>();
		var expectedPackagePrefix = "org." + module.replace('-', '.');

		edge_loop: for (GraphEdge edge : parser.getEdges().values()) {

			// cleanup raw names
			var source = classNameOf(edge.getNode1());
			var target = classNameOf(edge.getNode2());

			// inspecting only "org.junit" artifacts
			assertTrue(source.startsWith(expectedPackagePrefix), source);

			// ignore some target packages
			for (var prefix : IGNORE_TARGET_STARTING_WITH) {
				if (target.startsWith(prefix)) {
					continue edge_loop;
				}
			}

			// extract package names
			var fromPackage = packageNameOf(source);
			var toPackage = packageNameOf(target);

			// refs to same package are always okay
			if (fromPackage.equals(toPackage)) {
				continue;
			}

			if (!graph.containsVertex(fromPackage)) {
				graph.addVertex(fromPackage);
			}
			if (!graph.containsVertex(toPackage)) {
				graph.addVertex(toPackage);
			}

			try {
				graph.addEdge(fromPackage, toPackage);
			}
			catch (IllegalArgumentException e) {
				var badEdge = source + " -> " + target;
				reporter.publishEntry("bad edge", badEdge);
				if (!KNOWN_BAD_EDGES.contains(badEdge)) {
					bads.add(badEdge);
				}
			}
		}

		assertTrue(bads.isEmpty(), bads.size() + " bad edge(s) found, expected 0.");
	}

	private static String classNameOf(GraphNode node) {
		var name = node.getId();
		// strip `"` from names
		name = name.replaceAll("\"", "");
		// remove leading artifacts, like "9/" from a multi-release jar
		var indexOfSlash = name.indexOf('/');
		if (indexOfSlash >= 0) {
			name = name.substring(indexOfSlash + 1);
		}
		return name;
	}

	private static String packageNameOf(String className) {
		var indexOfLastDot = className.lastIndexOf('.');
		if (indexOfLastDot < 0) {
			return "";
		}
		return className.substring(0, indexOfLastDot);
	}

}

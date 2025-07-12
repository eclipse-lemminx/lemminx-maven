/*******************************************************************************
 * Copyright (c) 2022, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.hover;

import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
class ManagedVersionHoverTest {

	private XMLLanguageService languageService;

	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}

	@Test
	@Timeout(90000)
	void testManagedVersionHoverInDependencyChild() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-dependencyManagement-child.xml", languageService);
		Hover hover = languageService.doHover(document, new Position(15, 25), createSharedSettings());
		String value = hover.getContents().getRight().getValue();
		System.out.println("testManagedVersionHoverInDependencyChild: [" + value + "]");
		assertNotNull(value);
		assertTrue(value.contains("The managed version is"));
		assertTrue(value.contains("2.22.2"));
		assertTrue(value.contains("The artifact is managed in"));
		assertTrue(value.contains("dependencyManagement:parent:0.0.1-SNAPSHOT"));
		assertTrue(value.contains("pom-dependencyManagement-parent.xml"));
		assertTrue(value.contains("The managed scope is: \"compile\""));
	}

	@Test
	@Timeout(90000)
	void testManagedVersionHoverInDependencyParent() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-dependencyManagement-parent.xml", languageService);
		Hover hover = languageService.doHover(document, new Position(13, 29), createSharedSettings());
		String value = hover.getContents().getRight().getValue();
		System.out.println("testManagedVersionHoverInDependencyParent: [" + value + "]");
		assertNotNull(value);
		assertFalse(value.contains("The managed version is"));
		assertFalse(value.contains("The artifact is managed in"));
		assertFalse(value.contains("The managed scope is:"));
	}

	@Test
	@Timeout(90000)
	void testManagedVersionHoverInPluginChild() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-pluginManagement-child.xml", languageService);
		Hover hover = languageService.doHover(document, new Position(18, 33), createSharedSettings());
		String value = hover.getContents().getRight().getValue();
		System.out.println("testManagedVersionHoverInPluginChild: [" + value + "]");
		assertNotNull(value);
		assertTrue(value.contains("The managed version is"));
		assertTrue(value.contains("2.22.2"));
		assertTrue(value.contains("The artifact is managed in"));
		assertTrue(value.contains("pluginManagement:parent:0.0.1-SNAPSHOT"));
		assertTrue(value.contains("pom-pluginManagement-parent.xml"));
		assertFalse(value.contains("The managed scope is:"));
	}

	@Test
	@Timeout(90000)
	void testManagedVersionHoverInPluginParent() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-pluginManagement-parent.xml", languageService);
		Hover hover = languageService.doHover(document, new Position(14, 33), createSharedSettings());
		String value = hover.getContents().getRight().getValue();
		System.out.println("testManagedVersionHoverInPluginParent: [" + value + "]");
		assertNotNull(value);
		assertFalse(value.contains("The managed version is"));
		assertFalse(value.contains("The artifact is managed in"));
		assertFalse(value.contains("The managed scope is:"));
	}
	
    @Test
    @Timeout(90000)
    void testManagedVersionHoverInDependencyGrandchild() throws IOException, URISyntaxException {
        DOMDocument document = createDOMDocument("/hierarchy2/child/grandchild/pom.xml", languageService);
        Hover hover = languageService.doHover(document, new Position(11, 28), createSharedSettings());
        String value = hover.getContents().getRight().getValue();
        System.out.println("testManagedVersionHoverInDependencyGrandchild: [" + value + "]");
        assertNotNull(value);
        assertTrue(value.contains("The managed version is"));
        assertTrue(value.contains("2.0.6"));
        assertTrue(value.contains("The artifact is managed in"));
        assertTrue(value.contains("com.zollum.demo:demo:0.0.1-SNAPSHOT"));
        assertTrue(value.contains("hierarchy2/pom.xml"));
		assertTrue(value.contains("The managed scope is: \"compile\""));
    }
	
    @Test
    @Timeout(90000)
    void testManagedVersionHoverForBomProvidedDependency() throws IOException, URISyntaxException {
        DOMDocument document = createDOMDocument("/eclipse-bom-tester/pom.xml", languageService);
        Position position = new Position(26, 30);
        
        // Find Definition links
		List<? extends LocationLink> definitions = languageService.findDefinition(document, position, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(u -> System.out.println("Definition Link: " + u));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uri.endsWith("/fish/payara/api/payara-bom/5.2022.3/payara-bom-5.2022.3.pom")));

        // Find Hover with managed version
        Hover hover = languageService.doHover(document, position, createSharedSettings());
        final String value = hover.getContents().getRight().getValue();
        System.out.println("Hover Text: [" + value + "]");
        assertNotNull(value);
        assertTrue(value.contains("The managed version is"));
        assertTrue(value.contains("8.0.0"));
        assertTrue(value.contains("The artifact is managed in"));
        assertTrue(value.contains("fish.payara.api:payara-bom:5.2022.3"));
        assertTrue(value.contains("/fish/payara/api/payara-bom/5.2022.3/payara-bom-5.2022.3.pom"));
        
        // Compare the links from definition and hover
		assertTrue(definitions.stream().map(LocationLink::getTargetUri)
				.anyMatch(uri -> value.replace('\\', '/').contains(uri.substring("file:/".length()))));

		assertTrue(value.contains("The managed scope is: \"provided\""));
		System.out.println("<<< testManagedVersionHoverForBomProvidedDependency");
    }

    @Test
    @Timeout(90000)
    void testManagedVersionHoverForBomProvidedDependencyWithProperty() throws IOException, URISyntaxException {
        DOMDocument document = createDOMDocument("/eclipse-bom-tester/pom.xml", languageService);
        Position position = new Position(32, 30);

        // Find Definition links
		List<? extends LocationLink> definitions = languageService.findDefinition(document, position, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(u -> System.out.println("Definition Link: " + u));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uri.endsWith("/eclipse-bom-tester/parent/pom.xml")));

        // Find Hover with managed version
        Hover hover = languageService.doHover(document, position, createSharedSettings());
        final String value = hover.getContents().getRight().getValue();
        System.out.println("Hover Text: [" + value + "]");
        assertNotNull(value);
        assertTrue(value.contains("The managed version is"));
        assertTrue(value.contains("3.18.0"));
        assertTrue(value.contains("The artifact is managed in"));
        assertTrue(value.contains("org.eclipse.test:bom-import-parent:1.0.0"));
        assertTrue(value.contains("eclipse-bom-tester/parent/pom.xml"));

        // Compare the links from definition and hover
		assertTrue(definitions.stream().map(LocationLink::getTargetUri)
				.anyMatch(uri -> value.replace('\\', '/').contains(uri.substring("file:/".length()))));

        assertTrue(value.contains("The managed scope is: \"compile\""));
    }

    // Enable MARKDOWN format
	private static SharedSettings createSharedSettings() {
		HoverCapabilities hoverCapabilities = new HoverCapabilities();
		hoverCapabilities.setContentFormat(Arrays.asList(MarkupKind.MARKDOWN));
		SharedSettings sharedSettings = new SharedSettings();
		sharedSettings.getHoverSettings().setCapabilities(hoverCapabilities);
		return  sharedSettings;
	}
}
